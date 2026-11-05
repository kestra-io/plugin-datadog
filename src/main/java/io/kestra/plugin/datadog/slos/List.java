package io.kestra.plugin.datadog.slos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

/** Datadog operationId: ListSLOs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog SLOs", description = "Lists SLO definitions using Datadog query, tag and metric filters. Pages with limit and offset. Needs slos_read.")
@Plugin(examples = @Example(title = "Store the production SLO inventory", full = true, code = """
    id: datadog_slo_list
    namespace: company.observability
    tasks:
      - id: slos
        type: io.kestra.plugin.datadog.slos.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        tagsQuery: env:prod
        fetchType: STORE
    """))
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    @Schema(title = "SLO identifiers", description = "Comma-separated ids, sent as ids.")
    private Property<String> ids;
    @Schema(title = "SLO text query")
    private Property<String> query;
    @Schema(title = "Tags query")
    private Property<String> tagsQuery;
    @Schema(title = "Metrics query")
    private Property<String> metricsQuery;
    @Schema(title = "Deleted SLOs filter")
    private Property<Boolean> deleted;
    @Schema(title = "Maximum results", description = "Between 1 and 10000, default 1000. Reads one extra row to detect truncation.")
    private Property<@Min(1) @Max(10000) Integer> maxResults;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs without redaction. False by default.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            int limit = Slos.limit(context.render(maxResults).as(Integer.class).orElse(1000));
            Map<String, Object> params = DatadogClient.query();
            context.render(ids).as(String.class).ifPresent(value -> params.put("ids", value));
            context.render(query).as(String.class).ifPresent(value -> params.put("query", value));
            context.render(tagsQuery).as(String.class).ifPresent(value -> params.put("tags_query", value));
            context.render(metricsQuery).as(String.class).ifPresent(value -> params.put("metrics_query", value));
            context.render(deleted).as(Boolean.class).ifPresent(value -> params.put("is_deleted", value));
            var rows = Slos.collect(client, params, limit + 1);
            boolean truncated = rows.size() > limit;
            rows = rows.subList(0, Math.min(rows.size(), limit));
            var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
            var safe = rows.stream().map(row -> MonitorIO.redactMap(row, rIncludeSecrets)).toList();
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), safe);
            return Output.builder().slos(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).truncated(truncated).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO definitions", description = "Absent with STORE.")
        private final java.util.List<Map<String, Object>> slos;
        @Schema(title = "First SLO", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "ION results URI", description = "Present with STORE.")
        private final URI uri;
        @Schema(title = "Number of SLOs returned")
        private final Integer size;
        @Schema(title = "More SLOs exist")
        private final Boolean truncated;
    }
}
