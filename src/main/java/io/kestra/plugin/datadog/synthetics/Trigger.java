package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** Datadog operationIds: v1:TriggerCITests and v1:SearchTests. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Trigger Datadog synthetic CI tests", description = "Starts a CI batch without waiting. Needs synthetics_write and synthetics_read when selecting by search. Each task execution starts a new batch.")
@Plugin(examples = @Example(title = "Start production smoke tests", full = true, code = """
    id: trigger_synthetics
    namespace: company.delivery
    tasks:
      - id: trigger
        type: io.kestra.plugin.datadog.synthetics.Trigger
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        testIds: [abc-def-ghi]
    """))
public class Trigger extends AbstractTriggerTask implements RunnableTask<Trigger.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var body = triggerBody(context, client);
            var answer = client.post("/api/v1/synthetics/tests/trigger/ci", body).map();
            String batch = Synthetics.id((String) answer.get("batch_id"), "batch_id returned by Datadog");
            return Output.builder().batchId(batch)
                .results(Synthetics.rows(answer.getOrDefault("results", List.of()), "results")).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "CI batch ID")
        private final String batchId;
        @Schema(title = "Scheduled results", description = "Result IDs with public_id, location and device from the CI trigger response.")
        private final List<Map<String, Object>> results;
    }
}
