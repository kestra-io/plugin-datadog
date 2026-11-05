package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
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

/** Datadog operationId: QueryTimeseriesData. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Query metric timeseries formulas", description = "Runs named metric or cloud cost queries with formulas. Needs an API key and an application key with timeseries_query. Datadog can increase the requested interval to limit the point count.")
@Plugin(examples = @Example(title = "Read CPU series with a formula", full = true, code = """
    id: datadog_timeseries
    namespace: company.observability
    tasks:
      - id: query
        type: io.kestra.plugin.datadog.metrics.QueryTimeseries
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        queries:
          a: "avg:system.cpu.user{env:prod}"
          b: "avg:system.cpu.system{env:prod}"
        formulas: ["a + b"]
        queryInterval: 60000
        lookback: PT1H
        fetchType: STORE
    """))
public class QueryTimeseries extends AbstractFormulaTask implements RunnableTask<QueryTimeseries.Output> {
    @Schema(title = "Query interval", description = "Positive interval in milliseconds. Datadog chooses it when omitted.")
    @PluginProperty(group = "processing")
    private Property<Long> queryInterval;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rBody = requestBody(context, null, context.render(queryInterval).as(Long.class).orElse(null));
            var attributes = FormulaQueries.attributes(client.post("/api/v2/query/timeseries", rBody));
            if (!(attributes.get("series") instanceof List<?> series) || !(attributes.get("times") instanceof List<?>) || !(attributes.get("values") instanceof List<?>)) {
                throw new IllegalStateException("Datadog timeseries response needs series, times and values arrays");
            }
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), List.of(attributes));
            return Output.builder().data(result.rows() == null ? null : result.rows().getFirst()).row(result.row()).seriesCount(series.size())
                .uri(result.uri()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Timeseries data", description = "Datadog series metadata, times and parallel values arrays. Timestamps are returned unchanged.")
        private final Map<String, Object> data;
        @Schema(title = "First response", description = "Present with FETCH_ONE. Contains series metadata, times and values together.")
        private final Map<String, Object> row;
        @Schema(title = "Series count")
        private final Integer seriesCount;
        @Schema(title = "Stored ION response")
        private final URI uri;
    }
}
