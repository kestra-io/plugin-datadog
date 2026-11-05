package io.kestra.plugin.datadog.deployments;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Datadog operationId: v2:GetDeploymentGateRules. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read Datadog deployment gate rules", description = "Reads the rules of one gate. Needs an API key and application key. This operation has no pagination.")
@Plugin(examples = @Example(title = "Inspect a deployment gate", full = true, code = """
    id: read_gate_rules
    namespace: company.delivery
    tasks:
      - id: rules
        type: io.kestra.plugin.datadog.deployments.GetRules
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        gateId: e140302e-0cba-40d2-978c-6780647f8f1c
    """))
public class GetRules extends AbstractDatadogTask implements RunnableTask<GetRules.Output> {
    @NotNull
    @Schema(title = "Gate ID")
    @PluginProperty(group = "main")
    private Property<String> gateId;

    @Schema(title = "Fetch type", description = "FETCH returns rules, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only size.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType;

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs without redaction. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rGate = Deployments.id(context.render(gateId).as(String.class).orElse(null), "gateId");
            var answer = client.get("/api/v2/deployment_gates/" + rGate + "/rules", Map.of()).map();
            var data = Deployments.object(answer.get("data"), "gate rules data");
            var attributes = Deployments.object(data.get("attributes"), "gate rules attributes");
            if (!(attributes.get("rules") instanceof List<?> rows)) {
                throw new IllegalStateException("Datadog returned no rules array");
            }
            var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
            var rules = rows.stream().map(row -> MonitorIO.redactMap(Deployments.object(row, "rule"), rIncludeSecrets)).toList();
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), rules);
            return Output.builder().rules(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Rules")
        private final List<Map<String, Object>> rules;
        @Schema(title = "First rule", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Stored ION rules", description = "Present with STORE.")
        private final URI uri;
        @Schema(title = "Number of rules")
        private final Integer size;
    }
}
