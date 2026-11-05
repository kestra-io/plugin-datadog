package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Datadog operationId: v1:QueryMetrics. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query timeseries metrics from Datadog",
    description = """
        Runs a Datadog metrics query over a time window and returns the matching series with their points. \
        Needs the API key and an application key with the `timeseries_query` scope.

        The window is `from` to `to` or the last `lookback` when `from` is not set. Datadog returns at most about 300 points per series \
        and picks the rollup itself, so a long window returns coarser points: add `.rollup(avg, 300)` to the query to choose it."""
)
@Plugin(
    examples = {
        @Example(
            title = "Fail the flow when the error rate of a service is too high",
            full = true,
            code = """
                id: datadog_error_rate_gate
                namespace: company.observability

                tasks:
                  - id: error_rate
                    type: io.kestra.plugin.datadog.metrics.Query
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    query: "avg:trace.http.request.errors{service:orders}.as_rate()"
                    lookback: PT15M

                  - id: check
                    type: io.kestra.plugin.core.execution.Assert
                    errorMessage: "No data returned for the orders error rate"
                    conditions:
                      - "{{ outputs.error_rate.pointCount > 0 }}"
                """
        )
    }
)
public class Query extends AbstractDatadogTask implements RunnableTask<Query.Output> {
    @Schema(title = "Metrics query", description = "A Datadog metrics query, such as `avg:system.cpu.user{env:prod} by {host}`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(title = "Start of the window", description = "An ISO-8601 instant. When not set, the window starts `lookback` before `to`.")
    @PluginProperty(group = "processing")
    private Property<Instant> from;

    @Schema(title = "End of the window", description = "An ISO-8601 instant. The time the task runs when it is not set.")
    @PluginProperty(group = "processing")
    private Property<Instant> to;

    @Schema(title = "Look-back", description = "The length of the window when `from` is not set. One hour when it is not set.", defaultValue = "PT1H")
    @PluginProperty(group = "processing")
    private Property<Duration> lookback;

    @Schema(title = "Fetch type", description = "FETCH returns series, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only counts.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType;

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (from != null && lookback != null) {
            throw new IllegalArgumentException("`lookback` cannot be used with `from`");
        }
        var rQuery = runContext.render(this.query).as(String.class).orElse("").trim();
        if (rQuery.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `query` must not be blank");
        }

        var rEnd = runContext.render(this.to).as(Instant.class).orElseGet(Instant::now);
        var rWindow = runContext.render(this.lookback).as(Duration.class).orElse(Duration.ofHours(1));
        var rStart = runContext.render(this.from).as(Instant.class).orElseGet(() -> rEnd.minus(rWindow));
        if (!rStart.isBefore(rEnd)) {
            throw new IllegalArgumentException("The start of the window (" + rStart + ") must be before its end (" + rEnd + ")");
        }

        var params = DatadogClient.query();
        params.put("from", rStart.getEpochSecond());
        params.put("to", rEnd.getEpochSecond());
        params.put("query", rQuery);

        Map<String, Object> answer;
        try (var client = client(runContext, true)) {
            answer = client.get("/api/v1/query", params).map();
        }

        // a query Datadog cannot run is answered with a 200 and `status: error`
        var status = answer.get("status") == null ? null : String.valueOf(answer.get("status"));
        if ("error".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Datadog could not run the query '" + rQuery + "': " + answer.getOrDefault("error", answer.get("message")));
        }

        var series = new ArrayList<Map<String, Object>>();
        var pointCount = 0;
        if (answer.get("series") instanceof List<?> list) {
            for (var entry : list) {
                if (entry instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    var item = (Map<String, Object>) raw;
                    series.add(item);
                    if (item.get("pointlist") instanceof List<?> points) {
                        pointCount += points.size();
                    }
                }
            }
        }

        runContext.logger().info("Datadog returned {} series and {} points for '{}'", series.size(), pointCount, rQuery);
        var result = DatadogFetch.apply(runContext, runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), series);
        return Output.builder().status(status).series(result.rows()).row(result.row()).uri(result.uri()).seriesCount(result.size()).pointCount(pointCount).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Status", description = "The status Datadog answers, `ok` when the query ran.")
        private final String status;

        @Schema(title = "Series", description = "The series returned. Each has `metric`, `scope`, `expression`, `unit` and `pointlist`, a list of `[timestamp in milliseconds, value]` pairs.")
        private final List<Map<String, Object>> series;

        @Schema(title = "First series", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;

        @Schema(title = "Stored ION series", description = "Present with STORE.")
        private final URI uri;

        @Schema(title = "Number of series")
        private final Integer seriesCount;

        @Schema(title = "Number of points", description = "The points of all the series. 0 means the query matched no data in the window.")
        private final Integer pointCount;
    }
}
