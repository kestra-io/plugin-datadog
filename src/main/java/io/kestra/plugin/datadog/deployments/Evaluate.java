package io.kestra.plugin.datadog.deployments;

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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Datadog operationIds: v2:TriggerDeploymentGatesEvaluation and v2:GetDeploymentGatesEvaluationResult. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Evaluate a Datadog deployment gate", description = "Evaluates the configured gate for a service and environment and waits for pass or fail. Failed rules fail the task by default. Needs an API key and application key. The API spec defines no named OAuth scope for these operations.")
@Plugin(examples = @Example(title = "Check the production deployment gate", full = true, code = """
    id: evaluate_deployment_gate
    namespace: company.delivery
    tasks:
      - id: gate
        type: io.kestra.plugin.datadog.deployments.Evaluate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        service: checkout
        env: production
        deploymentVersion: v1.2.3
        maxWait: PT10M
    """))
public class Evaluate extends AbstractDatadogTask implements RunnableTask<Evaluate.Output> {
    @NotNull
    @Schema(title = "Service")
    private Property<String> service;
    @NotNull
    @Schema(title = "Environment")
    private Property<String> env;
    @Schema(title = "Gate identifier", description = "Identifier of the configured gate. Datadog uses default when omitted.")
    private Property<String> identifier;
    @Schema(title = "Deployment version", description = "Required by Datadog for faulty deployment detection rules.")
    private Property<String> deploymentVersion;
    @Schema(title = "Primary tag", description = "Scopes APM faulty deployment detection, for example region:us-east-1.")
    private Property<String> primaryTag;
    @Schema(title = "Maximum wait", description = "Total trigger and polling budget, between PT0.001S and P1D.", defaultValue = "PT10M")
    private Property<Duration> maxWait;
    @Schema(title = "Poll interval", description = "Clamped to the remaining deadline.", defaultValue = "PT5S")
    private Property<Duration> pollInterval;
    @Schema(title = "Fail on gate failure", defaultValue = "true")
    private Property<Boolean> failOnFailure;
    @Schema(title = "Fail on timeout", defaultValue = "true")
    private Property<Boolean> failOnTimeout;

    @Override
    public Output run(RunContext context) throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("service", Deployments.required(context.render(service).as(String.class).orElse(null), "service"));
        attributes.put("env", Deployments.required(context.render(env).as(String.class).orElse(null), "env"));
        context.render(identifier).as(String.class).ifPresent(value -> attributes.put("identifier", value));
        context.render(deploymentVersion).as(String.class).ifPresent(value -> attributes.put("version", value));
        context.render(primaryTag).as(String.class).ifPresent(value -> attributes.put("primary_tag", value));
        Duration wait = context.render(maxWait).as(Duration.class).orElse(Duration.ofMinutes(10));
        Duration interval = context.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(5));
        Deadline.validate(wait, "maxWait");
        Deadline.validate(interval, "pollInterval");
        boolean fail = context.render(failOnFailure).as(Boolean.class).orElse(true);
        boolean failTimeout = context.render(failOnTimeout).as(Boolean.class).orElse(true);
        var client = client(context, true);
        String id = null;
        Status status = Status.IN_PROGRESS;
        Boolean dryRun = null;
        List<RuleResult> rules = List.of();
        try (var deadline = new Deadline(wait)) {
            try {
                var answer = deadline.call(() -> client.post("/api/v2/deployments/gates/evaluation",
                    Deployments.body("deployment_gates_evaluation_request", attributes)).map());
                var data = Deployments.object(answer.get("data"), "evaluation data");
                var accepted = Deployments.object(data.get("attributes"), "evaluation attributes");
                id = Deployments.required((String) accepted.get("evaluation_id"), "evaluation_id returned by Datadog");
                if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                    throw new IllegalStateException("Datadog evaluation_id is not a UUID");
                }
                final String path = "/api/v2/deployments/gates/evaluation/" + id;
                while (!deadline.expired()) {
                    try {
                        var response = deadline.call(() -> client.get(path, Map.of()).map());
                        var resultData = Deployments.object(response.get("data"), "evaluation result data");
                        var result = Deployments.object(resultData.get("attributes"), "evaluation result attributes");
                        status = Status.from(result.get("gate_status"));
                        dryRun = (Boolean) result.get("dry_run");
                        if (!(result.get("rules") instanceof List<?> rows)) {
                            throw new IllegalStateException("Datadog returned no rules array");
                        }
                        rules = rows.stream().map(row -> {
                            var rule = Deployments.object(row, "rule");
                            return RuleResult.builder().name((String) rule.get("name")).reason((String) rule.get("reason"))
                                .status(Status.from(rule.get("status"))).dryRun((Boolean) rule.get("dry_run")).build();
                        }).toList();
                        if (status != Status.IN_PROGRESS) {
                            break;
                        }
                    } catch (DatadogClient.DatadogException e) {
                        if (e.getStatus() != 429 && e.getStatus() < 500) {
                            throw e;
                        }
                    }
                    deadline.pause(interval);
                }
            } catch (TimeoutException ignored) {
                // Return the last known state when failOnTimeout is false.
            }
        }
        boolean timedOut = status == Status.IN_PROGRESS;
        if (timedOut && failTimeout) {
            throw new IllegalStateException("Deployment gate evaluation " + id + " did not finish within " + wait);
        }
        if (status == Status.FAIL && fail) {
            throw new IllegalStateException("Deployment gate evaluation " + id + " failed: " + String.join("; ", rules.stream()
                .filter(rule -> rule.status == Status.FAIL).map(rule -> rule.name + ": " + rule.reason).toList()));
        }
        return Output.builder().evaluationId(id).status(status).dryRun(dryRun).rules(rules).timedOut(timedOut).build();
    }

    public enum Status {
        IN_PROGRESS, PASS, FAIL;
        static Status from(Object value) {
            return switch (String.valueOf(value)) {
                case "pass" -> PASS;
                case "fail" -> FAIL;
                default -> IN_PROGRESS;
            };
        }
    }

    @Builder
    @Getter
    public static class RuleResult {
        @Schema(title = "Rule name")
        private final String name;
        @Schema(title = "Rule status")
        private final Status status;
        @Schema(title = "Rule reason")
        private final String reason;
        @Schema(title = "Rule dry run")
        private final Boolean dryRun;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Evaluation ID")
        private final String evaluationId;
        @Schema(title = "Gate status")
        private final Status status;
        @Schema(title = "Gate dry run", description = "A dry-run gate can report PASS despite failed rules. Check this field before enforcing a release policy.")
        private final Boolean dryRun;
        @Schema(title = "Rule results")
        private final List<RuleResult> rules;
        @Schema(title = "Deadline reached")
        private final Boolean timedOut;
    }
}
