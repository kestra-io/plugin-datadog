package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogFetch;
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

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var body = requestBody(context, context.render(aggregator).as(Aggregator.class).orElse(Aggregator.AVG), null);
            var columns = FormulaQueries.columns(FormulaQueries.attributes(client.post("/api/v2/query/scalar", body)));
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), columns);
            return Output.builder().columns(result.rows()).row(result.row()).columnCount(result.size()).uri(result.uri()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Columns", description = "Datadog group and numeric columns with parallel values arrays. Null cells remain null.")
        private final List<Map<String, Object>> columns;
        @Schema(title = "First column", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Column count")
        private final Integer columnCount;
        @Schema(title = "Stored ION columns")
        private final URI uri;
    }
}
