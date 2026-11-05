package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

/** Datadog operationId: SearchMonitors. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog monitors with facets", description = "Searches the monitor search endpoint and returns facet counts from the first page. Reads pages up to maxResults. Needs monitors_read. Search rows are summaries; use Get for full definitions.")
@Plugin(examples = @Example(title = "Find alerting production monitors", full = true, code = """
    id: search_monitors
    namespace: company.observability
    tasks:
      - id: search
        type: io.kestra.plugin.datadog.monitors.Search
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "tag:env:prod status:alert"
        store: true
    """))
public class Search extends AbstractMonitorTask implements RunnableTask<Search.Output> {
    @Schema(title = "Monitor search query")
    private Property<String> query;
    @Schema(title = "Sort", description = "Datadog search sort expression, for example name,asc.")
    private Property<String> sort;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000.")
    private Property<Integer> maxResults;
    @Schema(title = "Store results", description = "Write monitor rows to an ION file instead of returning them inline.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        int limit = MonitorIO.limit(context.render(maxResults).as(Integer.class).orElse(null));
        boolean storage = context.render(store).as(Boolean.class).orElse(false);
        boolean include = includeSecrets(context);
        var params = DatadogClient.query();
        context.render(query).as(String.class).ifPresent(value -> params.put("query", value));
        context.render(sort).as(String.class).ifPresent(value -> params.put("sort", value));
        int pageSize = Math.min(100, limit);
        params.put("per_page", pageSize);
        var client = client(context, true);
        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> counts = Map.of();
        Map<String, Object> metadata = Map.of();
        for (int page = 0; rows.size() < limit; page++) {
            params.put("page", page);
            var answer = client.get("/api/v1/monitor/search", params).map();
            if (page == 0) {
                counts = answer.get("counts") instanceof Map<?, ?> map ? MonitorIO.redactMap(MonitorDefinitions.object(map), include) : Map.of();
                metadata = answer.get("metadata") instanceof Map<?, ?> map ? MonitorDefinitions.object(map) : Map.of();
            }
            if (!(answer.get("monitors") instanceof java.util.List<?> monitors)) {
                throw new IllegalStateException("Datadog search response must contain a monitors array");
            }
            for (Object row : monitors) {
                if (!(row instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("Datadog search returned a non-object monitor");
                }
                if (rows.size() < limit) {
                    rows.add(MonitorIO.redactMap(MonitorDefinitions.object(map), include));
                }
            }
            if (monitors.size() < pageSize || (metadata.get("page_count") instanceof Number pages && page + 1 >= pages.intValue())) {
                break;
            }
        }
        return Output.builder().monitors(storage ? null : rows).uri(storage ? MonitorIO.store(context, rows, false) : null)
            .counts(counts).metadata(metadata).size(rows.size()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Search results", description = "Omitted when store is true.")
        private final java.util.List<Map<String, Object>> monitors;
        @Schema(title = "Facet counts", description = "First page counts for muted, status, tag and type.")
        private final Map<String, Object> counts;
        @Schema(title = "Search metadata", description = "First page metadata including total_count and page_count.")
        private final Map<String, Object> metadata;
        @Schema(title = "Number of returned rows")
        private final Integer size;
        @Schema(title = "Result file URI")
        private final URI uri;
    }
}
