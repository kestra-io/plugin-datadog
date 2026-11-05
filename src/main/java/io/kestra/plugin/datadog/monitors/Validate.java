package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Datadog operationIds: ValidateMonitor and ValidateExistingMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Validate a Datadog monitor definition", description = "Asks Datadog to validate a complete definition without saving it. Set monitorId to validate an existing monitor. Needs monitors_read. Invalid definitions fail the task.")
@Plugin(examples = @Example(title = "Validate before deployment", full = true, code = """
    id: validate_monitor
    namespace: company.observability
    tasks:
      - id: validate
        type: io.kestra.plugin.datadog.monitors.Validate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        definition:
          type: query alert
          query: "avg(last_5m):avg:system.cpu.user{env:prod} > 80"
    """))
public class Validate extends AbstractDefinitionTask implements RunnableTask<Validate.Output> {
    @Schema(title = "Existing monitor id", description = "Optional positive id for ValidateExistingMonitor.")
    private Property<Long> monitorId;

    @Override
    public Output run(RunContext context) throws Exception {
        Long id = context.render(monitorId).as(Long.class).orElse(null);
        if (id != null) {
            MonitorIO.positiveId(id);
        }
        var body = definition(context, true);
        client(context, true).post(id == null ? "/api/v1/monitor/validate" : "/api/v1/monitor/" + id + "/validate", body);
        return Output.builder().valid(true).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Definition accepted by Datadog")
        private final Boolean valid;
    }
}
