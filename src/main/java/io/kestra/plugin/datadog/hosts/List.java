package io.kestra.plugin.datadog.hosts;

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
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/** Datadog operationId: ListHosts. */
@ValidDatadogLimits
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog hosts", description = "Searches host names, aliases and tags with hosts_read permission. Reads offset pages up to maxResults and reports truncation. Datadog includes hosts seen in the last three hours by default and retains seven days of host inventory.")
@Plugin(examples = @Example(title = "Export production host inventory", full = true, code = """
    id: datadog_hosts
    namespace: company.observability
    tasks:
      - id: hosts
        type: io.kestra.plugin.datadog.hosts.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        filter: "env:prod"
        includeMutedHostsData: true
        maxResults: 10000
        fetchType: STORE
    """))
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    @Schema(title = "Search filter", description = "Datadog host search expression.")
    private Property<String> filter;
    @Schema(title = "Sort field", description = "Datadog host field used for sorting.")
    private Property<String> sortField;
    @Schema(title = "Sort direction", description = "ASC or DESC. Omitted by default.")
    private Property<SortDirection> sortDirection;
    @Schema(title = "Start time", description = "Optional instant sent as epoch seconds in from.")
    private Property<Instant> from;
    @Schema(title = "Include mute information", description = "Include host mute state and timeout.")
    private Property<Boolean> includeMutedHostsData;
    @Schema(title = "Include host metadata", description = "Include agent and machine metadata.")
    private Property<Boolean> includeHostsMetadata;
    @Schema(title = "Page size", description = "Hosts per request, from 1 to 1000. Defaults to 1000.")
    private Property<@Min(1) @Max(1000) Integer> pageSize;
    @Schema(title = "Maximum results", description = "Maximum hosts collected, from 1 to 10000. Defaults to 1000.")
    private Property<@Min(1) @Max(10000) Integer> maxResults;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in host metadata. False by default.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        int page = context.render(pageSize).as(Integer.class).orElse(1000);
        int max = context.render(maxResults).as(Integer.class).orElse(1000);
        if (page < 1 || page > 1000 || max < 1 || max > 10000) {
            throw new IllegalArgumentException("`pageSize` must be 1..1000 and `maxResults` must be 1..10000");
        }
        var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
        var params = DatadogClient.query();
        context.render(filter).as(String.class).ifPresent(value -> params.put("filter", value));
        context.render(sortField).as(String.class).ifPresent(value -> params.put("sort_field", value));
        context.render(sortDirection).as(SortDirection.class).ifPresent(value -> params.put("sort_dir", value.name().toLowerCase(Locale.ROOT)));
        context.render(from).as(Instant.class).ifPresent(value -> params.put("from", value.getEpochSecond()));
        context.render(includeMutedHostsData).as(Boolean.class).ifPresent(value -> params.put("include_muted_hosts_data", value));
        context.render(includeHostsMetadata).as(Boolean.class).ifPresent(value -> params.put("include_hosts_metadata", value));
        long count = 0;
        Long total = null;
        boolean truncated = false;
        try (var client = client(context, true);
             var sink = new DatadogFetch.Sink<Map<String, Object>>(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH))) {
            while (count < max) {
                int requested = Math.min(page, max - (int) count);
                params.put("count", requested);
                params.put("start", count);
                Map<String, Object> answer = client.get("/api/v1/hosts", params).map();
                if (!(answer.get("host_list") instanceof java.util.List<?> rows)) {
                    throw new IllegalStateException("Datadog host response has no host_list array");
                }
                total = answer.get("total_matching") instanceof Number n ? n.longValue() : null;
                if (rows.size() > requested) {
                    throw new IllegalStateException("Datadog returned more hosts than the requested page size");
                }
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> map)) {
                        throw new IllegalStateException("Datadog host must be an object");
                    }
                    @SuppressWarnings("unchecked") Map<String, Object> host = (Map<String, Object>) map;
                    sink.add(MonitorIO.redactMap(host, rIncludeSecrets));
                    count++;
                }
                boolean more = total != null ? count < total : rows.size() == requested;
                if (rows.isEmpty() && more) {
                    throw new IllegalStateException("Datadog returned an empty host page before total_matching was reached");
                }
                if (rows.isEmpty() || !more) {
                    break;
                }
                if (count == max) {
                    truncated = true;
                }
            }
            var result = sink.result();
            return Output.builder().hosts(result.rows()).row(result.row()).count(count).totalMatching(total).truncated(truncated)
                .uri(result.uri()).build();
        }
    }

    public enum SortDirection { ASC, DESC }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Hosts")
        private final java.util.List<Map<String, Object>> hosts;
        @Schema(title = "First host", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Collected host count")
        private final Long count;
        @Schema(title = "Total matching hosts", description = "Latest Datadog total, if supplied.")
        private final Long totalMatching;
        @Schema(title = "Result limit reached", description = "True when more hosts were reported or a full final page leaves the total unknown.")
        private final Boolean truncated;
        @Schema(title = "Stored ION hosts")
        private final URI uri;
    }
}
