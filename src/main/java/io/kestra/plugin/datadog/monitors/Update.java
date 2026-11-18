package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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

import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(
    title = "Update one Datadog monitor",
    description = """
        Updates the supplied definition fields of one monitor by id. Needs monitors_write.
        A missing monitor fails unless ignoreNotFound is true. Nested options follow Datadog update semantics.
        """
)
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
    @PluginProperty(group = "main")
    private Property<@Min(1) Long> monitorId;

    @Schema(title = "Ignore a missing monitor", description = "False by default. Only HTTP 404 is treated as a no-op.")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;
    @Schema(title = "Dry run", description = "False by default. Reads the existing monitor and validates its definition with the supplied updates without saving it. Also needs monitors_read.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> dryRun;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = context.render(monitorId).as(Long.class).orElseThrow(() -> new IllegalArgumentException("monitorId is required"));
            var body = definition(context);
            var rIgnore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            var rDryRun = context.render(dryRun).as(Boolean.class).orElse(false);
            try {
                if (rDryRun) {
                    var proposed = new LinkedHashMap<>(MonitorDefinitions.writable(client.get("/api/v1/monitor/%s".formatted(rId), Map.of("with_assets", true)).map()));
                    proposed.putAll(body);
                    client.post("/api/v1/monitor/%s/validate".formatted(rId), proposed);
                    return Output.builder().monitorId(rId).monitor(body).updated(false).dryRun(true).build();
                }
                var monitor = MonitorOutput.of(client.put("/api/v1/monitor/%s".formatted(rId), body).map());
                return Output.builder().monitorId(monitor.getMonitorId()).monitor(monitor.getMonitor()).updated(true).dryRun(false).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() == 404 && rIgnore) {
                    return Output.builder().monitorId(rId).updated(false).dryRun(rDryRun).build();
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
        @Schema(title = "Updated", description = "False during a dry run or when the monitor was absent and ignoreNotFound was true.")
        private final Boolean updated;
        @Schema(title = "Monitor response")
        private final Map<String, Object> monitor;
        @Schema(title = "Dry run")
        private final Boolean dryRun;
    }
}
