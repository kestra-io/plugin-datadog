package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: v2:UpdateDowntime. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Update one Datadog downtime", description = "Changes the supplied attributes of one downtime through the v2 PATCH endpoint. Needs monitors_downtime. Schedule changes require a finite end. Use schedule fields only for a one-time downtime; recurring schedule edits are available through api.Call.")
@Plugin(examples = @Example(title = "Change a downtime note", full = true, code = """
    id: update_downtime
    namespace: company.observability
    tasks:
      - id: update
        type: io.kestra.plugin.datadog.downtimes.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        downtimeId: 00000000-0000-1234-0000-000000000000
        message: Maintenance in progress
    """))
public class Update extends AbstractDatadogTask implements RunnableTask<Update.Output> {
    @NotNull
    @Schema(title = "Downtime id")
    @PluginProperty(group = "main")
    private Property<String> downtimeId;
    @Schema(title = "Message", description = "Replaces the downtime note. An empty string clears it.")
    @PluginProperty(group = "main")
    private Property<String> message;
    @Schema(title = "Scope", description = "Replaces the monitor group scope for this downtime.")
    @PluginProperty(group = "main")
    private Property<String> scope;
    @Schema(title = "Start", description = "ISO-8601 instant for a one-time schedule. Requires end.")
    @PluginProperty(group = "main")
    private Property<Instant> start;
    @Schema(title = "End", description = "Finite ISO-8601 end of a one-time schedule.")
    @PluginProperty(group = "main")
    private Property<Instant> end;
    @Schema(title = "Display time zone")
    @PluginProperty(group = "main")
    private Property<String> displayTimezone;
    @Schema(title = "Mute the first recovery notification")
    @PluginProperty(group = "main")
    private Property<Boolean> muteFirstRecoveryNotification;
    @Schema(title = "Ignore a missing downtime", description = "False by default. Only HTTP 404 is ignored.")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;
    @Schema(title = "Include sensitive output values", description = "False by default. Filters credential-like keys and HTTP URLs.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = context.render(downtimeId).as(String.class).orElse(null);
            var path = DowntimeSupport.path(rId);
            var attributes = new LinkedHashMap<String, Object>();
            context.render(message).as(String.class).ifPresent(value -> attributes.put("message", value));
            var rScope = context.render(scope).as(String.class).orElse(null);
            if (rScope != null) {
                if (rScope.isBlank()) {
                    throw new IllegalArgumentException("scope must not be blank");
                }
                attributes.put("scope", rScope);
            }
            var rStart = context.render(start).as(Instant.class).orElse(null);
            var rEnd = context.render(end).as(Instant.class).orElse(null);
            if (rStart != null && rEnd == null) {
                throw new IllegalArgumentException("A schedule update with start requires end");
            }
            if (rEnd != null) {
                if (rStart != null && !rEnd.isAfter(rStart)) {
                    throw new IllegalArgumentException("end must be after start");
                }
                var schedule = new LinkedHashMap<String, Object>();
                schedule.put("end", rEnd.toString());
                if (rStart != null) {
                    schedule.put("start", rStart.toString());
                }
                attributes.put("schedule", schedule);
            }
            var rTimezone = context.render(displayTimezone).as(String.class).orElse(null);
            if (rTimezone != null) {
                ZoneId.of(rTimezone);
                attributes.put("display_timezone", rTimezone);
            }
            context.render(muteFirstRecoveryNotification).as(Boolean.class).ifPresent(value -> attributes.put("mute_first_recovery_notification", value));
            if (attributes.isEmpty()) {
                throw new IllegalArgumentException("Supply at least one downtime attribute to update");
            }
            var rIgnore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            var rInclude = context.render(includeSecrets).as(Boolean.class).orElse(false);
            try {
                var response = client.patch(path, Map.of("data", Map.of("id", rId, "type", "downtime", "attributes", attributes))).map();
                if (!(response.get("data") instanceof Map<?, ?>)) {
                    throw new IllegalStateException("Datadog downtime response must contain a data object");
                }
                return Output.builder().downtimeId(rId).updated(true).downtime(MonitorIO.redactMap(response, rInclude)).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() == 404 && rIgnore) {
                    return Output.builder().downtimeId(rId).updated(false).build();
                }
                throw e;
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtime id")
        private final String downtimeId;
        @Schema(title = "Updated", description = "False when the downtime was absent and ignoreNotFound was true.")
        private final Boolean updated;
        @Schema(title = "Downtime response")
        private final Map<String, Object> downtime;
    }
}
