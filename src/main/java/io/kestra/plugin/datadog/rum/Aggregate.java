package io.kestra.plugin.datadog.rum;

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

/** Datadog v2 operationId: AggregateRUMEvents. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Aggregate Datadog RUM events", description = "Computes counts, measures and time buckets over RUM events. Needs RUM read permission. Follows aggregate cursors up to maxResults and fails on incomplete answers.")
@Plugin(examples = @Example(title = "Count browser errors over time", full = true, code = """
    id: datadog_rum_counts
    namespace: company.observability
    tasks:
      - id: counts
        type: io.kestra.plugin.datadog.rum.Aggregate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "@type:error"
        compute:
          - aggregation: COUNT
            computeType: TIMESERIES
            interval: 1m
    """))
public class Aggregate extends AbstractAggregate implements RunnableTask<AbstractAggregate.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return aggregate(context, "/api/v2/rum/analytics/aggregate", false);
    }
}
