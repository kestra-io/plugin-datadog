package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: DeleteMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(title = "Delete one Datadog monitor", description = "Permanently deletes the monitor identified by monitorId. Needs monitors_write. Does not force deletion of referenced monitors. ignoreNotFound treats only HTTP 404 as a no-op.")
@Plugin(examples = @Example(title = "Remove a retired monitor", full = true, code = """
    id: delete_monitor
    namespace: company.observability
    tasks:
      - id: delete
        type: io.kestra.plugin.datadog.monitors.Delete
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        monitorId: 123
        ignoreNotFound: true
    """))
public class Delete extends AbstractDatadogTask implements RunnableTask<Delete.Output> {
    @NotNull
    @Schema(title = "Monitor id")
    @PluginProperty(group = "main")
    private Property<@Min(1) Long> monitorId;
    @Schema(title = "Ignore a missing monitor", description = "False by default.")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;
    @Schema(title = "Dry run", description = "False by default. Reads the monitor and checks its dependencies without deleting it. Referenced monitors fail the check. Needs monitors_read instead of monitors_write.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> dryRun;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = context.render(monitorId).as(Long.class).orElseThrow(() -> new IllegalArgumentException("monitorId is required"));
            var rIgnore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            var rDryRun = context.render(dryRun).as(Boolean.class).orElse(false);
            try {
                if (rDryRun) {
                    client.get("/api/v1/monitor/" + rId, Map.of());
                    client.get("/api/v1/monitor/can_delete", Map.of("monitor_ids", rId));
                    return Output.builder().monitorId(rId).deleted(false).dryRun(true).build();
                }
                client.delete("/api/v1/monitor/" + rId);
                return Output.builder().monitorId(rId).deleted(true).dryRun(false).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() == 404 && rIgnore) {
                    return Output.builder().monitorId(rId).deleted(false).dryRun(rDryRun).build();
                }
                throw e;
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitor id")
        private final Long monitorId;
        @Schema(title = "Deleted", description = "False during a dry run or when the monitor was absent and ignoreNotFound was true.")
        private final Boolean deleted;
        @Schema(title = "Dry run")
        private final Boolean dryRun;
    }
}
