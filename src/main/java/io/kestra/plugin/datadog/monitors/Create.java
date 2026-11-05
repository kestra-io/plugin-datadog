package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: CreateMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog monitor", description = "Creates one monitor from a definition. Needs an API key and an application key with monitors_write. Use Apply for idempotent reconciliation.")
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
    @Override
    public MonitorOutput run(RunContext context) throws Exception {
        var body = definition(context, true);
        return MonitorOutput.of(client(context, true).post("/api/v1/monitor", body).map(), includeSecrets(context));
    }
}
