package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: UpdateMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Update one Datadog monitor", description = "Updates the supplied definition fields of one monitor by id. Needs monitors_write. A missing monitor fails unless ignoreNotFound is true. Nested options follow Datadog update semantics.")
@Plugin(examples = @Example(title = "Change a monitor message", full = true, code = """
    id: update_monitor
    namespace: company.observability
    tasks:
      - id: update
        type: io.kestra.plugin.datadog.monitors.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        monitorId: 123
        definition:
          message: "Contact @team-platform"
    """))
public class Update extends AbstractDefinitionTask implements RunnableTask<Update.Output> {
    @NotNull
    @Schema(title = "Monitor id", description = "The positive id of the monitor to update.")
    private Property<Long> monitorId;

    @Schema(title = "Ignore a missing monitor", description = "False by default. Only HTTP 404 is treated as a no-op.")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        long id = MonitorIO.positiveId(context.render(monitorId).as(Long.class).orElse(null));
        var body = definition(context, false);
        boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
        boolean include = includeSecrets(context);
        try {
            var monitor = MonitorOutput.of(client(context, true).put("/api/v1/monitor/" + id, body).map(), include);
            return Output.builder().monitorId(monitor.getMonitorId()).monitor(monitor.getMonitor()).updated(true).build();
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() == 404 && ignore) {
                return Output.builder().monitorId(id).updated(false).build();
            }
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitor id")
        private final Long monitorId;
        @Schema(title = "Updated", description = "False when the monitor was absent and ignoreNotFound was true.")
        private final Boolean updated;
        @Schema(title = "Monitor response")
        private final Map<String, Object> monitor;
    }
}
