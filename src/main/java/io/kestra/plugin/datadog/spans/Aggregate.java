package io.kestra.plugin.datadog.spans;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.logs.AbstractAggregate;
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
@Schema(title = "Aggregate Datadog spans", description = "Computes counts, measures and time buckets over retained spans. Needs apm_read. The API has no aggregate pagination. Incomplete answers fail the task.")
@Plugin(examples = @Example(title = "Measure service latency", full = true, code = """
    id: datadog_span_latency
    namespace: company.observability
    tasks:
      - id: latency
        type: io.kestra.plugin.datadog.spans.Aggregate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "service:orders"
        compute:
          - aggregation: PC95
            metric: "@duration"
    """))
public class Aggregate extends AbstractAggregate implements RunnableTask<AbstractAggregate.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return aggregate(context, "/api/v2/spans/analytics/aggregate", true);
    }
}
