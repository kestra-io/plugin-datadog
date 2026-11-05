package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.deployments.Deadline;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Datadog operationIds: v1:TriggerCITests, v1:SearchTests, v1:GetSyntheticsCIBatch, v1:GetAPITestResult and v1:GetBrowserTestResult. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Run Datadog synthetic tests as a quality gate", description = "Starts a CI batch and waits within maxWait. Blocking failures fail the task by default. Skipped tests stay skipped and non_blocking failures are reported without failing the gate. Needs synthetics_read and synthetics_write. Each retry of this task starts a new batch.")
@Plugin(examples = @Example(title = "Gate a release on synthetic tests", full = true, code = """
    id: synthetic_quality_gate
    namespace: company.delivery
    tasks:
      - id: smoke
        type: io.kestra.plugin.datadog.synthetics.Run
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        testIds: [abc-def-ghi]
        maxWait: PT10M
        pollInterval: PT5S
        overrides:
          startUrl: https://staging.example.com
    """))
public class Run extends AbstractTriggerTask implements RunnableTask<Run.Output> {
    @Schema(title = "Maximum wait", description = "Total budget for selection, trigger, polling and failure details. Between PT0.001S and P1D.", defaultValue = "PT10M")
    private Property<Duration> maxWait;
    @Schema(title = "Poll interval", description = "Delay between reads. Sleep is capped at the remaining budget.", defaultValue = "PT5S")
    private Property<Duration> pollInterval;
    @Schema(title = "Fail on test failure", description = "Fail if a blocking test fails or the batch fails without an explained nonblocking failure.", defaultValue = "true")
    private Property<Boolean> failOnFailure;
    @Schema(title = "Fail on timeout", description = "Fail when the batch is unfinished at maxWait. False returns timedOut with the last known results.", defaultValue = "true")
    private Property<Boolean> failOnTimeout;
    @Schema(title = "Include secret-bearing failure messages", description = "Expose raw failure messages from result details. False returns the failure code and redacts the message because it can contain credentials or request content.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            Duration wait = context.render(maxWait).as(Duration.class).orElse(Duration.ofMinutes(10));
            Duration interval = context.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(5));
            Deadline.validate(wait, "maxWait");
            Deadline.validate(interval, "pollInterval");
            boolean fail = context.render(failOnFailure).as(Boolean.class).orElse(true);
            boolean timeoutFails = context.render(failOnTimeout).as(Boolean.class).orElse(true);
            boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
            String batchId = null;
            Status status = Status.IN_PROGRESS;
            List<TestResult> results = List.of();
            boolean finished = false;
            try (var deadline = new Deadline(wait)) {
                try {
                    var body = deadline.call(() -> triggerBody(context, client));
                    var triggered = deadline.call(() -> client.post("/api/v1/synthetics/tests/trigger/ci", body).map());
                    batchId = Synthetics.id((String) triggered.get("batch_id"), "batch_id returned by Datadog");
                    final String path = "/api/v1/synthetics/ci/batch/" + batchId;
                    while (!deadline.expired()) {
                        try {
                            var response = deadline.call(() -> client.get(path, Map.of()).map());
                            var data = Synthetics.object(response.get("data"), "batch data");
                            status = Status.from(data.get("status"));
                            results = parse(data);
                            // Both the batch and each reported result must be terminal. Empty success is not evidence.
                            finished = status != Status.IN_PROGRESS && (status == Status.SKIPPED || !results.isEmpty())
                                && results.stream().allMatch(result -> result.status != Status.IN_PROGRESS);
                            if (finished) {
                                results = details(client, deadline, results, reveal);
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
                    // Preserve the last state. A deadline is never turned into a passing batch.
                }
            }
            boolean timedOut = !finished;
            boolean failed = failed(status, results);
            var output = Output.builder().batchId(batchId).status(status).results(results).timedOut(timedOut)
                .passed(finished && !failed && status == Status.PASSED).build();
            if (timedOut && timeoutFails) {
                throw new IllegalStateException("Synthetic batch " + batchId + " did not finish within " + wait);
            }
            if (failed && fail) {
                String reasons = String.join("; ", results.stream().filter(TestResult::blocks)
                    .map(row -> row.publicId + " at " + row.location + ": " + row.failureCode).toList());
                throw new IllegalStateException("Synthetic batch " + batchId + " failed" + (reasons.isEmpty() ? "" : ": " + reasons));
            }
            return output;
        }
    }

    static boolean failed(Status batch, List<TestResult> results) {
        if (results.stream().anyMatch(TestResult::blocks)) {
            return true;
        }
        return batch == Status.FAILED && results.stream().noneMatch(row -> row.status == Status.FAILED);
    }

    static List<TestResult> parse(Map<String, Object> data) {
        return Synthetics.rows(data.getOrDefault("results", List.of()), "batch results").stream().map(row -> TestResult.builder()
            .publicId((String) row.get("test_public_id")).resultId((String) row.get("result_id"))
            .testType((String) row.get("test_type")).location((String) row.get("location"))
            .status(Status.from(row.get("status"))).executionRule(ExecutionRule.from(row.get("execution_rule")))
            .failureCode(Status.from(row.get("status")) == Status.FAILED ? "TEST_FAILED" : null).build()).toList();
    }

    private static List<TestResult> details(DatadogClient client, Deadline deadline, List<TestResult> results, boolean reveal) throws Exception {
        List<TestResult> enriched = new ArrayList<>();
        for (TestResult row : results) {
            if (row.status != Status.FAILED || row.resultId == null || row.publicId == null
                || !("api".equals(row.testType) || "browser".equals(row.testType))) {
                enriched.add(row);
                continue;
            }
            String path = "/api/v1/synthetics/tests/" + ("browser".equals(row.testType) ? "browser/" : "")
                + Synthetics.id(row.publicId, "test_public_id") + "/results/" + Synthetics.id(row.resultId, "result_id");
            try {
                var answer = deadline.call(() -> client.get(path, Map.of()).map());
                var result = Synthetics.object(answer.get("result"), "result");
                var failure = Synthetics.object(result.getOrDefault("failure", Map.of()), "failure");
                enriched.add(row.toBuilder().failureCode(String.valueOf(failure.getOrDefault("code", "TEST_FAILED")))
                    .failureMessage(failure.containsKey("message") ? (reveal ? String.valueOf(failure.get("message")) : "[REDACTED]") : null).build());
            } catch (DatadogClient.DatadogException | TimeoutException ignored) {
                // A diagnostic read cannot change the decision already supplied by the CI batch.
                enriched.add(row);
            }
        }
        return enriched;
    }

    public enum Status {
        PASSED, SKIPPED, FAILED, IN_PROGRESS;
        static Status from(Object value) {
            return switch (String.valueOf(value)) {
                case "passed" -> PASSED;
                case "skipped" -> SKIPPED;
                case "failed" -> FAILED;
                default -> IN_PROGRESS;
            };
        }
    }

    public enum ExecutionRule {
        BLOCKING, NON_BLOCKING, SKIPPED;
        static ExecutionRule from(Object value) {
            return switch (String.valueOf(value)) {
                case "non_blocking" -> NON_BLOCKING;
                case "skipped" -> SKIPPED;
                default -> BLOCKING;
            };
        }
    }

    @Builder(toBuilder = true)
    @Getter
    public static class TestResult {
        @Schema(title = "Test public ID")
        private final String publicId;
        @Schema(title = "Result ID")
        private final String resultId;
        @Schema(title = "Test kind")
        private final String testType;
        @Schema(title = "Location name")
        private final String location;
        @Schema(title = "Result status")
        private final Status status;
        @Schema(title = "CI execution rule")
        private final ExecutionRule executionRule;
        @Schema(title = "Failure code", description = "API failure code when available. TEST_FAILED when no details were available.")
        private final String failureCode;
        @Schema(title = "Failure message", description = "Redacted unless includeSecrets is true.")
        private final String failureMessage;

        boolean blocks() {
            return status == Status.FAILED && executionRule == ExecutionRule.BLOCKING;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "CI batch ID", description = "Null if the deadline was reached before a trigger response arrived.")
        private final String batchId;
        @Schema(title = "Last batch status")
        private final Status status;
        @Schema(title = "Test results")
        private final List<TestResult> results;
        @Schema(title = "Deadline reached before completion")
        private final Boolean timedOut;
        @Schema(title = "Batch passed", description = "False for skipped or unfinished batches.")
        private final Boolean passed;
    }
}
