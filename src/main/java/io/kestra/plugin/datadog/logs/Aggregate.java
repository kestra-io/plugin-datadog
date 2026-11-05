package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** Datadog v2 operationId: AggregateLogs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Aggregate Datadog logs", description = "Computes counts, measures and time buckets grouped by facets. Needs logs_read_data. Follows aggregate cursors up to maxResults and fails on incomplete answers.")
@Plugin(examples = @Example(title = "Count errors by service", full = true, code = """
    id: datadog_log_counts
    namespace: company.observability
    tasks:
      - id: counts
        type: io.kestra.plugin.datadog.logs.Aggregate
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "status:error"
        compute:
          - aggregation: COUNT
        groupBy:
          - facet: service
            limit: 10
    """))
public class Aggregate extends AbstractAggregate implements RunnableTask<AbstractAggregate.Output> {
    @Schema(title = "Indexes", description = "Log indexes to aggregate. All indexes when omitted.")
    @PluginProperty(group = "processing")
    private Property<List<String>> indexes;

    @Override
    protected Map<String, Object> filter(RunContext context) throws Exception {
        var filter = super.filter(context);
        var rValues = context.render(indexes).asList(String.class);
        if (!rValues.isEmpty()) {
            filter.put("indexes", rValues);
        }
        return filter;
    }

    @Override
    public Output run(RunContext context) throws Exception {
        return aggregate(context, "/api/v2/logs/analytics/aggregate", false);
    }
}
