package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ValidDatadogLimits
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Search the Datadog logs",
    description = """
        Returns the log events that match a Log Explorer query in a time window. \
        Needs the API key and an application key with the `logs_read_data` scope.

        The events are read page by page until `maxResults` are collected or Datadog has no more. \
        Datadog applies a rate limit of 300 requests per hour to this endpoint."""
)
@Plugin(
    examples = {
        @Example(
            title = "Fail the flow when a service logged errors in the last 15 minutes",
            full = true,
            code = """
                id: datadog_error_logs
                namespace: company.observability

                tasks:
                  - id: errors
                    type: io.kestra.plugin.datadog.logs.Search
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    query: "service:orders status:error"
                    from: now-15m
                    maxResults: 50

                  - id: gate
                    type: io.kestra.plugin.core.execution.Assert
                    errorMessage: "{{ outputs.errors.size }} error log(s) from orders"
                    conditions:
                      - "{{ outputs.errors.size == 0 }}"
                """
        )
    }
)
public class Search extends AbstractDatadogTask implements RunnableTask<Search.Output> {
    private static final int PAGE_SIZE = 1000;

    @Builder.Default
    @Schema(title = "Query", description = "A Log Explorer query, such as `service:orders status:error`. All the logs when it is not set.", defaultValue = "*")
    @PluginProperty(group = "main")
    private Property<String> query = Property.ofValue("*");

    @Builder.Default
    @Schema(title = "From", description = "The start of the window: a relative time such as `now-15m` or `now-1d`, an ISO-8601 date or a timestamp in milliseconds.", defaultValue = "now-15m")
    @PluginProperty(group = "processing")
    private Property<String> from = Property.ofValue("now-15m");

    @Builder.Default
    @Schema(title = "To", description = "The end of the window, in the same formats as `from`.", defaultValue = "now")
    @PluginProperty(group = "processing")
    private Property<String> to = Property.ofValue("now");

    @Schema(title = "Indexes", description = "The log indexes to search. All the indexes when it is not set.")
    @PluginProperty(group = "processing")
    private Property<List<String>> indexes;

    @Schema(title = "Sort order", description = "`ASCENDING` (oldest first) or `DESCENDING` (newest first). Datadog uses `DESCENDING` when it is not set.")
    @PluginProperty(group = "processing")
    private Property<Sort> sort;

    @Builder.Default
    @Schema(title = "Maximum number of results", description = "Stops after this many log events, between 1 and 10000.", defaultValue = "100")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults = Property.ofValue(100);

    @Builder.Default
    @Schema(
        title = "How to return the events",
        description = "`FETCH` returns all the events in the output, `FETCH_ONE` the first one only, `STORE` writes them to internal storage as an Ion file and returns its `uri`, `NONE` returns only `size`. " +
            "Use `STORE` for a large result: the output of a task is kept in the execution and verbose events can reach several MB.",
        defaultValue = "FETCH"
    )
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in log messages and attributes. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rLimit = runContext.render(this.maxResults).as(Integer.class).orElse(100);
        if (rLimit < 1 || rLimit > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxResults` must be between 1 and 10000 but was " + rLimit);
        }

        var filter = new LinkedHashMap<String, Object>();
        filter.put("query", runContext.render(this.query).as(String.class).filter(v -> !v.isBlank()).orElse("*"));
        filter.put("from", runContext.render(this.from).as(String.class).filter(v -> !v.isBlank()).orElse("now-15m"));
        filter.put("to", runContext.render(this.to).as(String.class).filter(v -> !v.isBlank()).orElse("now"));
        var rIndexes = runContext.render(this.indexes).asList(String.class);
        if (!rIndexes.isEmpty()) {
            filter.put("indexes", rIndexes);
        }
        var rSort = runContext.render(this.sort).as(Sort.class).orElse(null);

        var logs = new ArrayList<Map<String, Object>>();
        try (var client = client(runContext, true)) {
            String cursor = null;
            do {
                var page = new LinkedHashMap<String, Object>();
                page.put("limit", Math.min(PAGE_SIZE, rLimit - logs.size()));
                if (cursor != null) {
                    page.put("cursor", cursor);
                }
                var body = new LinkedHashMap<String, Object>();
                body.put("filter", filter);
                body.put("page", page);
                if (rSort != null) {
                    body.put("sort", rSort.api());
                }

                var answer = client.post("/api/v2/logs/events/search", body).map();
                if (answer.get("data") instanceof List<?> rows) {
                    for (var row : rows) {
                        if (row instanceof Map<?, ?> raw && logs.size() < rLimit) {
                            logs.add(flatten(raw));
                        }
                    }
                }
                cursor = null;
                if (logs.size() < rLimit && answer.get("meta") instanceof Map<?, ?> meta && meta.get("page") instanceof Map<?, ?> metaPage && metaPage.get("after") != null) {
                    cursor = String.valueOf(metaPage.get("after"));
                }
            } while (cursor != null);
        }

        runContext.logger().info("Datadog returned {} log event(s)", logs.size());
        var rIncludeSecrets = runContext.render(includeSecrets).as(Boolean.class).orElse(false);
        var safe = logs.stream().map(log -> MonitorIO.redactMap(log, rIncludeSecrets)).toList();
        var result = DatadogFetch.apply(runContext, runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH), safe);
        return Output.builder().logs(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).build();
    }

    /** Puts the id and the attributes of an event side by side: {@code id}, {@code timestamp}, {@code host}, {@code service}, {@code status}, {@code message}, {@code tags} and {@code attributes}. */
    private static Map<String, Object> flatten(Map<?, ?> event) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", event.get("id"));
        if (event.get("attributes") instanceof Map<?, ?> attributes) {
            attributes.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }

    public enum Sort {
        ASCENDING("timestamp"),
        DESCENDING("-timestamp");

        private final String api;

        Sort(String api) {
            this.api = api;
        }

        String api() {
            return api;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Log events", description = "With `FETCH`, the events: `id`, `timestamp`, `host`, `service`, `status`, `message`, `tags` and `attributes` (the parsed fields of the log).")
        private final List<Map<String, Object>> logs;

        @Schema(title = "First event", description = "With `FETCH_ONE`, the first event.")
        private final Map<String, Object> row;

        @Schema(title = "Internal storage URI", description = "With `STORE`, the Ion file that holds the events, one per line.")
        private final URI uri;

        @Schema(title = "Number of events", description = "The number of events found, whatever the `fetchType`.")
        private final Integer size;
    }
}
