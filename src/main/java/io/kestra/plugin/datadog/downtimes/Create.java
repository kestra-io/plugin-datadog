package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
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

/** Datadog v2 operationId: CreateDowntime. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Schedule a Datadog downtime that mutes monitor notifications",
    description = """
        Creates a one-time downtime on one monitor (`monitorId`) or on every monitor with given monitor tags (`monitorTags`), \
        for the part of them that matches `scope`. Needs the API key and an application key with the `monitors_downtime` scope.

        A downtime needs an end: give `end` or `duration` to end it that long after `start`. \
        A downtime without an end would mute the monitors until someone cancels it, which a failed flow may never do. \
        Keep the returned `downtimeId` and cancel the downtime with `downtimes.Cancel` when the work is done."""
)
@Plugin(
    examples = {
        @Example(
            title = "Mute the production monitors of a service during a deployment",
            full = true,
            code = """
                id: datadog_mute_during_deploy
                namespace: company.observability

                tasks:
                  - id: mute
                    type: io.kestra.plugin.datadog.downtimes.Create
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    scope: "env:prod AND service:orders"
                    monitorTags:
                      - "service:orders"
                    duration: PT30M
                    message: "Deployment {{ execution.id }}"

                  - id: deploy
                    type: io.kestra.plugin.core.log.Log
                    message: Deploying the orders service

                finally:
                  - id: unmute
                    type: io.kestra.plugin.datadog.downtimes.Cancel
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    runIf: "{{ outputs.mute is defined }}"
                    downtimeId: "{{ outputs.mute.downtimeId }}"
                    ignoreNotFound: true
                """
        )
    }
)
public class Create extends AbstractDatadogTask implements RunnableTask<Create.Output> {
    @Schema(title = "Scope", description = "The monitor groups to mute, in the Datadog query syntax, such as `env:prod AND service:orders`. `*` mutes all the groups of the selected monitors.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> scope;

    @Schema(title = "Monitor id", description = "The id of the one monitor to mute. Use instead of `monitorTags`.")
    @PluginProperty(group = "main")
    private Property<Long> monitorId;

    @Schema(title = "Monitor tags", description = "Mutes every monitor that carries all these monitor tags. Use instead of `monitorId`.")
    @PluginProperty(group = "main")
    private Property<List<String>> monitorTags;

    @Schema(title = "Message", description = "A note shown with the downtime.")
    @PluginProperty(group = "processing")
    private Property<String> message;

    @Schema(title = "Start", description = "When the downtime starts, an ISO-8601 instant. It starts now when it is not set.")
    @PluginProperty(group = "processing")
    private Property<Instant> start;

    @Schema(title = "End", description = "When the downtime ends, an ISO-8601 instant. Use instead of `duration`.")
    @PluginProperty(group = "processing")
    private Property<Instant> end;

    @Schema(title = "Duration", description = "How long the downtime lasts from its start (from now when `start` is not set), such as `PT30M`. Use instead of `end`.")
    @PluginProperty(group = "processing")
    private Property<Duration> duration;

    @Schema(title = "Display time zone", description = "The time zone Datadog uses to show the downtime, such as `Europe/Paris`. `UTC` when it is not set.")
    @PluginProperty(group = "advanced")
    private Property<String> displayTimezone;

    @Schema(title = "Mute the first recovery notification", description = "Also mutes the first recovery notification of a monitor that recovers during the downtime.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> muteFirstRecoveryNotification;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rScope = runContext.render(this.scope).as(String.class).orElse("").trim();
        if (rScope.isEmpty()) {
            throw new IllegalArgumentException("The Datadog downtime `scope` must not be blank");
        }

        var rMonitorId = runContext.render(this.monitorId).as(Long.class).orElse(null);
        var rTags = runContext.render(this.monitorTags).asList(String.class).stream().filter(tag -> tag != null && !tag.isBlank()).toList();
        if ((rMonitorId == null) == rTags.isEmpty()) {
            throw new IllegalArgumentException("Give either `monitorId` or `monitorTags` to choose the monitors to mute, but not both and not neither");
        }

        var rStart = runContext.render(this.start).as(Instant.class).orElse(null);
        var rEnd = runContext.render(this.end).as(Instant.class).orElse(null);
        var rDuration = runContext.render(this.duration).as(Duration.class).orElse(null);
        if ((rEnd == null) == (rDuration == null)) {
            throw new IllegalArgumentException("Give either `end` or `duration` so that the downtime stops, but not both and not neither");
        }
        if (rDuration != null) {
            if (rDuration.isZero() || rDuration.isNegative()) {
                throw new IllegalArgumentException("The downtime `duration` must be positive but was " + rDuration);
            }
            rEnd = (rStart == null ? Instant.now() : rStart).plus(rDuration);
        }
        if (rStart != null && !rEnd.isAfter(rStart)) {
            throw new IllegalArgumentException("The end of the downtime (" + rEnd + ") must be after its start (" + rStart + ")");
        }

        var schedule = new LinkedHashMap<String, Object>();
        if (rStart != null) {
            schedule.put("start", rStart.toString());
        }
        schedule.put("end", rEnd.toString());

        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("scope", rScope);
        attributes.put("monitor_identifier", rMonitorId != null ? Map.of("monitor_id", rMonitorId) : Map.of("monitor_tags", rTags));
        attributes.put("schedule", schedule);
        runContext.render(this.message).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> attributes.put("message", value));
        runContext.render(this.displayTimezone).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> attributes.put("display_timezone", value));
        runContext.render(this.muteFirstRecoveryNotification).as(Boolean.class).ifPresent(value -> attributes.put("mute_first_recovery_notification", value));

        var body = Map.of("data", Map.of("type", "downtime", "attributes", attributes));
        Map<String, Object> answer;
        try (var client = client(runContext, true)) {
            answer = client.post("/api/v2/downtime", body).map();
        }

        String id = null;
        String status = null;
        if (answer.get("data") instanceof Map<?, ?> data) {
            id = data.get("id") == null ? null : String.valueOf(data.get("id"));
            if (data.get("attributes") instanceof Map<?, ?> returned && returned.get("status") != null) {
                status = String.valueOf(returned.get("status"));
            }
        }
        if (id == null) {
            throw new IllegalStateException("Datadog accepted the downtime but returned no id, so it could not be cancelled by this flow: check the downtimes in Datadog");
        }

        runContext.logger().info("Datadog downtime {} created for '{}' until {}", id, rScope, rEnd);
        return Output.builder().downtimeId(id).status(status).end(rEnd).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtime id", description = "The id of the downtime, to give to `downtimes.Cancel`.")
        private final String downtimeId;

        @Schema(title = "Status", description = "The status Datadog gives the downtime, such as `scheduled` or `active`.")
        private final String status;

        @Schema(title = "End", description = "When the downtime ends.")
        private final Instant end;
    }
}
