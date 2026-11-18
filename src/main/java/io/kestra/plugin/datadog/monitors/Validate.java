package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog operationIds: ValidateMonitor and ValidateExistingMonitor. */
@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Schema(
    title = "Validate a Datadog monitor definition",
    description = """
        Asks Datadog to validate a complete definition without saving it.
        Set monitorId to validate an existing monitor. Needs monitors_read. Invalid definitions fail the task.
        """
)
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
public class Validate extends AbstractDefinitionTask implements RunnableTask<VoidOutput> {
    @Schema(title = "Existing monitor id", description = "Optional positive id for ValidateExistingMonitor.")
    @PluginProperty(group = "main")
    private Property<@Min(1) Long> monitorId;

    @Override
    public VoidOutput run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = context.render(monitorId).as(Long.class).orElse(null);
            var body = definition(context);
            client.post(rId == null ? "/api/v1/monitor/validate" : "/api/v1/monitor/%s/validate".formatted(rId), body);
            return null;
        }
    }
}
