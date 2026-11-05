package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: GetMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Get one Datadog monitor", description = "Reads a monitor definition and state by id. Needs monitors_read. A missing monitor fails the task.")
@Plugin(examples = @Example(title = "Read a monitor", full = true, code = """
    id: get_monitor
    namespace: company.observability
    tasks:
      - id: get
        type: io.kestra.plugin.datadog.monitors.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        monitorId: 123
        withDowntimes: true
    """))
public class Get extends AbstractMonitorTask implements RunnableTask<MonitorOutput> {
    @NotNull
    @Schema(title = "Monitor id")
    private Property<Long> monitorId;
    @Schema(title = "Include matching downtimes")
    private Property<Boolean> withDowntimes;
    @Schema(title = "Include monitor assets")
    private Property<Boolean> withAssets;

    @Override
    public MonitorOutput run(RunContext context) throws Exception {
        long id = MonitorIO.positiveId(context.render(monitorId).as(Long.class).orElse(null));
        var params = DatadogClient.query();
        context.render(withDowntimes).as(Boolean.class).ifPresent(value -> params.put("with_downtimes", value));
        context.render(withAssets).as(Boolean.class).ifPresent(value -> params.put("with_assets", value));
        return MonitorOutput.of(client(context, true).get("/api/v1/monitor/" + id, params).map(), includeSecrets(context));
    }
}
