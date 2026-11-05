package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: GetSLO. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog SLO", description = "Reads one SLO definition, including thresholds, tags and its metric or monitor configuration. Needs slos_read.")
@Plugin(examples = @Example(title = "Read an SLO definition", full = true, code = """
    id: datadog_slo_get
    namespace: company.observability
    tasks:
      - id: slo
        type: io.kestra.plugin.datadog.slos.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
    """))
public class Get extends AbstractDatadogTask implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(title = "SLO identifier")
    private Property<String> sloId;
    @Schema(title = "Include configured alert identifiers")
    private Property<Boolean> withConfiguredAlertIds;

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in the SLO definition. False by default.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String path = Slos.path(context.render(sloId).as(String.class).orElse(null));
            Map<String, Object> query = DatadogClient.query();
            context.render(withConfiguredAlertIds).as(Boolean.class).ifPresent(value -> query.put("with_configured_alert_ids", value));
            var response = client.get(path, query).map();
            Slos.noErrors(response);
            return Output.builder().slo(io.kestra.plugin.datadog.monitors.MonitorIO.redactMap(Slos.object(response.get("data"), "data"), context.render(includeSecrets).as(Boolean.class).orElse(false))).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO definition")
        private final Map<String, Object> slo;
    }
}
