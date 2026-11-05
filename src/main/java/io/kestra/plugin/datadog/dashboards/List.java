package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

/** Datadog operationId: ListDashboards. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog dashboards", description = "Pages through dashboard summaries with count and start. Needs dashboards_read. Use Get for widgets or store for an ION file.")
@Plugin(examples = @Example(title = "Store dashboard inventory", full = true, code = """
    id: datadog_dashboard_list
    namespace: company.observability
    tasks:
      - id: dashboards
        type: io.kestra.plugin.datadog.dashboards.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        store: true
    """))
public class List extends AbstractDashboardTask implements RunnableTask<List.Output> {
    @Schema(title = "Shared dashboards filter")
    private Property<Boolean> shared;
    @Schema(title = "Deleted dashboards filter")
    private Property<Boolean> deleted;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000. Reads one extra row to detect truncation.")
    private Property<Integer> maxResults;
    @Schema(title = "Store results", description = "Return an internal-storage ION URI instead of the dashboards array. False by default.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        int limit = Dashboards.limit(context.render(maxResults).as(Integer.class).orElse(1000));
        Map<String, Object> query = DatadogClient.query();
        context.render(shared).as(Boolean.class).ifPresent(value -> query.put("filter[shared]", value));
        context.render(deleted).as(Boolean.class).ifPresent(value -> query.put("filter[deleted]", value));
        var rows = Dashboards.collect(client(context, true), query, limit + 1);
        boolean truncated = rows.size() > limit;
        rows = Dashboards.redact(rows.subList(0, Math.min(rows.size(), limit)), context.render(getIncludeSecrets()).as(Boolean.class).orElse(false));
        boolean stored = context.render(store).as(Boolean.class).orElse(false);
        return Output.builder().dashboards(stored ? null : rows).uri(stored ? Dashboards.storeRows(context, rows) : null).size(rows.size()).truncated(truncated).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard summaries", description = "Absent when store is true.")
        private final java.util.List<Map<String, Object>> dashboards;
        @Schema(title = "ION results URI", description = "Present when store is true.")
        private final URI uri;
        @Schema(title = "Number of returned dashboards")
        private final Integer size;
        @Schema(title = "More dashboards exist")
        private final Boolean truncated;
    }
}
