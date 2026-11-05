package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.ValidDatadogLimits;
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
import java.util.Map;

/** Datadog operationId: ListDashboards. */
@ValidDatadogLimits
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
        fetchType: STORE
    """))
public class List extends AbstractDashboardTask implements RunnableTask<List.Output> {
    @Schema(title = "Shared dashboards filter")
    @PluginProperty(group = "main")
    private Property<Boolean> shared;
    @Schema(title = "Deleted dashboards filter")
    @PluginProperty(group = "main")
    private Property<Boolean> deleted;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000. Reads one extra row to detect truncation.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rLimit = Dashboards.limit(context.render(maxResults).as(Integer.class).orElse(1000));
            var query = DatadogClient.query();
            context.render(shared).as(Boolean.class).ifPresent(value -> query.put("filter[shared]", value));
            context.render(deleted).as(Boolean.class).ifPresent(value -> query.put("filter[deleted]", value));
            var rows = Dashboards.collect(client, query, rLimit + 1);
            var truncated = rows.size() > rLimit;
            rows = Dashboards.redact(rows.subList(0, Math.min(rows.size(), rLimit)), context.render(getIncludeSecrets()).as(Boolean.class).orElse(false));
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), rows);
            return Output.builder().dashboards(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).truncated(truncated).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Dashboard summaries", description = "Absent with STORE.")
        private final java.util.List<Map<String, Object>> dashboards;
        @Schema(title = "First dashboard", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "ION results URI", description = "Present with STORE.")
        private final URI uri;
        @Schema(title = "Number of returned dashboards")
        private final Integer size;
        @Schema(title = "More dashboards exist")
        private final Boolean truncated;
    }
}
