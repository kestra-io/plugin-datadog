package io.kestra.plugin.datadog.spans;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.logs.AbstractCount;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog v2 operationId: AggregateSpans. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Count Datadog spans against a threshold", description = "Counts matching retained spans with the aggregate API. Needs apm_read. Returns count and breached or fails on breach when requested. Incomplete counts fail the task.")
@Plugin(examples = @Example(title = "Check error spans", full = true, code = """
    id: datadog_span_gate
    namespace: company.observability
    tasks:
      - id: gate
        type: io.kestra.plugin.datadog.spans.Count
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders status:error"
        threshold: 10
    """))
public class Count extends AbstractCount implements RunnableTask<AbstractCount.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return count(context, true);
    }
}
