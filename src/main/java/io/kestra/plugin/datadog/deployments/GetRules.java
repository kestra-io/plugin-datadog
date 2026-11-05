package io.kestra.plugin.datadog.deployments;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

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
    private Property<String> gateId;

    @Override
    public Output run(RunContext context) throws Exception {
        String gate = Deployments.id(context.render(gateId).as(String.class).orElse(null), "gateId");
        var answer = client(context, true).get("/api/v2/deployment_gates/" + gate + "/rules", Map.of()).map();
        var data = Deployments.object(answer.get("data"), "gate rules data");
        var attributes = Deployments.object(data.get("attributes"), "gate rules attributes");
        if (!(attributes.get("rules") instanceof List<?> rows)) {
            throw new IllegalStateException("Datadog returned no rules array");
        }
        var rules = rows.stream().map(row -> Deployments.object(row, "rule")).toList();
        return Output.builder().rules(rules).size(rules.size()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Rules")
        private final List<Map<String, Object>> rules;
        @Schema(title = "Number of rules")
        private final Integer size;
    }
}
