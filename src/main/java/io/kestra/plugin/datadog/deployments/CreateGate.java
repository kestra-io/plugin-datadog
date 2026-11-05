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

import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: v2:CreateDeploymentGate. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog deployment gate", description = "Creates a gate for one service and environment. Add rules with CreateMonitorRule. Needs an API key and application key. This is a create operation, not an upsert.")
@Plugin(examples = @Example(title = "Create a pre-deploy gate", full = true, code = """
    id: create_deployment_gate
    namespace: company.delivery
    tasks:
      - id: create
        type: io.kestra.plugin.datadog.deployments.CreateGate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        service: checkout
        env: production
        identifier: pre-deploy
        evaluationDryRun: false
    """))
public class CreateGate extends AbstractDatadogTask implements RunnableTask<CreateGate.Output> {
    @NotNull
    @Schema(title = "Service")
    private Property<String> service;
    @NotNull
    @Schema(title = "Environment")
    private Property<String> env;
    @Schema(title = "Gate identifier", defaultValue = "default")
    private Property<String> identifier;
    @Schema(title = "Evaluation dry-run mode", description = "Persists Datadog's dry_run flag on the new gate. Its evaluations always pass while recording rule results. Both values create a gate with a POST request.", defaultValue = "false")
    private Property<Boolean> evaluationDryRun;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("service", Deployments.required(context.render(service).as(String.class).orElse(null), "service"));
            attributes.put("env", Deployments.required(context.render(env).as(String.class).orElse(null), "env"));
            attributes.put("identifier", Deployments.required(context.render(identifier).as(String.class).orElse("default"), "identifier"));
            attributes.put("dry_run", context.render(evaluationDryRun).as(Boolean.class).orElse(false));
            var answer = client.post("/api/v2/deployment_gates", Deployments.body("deployment_gate", attributes)).map();
            var data = Deployments.object(answer.get("data"), "gate data");
            return Output.builder().gateId(Deployments.required((String) data.get("id"), "gate id returned by Datadog")).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Created gate ID")
        private final String gateId;
    }
}
