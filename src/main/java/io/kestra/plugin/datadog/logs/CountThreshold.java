package io.kestra.plugin.datadog.logs;

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
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Datadog v2 operationId: AggregateLogs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Start a flow when a log count crosses a threshold", description = "Polls a sliding log window. Needs logs_read_data. The first successful poll records state only. Fires when count exceeds threshold and re-arms only after count drops below threshold. State is saved after execution creation. Rate limits and server errors skip the poll.")
@Plugin(examples = @Example(title = "React to an error burst", full = true, code = """
    id: datadog_error_burst
    namespace: company.observability
    triggers:
      - id: burst
        type: io.kestra.plugin.datadog.logs.CountThreshold
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders status:error"
        window: PT15M
        threshold: 10
        interval: PT5M
    tasks:
      - id: log
        type: io.kestra.plugin.core.log.Log
        message: "Log count crossed the threshold: {{ trigger.count }}"
    """))
public class CountThreshold extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<CountThreshold.Output> {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Builder.Default
    @Schema(title = "Polling interval", description = "Positive duration between polls.", defaultValue = "PT5M")
    @PluginProperty(group = "reliability")
    private final Duration interval = Duration.ofMinutes(5);

    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog API key", description = "Organization API key from a Kestra secret.")
    private Property<String> apiKey;

    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog application key", description = "Application key with logs_read_data from a Kestra secret.")
    private Property<String> appKey;

    @Schema(title = "Datadog site", description = "Organization site such as datadoghq.com or datadoghq.eu.")
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(title = "API base URL", description = "Optional proxy or test endpoint override.")
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    @Builder.Default
    @Schema(title = "Query", description = "Log Explorer query to count.", defaultValue = "*")
    @PluginProperty(group = "main")
    private Property<String> query = Property.ofValue("*");

    @Schema(title = "Indexes", description = "Log indexes to count. All indexes when omitted.")
    @PluginProperty(group = "processing")
    private Property<List<String>> indexes;

    @Builder.Default
    @Schema(title = "Sliding window", description = "Positive whole-second duration ending at now. Sent as a Datadog relative window.", defaultValue = "PT15M")
    @PluginProperty(group = "processing")
    private Property<Duration> window = Property.ofValue(Duration.ofMinutes(15));

    @NotNull
    @Schema(title = "Threshold", description = "Positive count boundary. Fires above it and re-arms below it. Equality retains state.")
    @PluginProperty(group = "main")
    private Property<Long> threshold;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rBoundary = Counts.threshold(runContext.render(threshold).as(Long.class).orElse(null));
        if (rBoundary == 0) {
            throw new IllegalArgumentException("The Datadog trigger `threshold` must be positive so the count can drop below it");
        }
        var rDuration = runContext.render(window).as(Duration.class).orElse(Duration.ofMinutes(15));
        if (rDuration.isNegative() || rDuration.isZero() || rDuration.getNano() != 0) {
            throw new IllegalArgumentException("The Datadog `window` must be a positive whole-second duration");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("The Datadog `interval` must be positive");
        }
        var rQuery = runContext.render(query).as(String.class).filter(v -> !v.isBlank()).orElse("*");
        var filter = new LinkedHashMap<String, Object>();
        filter.put("query", rQuery);
        filter.put("from", "now-" + rDuration.getSeconds() + "s");
        filter.put("to", "now");
        var rIndexes = runContext.render(indexes).asList(String.class);
        if (!rIndexes.isEmpty()) {
            filter.put("indexes", rIndexes);
        }
        try (var client = DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, true)) {
            var store = runContext.namespaceKv(context.getNamespace());
            var key = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
            var previous = readArmed(store, key);
            long count;
            try {
                count = Counts.query(client, filter, false);
            } catch (DatadogClient.DatadogException e) {
                if (e.isTransient()) {
                    runContext.logger().warn("Datadog count poll skipped after HTTP {}", e.getStatus());
                    return Optional.empty();
                }
                throw e;
            }
            var sampledAt = client.serverTime().orElse(null);
            if (sampledAt == null) {
                runContext.logger().warn("Datadog count poll skipped because the Date header is missing");
                return Optional.empty();
            }
            if (previous.isEmpty()) {
                writeArmed(store, key, !Counts.breached(count, rBoundary), sampledAt);
                return Optional.empty();
            }
            if (count < rBoundary) {
                writeArmed(store, key, true, sampledAt);
                return Optional.empty();
            }
            if (!previous.get() || !Counts.breached(count, rBoundary)) {
                return Optional.empty();
            }
            var output = Output.builder().count(count).threshold(rBoundary).build();
            var execution = TriggerService.generateExecution(this, conditionContext, context, output);
            writeArmed(store, key, false, sampledAt);
            return Optional.of(execution);
        }
    }

    private static Optional<Boolean> readArmed(KVStore store, String key) throws Exception {
        var stored = store.getValue(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        var state = switch (stored.get().value()) {
            case byte[] bytes -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case String text -> JacksonMapper.ofJson().readValue(text, MAP_TYPE);
            case Map<?, ?> map -> JacksonMapper.ofJson().convertValue(map, MAP_TYPE);
            default -> throw new IllegalStateException("Datadog count trigger state has an unexpected type");
        };
        if (!(state.get("armed") instanceof Boolean armed)) {
            throw new IllegalStateException("Datadog count trigger state is missing armed");
        }
        return Optional.of(armed);
    }

    private static void writeArmed(KVStore store, String key, boolean armed, Instant sampledAt) throws Exception {
        var bytes = JacksonMapper.ofJson().writeValueAsBytes(Map.of("armed", armed, "sampledAt", sampledAt.toString()));
        store.put(key, new KVValueAndMetadata(new KVMetadata("Datadog log count threshold state", (Duration) null), bytes));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Count in the current window")
        private final Long count;

        @Schema(title = "Crossed threshold")
        private final Long threshold;
    }
}
