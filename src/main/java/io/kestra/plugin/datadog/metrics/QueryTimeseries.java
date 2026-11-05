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
        store: true
    """))
public class QueryTimeseries extends AbstractFormulaTask implements RunnableTask<QueryTimeseries.Output> {
    @Schema(title = "Query interval", description = "Positive interval in milliseconds. Datadog chooses it when omitted.")
    private Property<Long> queryInterval;

    @Schema(title = "Store response", description = "Write the response attributes as one ION record and omit inline data. Defaults to false.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        var body = requestBody(context, null, context.render(queryInterval).as(Long.class).orElse(null));
        var attributes = FormulaQueries.attributes(client(context, true).post("/api/v2/query/timeseries", body));
        if (!(attributes.get("series") instanceof List<?> series) || !(attributes.get("times") instanceof List<?>) || !(attributes.get("values") instanceof List<?>)) {
            throw new IllegalStateException("Datadog timeseries response needs series, times and values arrays");
        }
        boolean stored = context.render(store).as(Boolean.class).orElse(false);
        return Output.builder().data(stored ? null : attributes).seriesCount(series.size())
            .uri(stored ? FormulaQueries.store(context, List.of(attributes)) : null).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Timeseries data", description = "Datadog series metadata, times and parallel values arrays. Timestamps are returned unchanged.")
        private final Map<String, Object> data;
        @Schema(title = "Series count")
        private final Integer seriesCount;
        @Schema(title = "Stored ION response")
        private final URI uri;
    }
}
