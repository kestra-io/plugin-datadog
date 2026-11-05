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

import java.util.Map;

/** Datadog operationId: v2:CreateDeploymentRule with monitor query options. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a monitor-based Datadog gate rule", description = "Adds a monitor-query rule to an identified deployment gate. Missing monitor data and no matching groups fail the rule by default. Needs an API key and application key.")
@Plugin(examples = @Example(title = "Require healthy production monitors", full = true, code = """
    id: create_gate_rule
    namespace: company.delivery
    tasks:
      - id: create
        type: io.kestra.plugin.datadog.deployments.CreateMonitorRule
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        gateId: e140302e-0cba-40d2-978c-6780647f8f1c
        name: Healthy checkout monitors
        query: service:checkout env:production
        healthySeconds: 60
    """))
public class CreateMonitorRule extends AbstractDatadogTask implements RunnableTask<CreateMonitorRule.Output> {
    @NotNull
    @Schema(title = "Gate ID")
    private Property<String> gateId;
    @NotNull
    @Schema(title = "Rule name")
    private Property<String> name;
    @NotNull
    @Schema(title = "Monitor query")
    private Property<String> query;
    @Schema(title = "Required healthy seconds", description = "Nonnegative duration for monitors to stay OK.", defaultValue = "0")
    private Property<Long> healthySeconds;
    @Schema(title = "Warmup seconds", description = "Nonnegative delay after deployment starts.", defaultValue = "0")
    private Property<Long> warmupSeconds;
    @Schema(title = "Fail on no data", defaultValue = "true")
    private Property<Boolean> failOnNoData;
    @Schema(title = "Fail on no matching groups", description = "Defaults to true to prevent an empty query from passing the gate.", defaultValue = "true")
    private Property<Boolean> failOnNoGroupsFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            String gate = Deployments.id(context.render(gateId).as(String.class).orElse(null), "gateId");
            String label = Deployments.required(context.render(name).as(String.class).orElse(null), "name");
            String text = Deployments.required(context.render(query).as(String.class).orElse(null), "query");
            long duration = context.render(healthySeconds).as(Long.class).orElse(0L);
            long warmup = context.render(warmupSeconds).as(Long.class).orElse(0L);
            if (duration < 0 || warmup < 0) {
                throw new IllegalArgumentException("`healthySeconds` and `warmupSeconds` must be nonnegative");
            }
            var options = Map.of("query", text, "duration", duration, "warmup", warmup,
                "fail_on_no_data", context.render(failOnNoData).as(Boolean.class).orElse(true),
                "fail_on_no_groups_found", context.render(failOnNoGroupsFound).as(Boolean.class).orElse(true));
            var body = Deployments.body("deployment_rule", Map.of("name", label, "type", "monitor", "options", options, "dry_run", false));
            var answer = client.post("/api/v2/deployment_gates/" + gate + "/rules", body).map();
            var data = Deployments.object(answer.get("data"), "rule data");
            return Output.builder().ruleId(Deployments.required((String) data.get("id"), "rule id returned by Datadog")).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Created rule ID")
        private final String ruleId;
    }
}
