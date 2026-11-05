package io.kestra.plugin.datadog.monitors;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.datadog.ConnectionDocs;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Datadog operationId: v1:ListMonitors. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@ValidDatadogLimits
@Schema(
    title = "Start a flow when Datadog monitors change to an alerting state",
    description = """
        Polls the Datadog monitors and starts one execution with the monitors that entered one of the watched `states` since the previous poll. \
        Needs the API key and an application key with the `monitors_read` scope.

        A monitor is returned when its `overall_state` is one of `states` and its `overall_state_modified` is later than the previous poll, \
        so a monitor that stays in alert is returned once, not at every poll. \
        `overall_state_modified` has a precision of one second, so the trigger keeps in the namespace KV store the newest value returned and the monitors returned at that second: \
        a monitor that changes in the same second as another one is not lost. \
        The first poll only records the time (taken from the Datadog clock, from the `Date` header of its answer): monitors that were already alerting are not returned. \
        A monitor that goes into alert and recovers between two polls is not seen, so keep `interval` shorter than the shortest outage you care about."""
)
@Plugin(
    examples = {
        @Example(
            title = "React to production monitors that go into alert",
            full = true,
            code = """
                id: datadog_on_alert
                namespace: company.observability

                triggers:
                  - id: on_alert
                    type: io.kestra.plugin.datadog.monitors.StateChange
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    monitorTags:
                      - "env:prod"
                    states:
                      - ALERT
                    interval: PT2M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} production monitor(s) went into alert: {{ trigger.monitors | jq('map(.name)') | first }}"
                """
        )
    }
)
public class StateChange extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<StateChange.Output> {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Builder.Default
    @Schema(title = "Interval between two polls", description = "The time between two polls of the Datadog API. Five minutes by default.", defaultValue = "PT5M")
    @PluginProperty(group = "reliability")
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(title = "Datadog API key", description = ConnectionDocs.API_KEY)
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(title = "Datadog application key", description = ConnectionDocs.APP_KEY + " The trigger needs the `monitors_read` scope.")
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> appKey;

    @Schema(title = "Datadog site", description = ConnectionDocs.SITE)
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(title = "API base URL", description = ConnectionDocs.API_URL)
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    @Schema(title = "Name filter", description = "Watches the monitors whose name contains this text.")
    @PluginProperty(group = "processing")
    private Property<String> name;

    @Schema(title = "Tags filter", description = "Watches the monitors that carry all these tags.")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Monitor tags filter", description = "Watches the monitors that carry all these monitor tags, such as `env:prod`.")
    @PluginProperty(group = "processing")
    private Property<List<String>> monitorTags;

    @Schema(title = "States to watch", description = "The monitor states that start an execution. `ALERT` only when it is not set.")
    @PluginProperty(group = "processing")
    private Property<List<State>> states;

    @Builder.Default
    @Schema(title = "Maximum number of monitors per poll", description = "Reads at most this many monitors from Datadog at each poll, between 1 and 10000. " +
        "Narrow the filters when the organization has more monitors than this.", defaultValue = "1000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxMonitors = Property.ofValue(1000);

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in monitor messages. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rLimit = runContext.render(this.maxMonitors).as(Integer.class).orElse(1000);
        if (rLimit < 1 || rLimit > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxMonitors` must be between 1 and 10000 but was " + rLimit);
        }
        var rStates = runContext.render(this.states).asList(State.class);
        var labels = (rStates.isEmpty() ? Set.of(State.ALERT) : Set.copyOf(rStates)).stream().map(State::label).collect(Collectors.toSet());

        var params = DatadogClient.query();
        runContext.render(this.name).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> params.put("name", value));
        Monitors.join(runContext.render(this.tags).asList(String.class)).ifPresent(value -> params.put("tags", value));
        Monitors.join(runContext.render(this.monitorTags).asList(String.class)).ifPresent(value -> params.put("monitor_tags", value));

        var kvStore = runContext.namespaceKv(context.getNamespace());
        var stateKey = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
        var stored = readCursor(kvStore, stateKey);

        List<Map<String, Object>> all;
        Instant serverTime;
        try (var client = DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, true)) {
            all = Monitors.collect(client, params, rLimit);
            serverTime = client.serverTime().orElse(null);
        } catch (DatadogClient.DatadogException e) {
            // a rate limit, an outage or a network failure must not stop the trigger: the next poll reads the same window again
            if (e.isTransient()) {
                logger.warn("The poll is skipped and will be retried on the next interval: {}", e.getMessage());
                return Optional.empty();
            }
            throw e;
        }

        if (all.size() >= rLimit) {
            logger.warn("Datadog returned {} monitors, the `maxMonitors` limit, so monitors past it are not watched. Narrow the poll with `name`, `tags` or `monitorTags` or raise `maxMonitors`.", all.size());
        }

        if (stored.isEmpty()) {
            // first poll: start from Datadog's own clock, so that a worker clock that runs ahead cannot hide the next alerts.
            // The monitors that changed in that same second are marked as seen, the ones that already alert are not replayed.
            if (serverTime == null) {
                logger.warn("Datadog monitor poll skipped because the Date header is missing");
                return Optional.empty();
            }
            var start = serverTime;
            writeCursor(kvStore, stateKey, new Cursor(start, idsModifiedAt(all, start)));
            logger.info("No cursor found, watching Datadog monitors that change state after {}", start);
            return Optional.empty();
        }
        var cursor = stored.get();

        // a monitor is new when it changed after the cursor or in the same second as the cursor without having been returned yet
        var changed = new ArrayList<Map<String, Object>>();
        for (var monitor : all) {
            if (!labels.contains(String.valueOf(monitor.get("overall_state")))) {
                continue;
            }
            var modified = modifiedAt(monitor.get("overall_state_modified"));
            if (modified == null || modified.isBefore(cursor.modified())) {
                continue;
            }
            if (modified.isAfter(cursor.modified()) || !cursor.ids().contains(idOf(monitor))) {
                changed.add(monitor);
            }
        }
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        changed.sort(Comparator.comparing(monitor -> modifiedAt(monitor.get("overall_state_modified"))));

        var newest = modifiedAt(changed.getLast().get("overall_state_modified"));
        var seen = new HashSet<String>(idsModifiedAt(changed, newest));
        if (newest.equals(cursor.modified())) {
            seen.addAll(cursor.ids());
        }

        var rIncludeSecrets = runContext.render(includeSecrets).as(Boolean.class).orElse(false);
        var output = Output.builder().monitors(changed.stream().map(monitor -> MonitorIO.redactMap(monitor, rIncludeSecrets)).toList()).count(changed.size()).build();

        // at-least-once: the cursor moves once the execution is built. If building fails, the next poll returns the same monitors.
        var execution = TriggerService.generateExecution(this, conditionContext, context, output);
        writeCursor(kvStore, stateKey, new Cursor(newest, seen));
        logger.info("{} Datadog monitor(s) entered {}", changed.size(), labels);
        return Optional.of(execution);
    }

    private static Instant modifiedAt(Object value) {
        var instant = parseInstant(value);
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
    }

    private static Set<String> idsModifiedAt(List<Map<String, Object>> monitors, Instant at) {
        return monitors.stream().filter(monitor -> at.equals(modifiedAt(monitor.get("overall_state_modified")))).map(StateChange::idOf).collect(Collectors.toSet());
    }

    private static String idOf(Map<String, Object> monitor) {
        return monitor.get("id") instanceof Number number ? String.valueOf(number.longValue()) : String.valueOf(monitor.get("id"));
    }

    static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value).trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // some answers carry no offset: read them as UTC
        }
        try {
            return Instant.parse(text.endsWith("Z") ? text : text + "Z");
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The newest {@code overall_state_modified} returned and the monitors returned at that very second: the field only has second precision. */
    private record Cursor(Instant modified, Set<String> ids) {
    }

    private static Optional<Cursor> readCursor(KVStore kvStore, String key) throws Exception {
        var stored = kvStore.getValue(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        var value = stored.get().value();
        var json = switch (value) {
            case byte[] bytes -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case String text -> JacksonMapper.ofJson().readValue(text, MAP_TYPE);
            case Map<?, ?> map -> JacksonMapper.ofJson().convertValue(map, MAP_TYPE);
            default -> throw new IllegalStateException("The Datadog trigger cursor '" + key + "' has an unexpected type: " + value);
        };
        var ids = json.get("ids") instanceof List<?> list ? list.stream().map(String::valueOf).collect(Collectors.toSet()) : Set.<String>of();
        return Optional.of(new Cursor(Instant.parse(String.valueOf(json.get("modified"))), ids));
    }

    private static void writeCursor(KVStore kvStore, String key, Cursor cursor) throws Exception {
        var bytes = JacksonMapper.ofJson().writeValueAsString(Map.of("modified", cursor.modified().toString(), "ids", cursor.ids().stream().sorted().toList())).getBytes(StandardCharsets.UTF_8);
        // no expiration: an expired cursor would replay or skip alerts
        kvStore.put(key, new KVValueAndMetadata(new KVMetadata("Datadog StateChange trigger cursor", (Duration) null), bytes));
    }

    public enum State {
        ALERT("Alert"),
        WARN("Warn"),
        NO_DATA("No Data"),
        OK("OK"),
        IGNORED("Ignored"),
        SKIPPED("Skipped"),
        UNKNOWN("Unknown");

        private final String label;

        State(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitors", description = "The monitors that changed state, from the oldest change to the newest. Use `{{ trigger.monitors }}`.")
        private final List<Map<String, Object>> monitors;

        @Schema(title = "Number of monitors")
        private final Integer count;
    }
}
