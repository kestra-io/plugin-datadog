package io.kestra.plugin.datadog.ddsql;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Datadog operationIds: ExecuteDdsqlTabularQuery and FetchDdsqlTabularQuery. */
@ValidDatadogLimits
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Run a tabular DDSQL query", description = "Executes DDSQL and polls the returned query_id until completed. Requires timeseries_query and the application key owner's data-source permissions. Results are column-major and bounded by rowLimit. The API exposes no row pagination. A query still running after maxPolls fails the task.")
@Plugin(examples = @Example(title = "Count hosts by cloud provider", full = true, code = """
    id: datadog_ddsql
    namespace: company.observability
    tasks:
      - id: query
        type: io.kestra.plugin.datadog.ddsql.Query
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "SELECT cloud_provider, count(*) FROM dd.hosts GROUP BY cloud_provider"
        lookback: PT1H
        rowLimit: 1000
        fetchType: STORE
    """))
public class Query extends AbstractDatadogTask implements RunnableTask<Query.Output> {
    @NotNull
    @Schema(title = "DDSQL statement", description = "Query in Datadog's SQL dialect, such as SELECT cloud_provider, count(*) FROM dd.hosts GROUP BY cloud_provider.")
    private Property<String> query;
    @Schema(title = "Window start", description = "Inclusive instant. Defaults to lookback before to. Static tables still require a window in the API request.")
    private Property<Instant> from;
    @Schema(title = "Window end", description = "Exclusive instant. Defaults to now.")
    private Property<Instant> to;
    @Schema(title = "Lookback", description = "Positive duration used when from is absent. Defaults to PT1H.")
    private Property<Duration> lookback;
    @Schema(title = "Row limit", description = "Maximum returned rows, from 1 to 10000. Defaults to 1000. Check warnings for server truncation.")
    private Property<@Min(1) @Max(10000) Integer> rowLimit;
    @Schema(title = "Maximum fetch polls", description = "Number of fetch requests allowed, from 1 to 1000. Defaults to 30.")
    private Property<@Min(1) @Max(1000) Integer> maxPolls;
    @Schema(title = "Poll delay", description = "Delay between fetch requests, from one millisecond to one minute. Defaults to PT1S.")
    private Property<Duration> pollDelay;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);
    @Schema(title = "Include sensitive columns", description = "Return values for columns named as API keys, application keys, tokens, passwords or webhook URLs. Defaults to false. Detection uses column names; alias sensitive source fields with their original names.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        if (from != null && lookback != null) {
            throw new IllegalArgumentException("`lookback` cannot be used with `from`");
        }
        try (var client = client(context, true)) {
            String statement = context.render(query).as(String.class).orElse("").trim();
            int limit = context.render(rowLimit).as(Integer.class).orElse(1000);
            int polls = context.render(maxPolls).as(Integer.class).orElse(30);
            Duration delay = context.render(pollDelay).as(Duration.class).orElse(Duration.ofSeconds(1));
            Duration window = context.render(lookback).as(Duration.class).orElse(Duration.ofHours(1));
            Instant end = context.render(to).as(Instant.class).orElseGet(Instant::now);
            Instant start = context.render(from).as(Instant.class).orElseGet(() -> end.minus(window));
            boolean includeSensitive = context.render(includeSecrets).as(Boolean.class).orElse(false);
            if (statement.isBlank()) {
                throw new IllegalArgumentException("`query` must not be blank");
            }
            if (limit < 1 || limit > 10000 || polls < 1 || polls > 1000) {
                throw new IllegalArgumentException("`rowLimit` must be 1..10000 and `maxPolls` must be 1..1000");
            }
            if (delay.compareTo(Duration.ofMillis(1)) < 0 || delay.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("`pollDelay` must be between one millisecond and one minute");
            }
            if (window.isZero() || window.isNegative() || start.toEpochMilli() >= end.toEpochMilli()) {
                throw new IllegalArgumentException("`lookback` must be positive and `from` must precede `to` at millisecond precision");
            }
            var attributes = Map.of("query", statement, "row_limit", limit,
                "time", Map.of("from_timestamp", start.toEpochMilli(), "to_timestamp", end.toEpochMilli()));
            Map<String, Object> answer = client.post("/api/v2/ddsql/query/tabular", body("ddsql_query_request", attributes)).map();
            List<String> warnings = new ArrayList<>();
            for (int fetched = 0; ; fetched++) {
                if (!(answer.get("data") instanceof Map<?, ?> data) || !(data.get("attributes") instanceof Map<?, ?> result)) {
                    throw new IllegalStateException("Datadog DDSQL response has no data.attributes");
                }
                if (result.get("warnings") instanceof List<?> messages) {
                    for (Object message : messages) {
                        if (!(message instanceof String text)) {
                            throw new IllegalStateException("DDSQL warnings must be strings");
                        }
                        if (!warnings.contains(text)) {
                            warnings.add(text);
                        }
                    }
                }
                State state = State.fromApi(result.get("state"));
                if (state == State.COMPLETED) {
                    if (!(result.get("columns") instanceof List<?> columns)) {
                        throw new IllegalStateException("Completed DDSQL response has no columns");
                    }
                    List<Column> parsed = new ArrayList<>();
                    Integer rowCount = null;
                    for (Object column : columns) {
                        if (!(column instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)
                            || !(map.get("type") instanceof String dataType) || !(map.get("values") instanceof List<?> values)) {
                            throw new IllegalStateException("DDSQL columns need name, type and values");
                        }
                        if ((rowCount != null && rowCount != values.size()) || values.size() > limit) {
                            throw new IllegalStateException("DDSQL column lengths are inconsistent or exceed rowLimit");
                        }
                        rowCount = values.size();
                    List<?> safeValues = includeSensitive ? values : values.stream().map(value -> value == null ? null
                        : sensitive(name) ? "[REDACTED]" : io.kestra.plugin.datadog.monitors.MonitorIO.redact(value, false)).toList();
                        parsed.add(Column.builder().name(name).dataType(dataType).values(safeValues).build());
                    }
                    var fetchedResult = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), parsed);
                    return Output.builder().columns(fetchedResult.rows()).row(fetchedResult.row()).rowCount(rowCount == null ? 0L : rowCount.longValue())
                        .state(state).warnings(warnings).fetchCount(fetched).uri(fetchedResult.uri()).build();
                }
                if (!(result.get("query_id") instanceof String queryId) || queryId.isBlank()) {
                    throw new IllegalStateException("Running DDSQL response has no query_id");
                }
                if (fetched >= polls) {
                    throw new IllegalStateException("DDSQL query is still running after maxPolls fetch requests");
                }
                Thread.sleep(delay.toMillis());
                answer = client.post("/api/v2/ddsql/query/tabular/fetch", body("ddsql_query_fetch_request", Map.of("query_id", queryId))).map();
            }
        }
    }

    private static Map<String, Object> body(String type, Map<String, ?> attributes) {
        return Map.of("data", Map.of("type", type, "attributes", attributes));
    }

    private static boolean sensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey") || normalized.contains("appkey") || normalized.contains("applicationkey")
            || normalized.contains("password") || normalized.contains("passwd") || normalized.contains("secret")
            || normalized.contains("webhookurl") || normalized.contains("token");
    }

    public enum State {
        RUNNING, COMPLETED;

        static State fromApi(Object value) {
            if ("running".equals(value)) {
                return RUNNING;
            }
            if ("completed".equals(value)) {
                return COMPLETED;
            }
            throw new IllegalStateException("DDSQL state must be running or completed");
        }
    }

    @Builder
    @Getter
    public static class Column {
        @Schema(title = "Column name")
        private final String name;
        @Schema(title = "DDSQL data type", description = "Data type reported by Datadog, such as BIGINT or DECIMAL.")
        private final String dataType;
        @Schema(title = "Column values", description = "Parallel row values. Nulls and the API's integer or floating-point types are preserved.")
        private final List<?> values;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Query state")
        private final State state;
        @Schema(title = "Result columns")
        private final List<Column> columns;
        @Schema(title = "First column", description = "Present with FETCH_ONE. DDSQL results are column-major.")
        private final Column row;
        @Schema(title = "Result row count")
        private final Long rowCount;
        @Schema(title = "Fetch requests made")
        private final Integer fetchCount;
        @Schema(title = "Datadog warnings", description = "Includes warnings about row-limit truncation when supplied by Datadog.")
        private final List<String> warnings;
        @Schema(title = "Stored ION columns")
        private final URI uri;
    }
}
