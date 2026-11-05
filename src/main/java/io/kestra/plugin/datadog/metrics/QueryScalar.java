package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Datadog operationId: QueryScalarData. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Query scalar metric formulas", description = "Runs named metric or cloud cost queries and formulas through the v2 scalar API. Needs an API key and an application key with timeseries_query. Null values are preserved.")
@Plugin(examples = @Example(title = "Compute a request error percentage", full = true, code = """
    id: datadog_scalar
    namespace: company.observability
    tasks:
      - id: query
        type: io.kestra.plugin.datadog.metrics.QueryScalar
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        queries:
          a: "sum:requests.errors{env:prod}.as_count()"
          b: "sum:requests.total{env:prod}.as_count()"
        formulas: ["a / b * 100"]
        aggregator: SUM
        lookback: PT15M
    """))
public class QueryScalar extends AbstractFormulaTask implements RunnableTask<QueryScalar.Output> {
    @Schema(title = "Time aggregation", description = "Aggregation applied to each metric query. Defaults to AVG.")
    private Property<Aggregator> aggregator;

    @Schema(title = "Store columns", description = "Write one column per ION record to internal storage and omit inline columns. Defaults to false.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        var body = requestBody(context, context.render(aggregator).as(Aggregator.class).orElse(Aggregator.AVG), null);
        var columns = FormulaQueries.columns(FormulaQueries.attributes(client(context, true).post("/api/v2/query/scalar", body)));
        boolean stored = context.render(store).as(Boolean.class).orElse(false);
        return Output.builder().columns(stored ? null : columns).columnCount(columns.size())
            .uri(stored ? FormulaQueries.store(context, columns) : null).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Columns", description = "Datadog group and numeric columns with parallel values arrays. Null cells remain null.")
        private final List<Map<String, Object>> columns;
        @Schema(title = "Column count")
        private final Integer columnCount;
        @Schema(title = "Stored ION columns")
        private final URI uri;
    }
}
