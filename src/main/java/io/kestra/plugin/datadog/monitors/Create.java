package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: CreateMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(
    title = "Create a Datadog monitor",
    description = """
        Creates one monitor from a definition. Needs an API key and an application key with monitors_write.
        Use Apply for idempotent reconciliation.
        """
)
@Plugin(examples = @Example(title = "Create a CPU monitor", full = true, code = """
    id: create_monitor
    namespace: company.observability
    tasks:
      - id: create
        type: io.kestra.plugin.datadog.monitors.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        definition:
          type: query alert
          name: CPU usage
          query: "avg(last_5m):avg:system.cpu.user{env:prod} > 80"
    """))
public class Create extends AbstractDefinitionTask implements RunnableTask<MonitorOutput> {
    @Schema(title = "Dry run", description = "False by default. Validates the definition with Datadog without creating a monitor. Needs monitors_read instead of monitors_write.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> dryRun;

    @Override
    public MonitorOutput run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var body = definition(context);
            if (context.render(dryRun).as(Boolean.class).orElse(false)) {
                client.post("/api/v1/monitor/validate", body);
                return MonitorOutput.builder().monitor(body).dryRun(true).build();
            }
            return MonitorOutput.of(client.post("/api/v1/monitor", body).map());
        }
    }
}
