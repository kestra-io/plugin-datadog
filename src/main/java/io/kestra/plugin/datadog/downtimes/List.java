package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog v2 operationId: ListDowntimes. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "List Datadog downtimes", description = "Reads v2 downtimes with offset paging up to maxResults. Needs monitors_downtime. Set currentOnly to select active downtimes.")
@Plugin(examples = @Example(title = "Audit active maintenance windows", full = true, code = """
    id: list_downtimes
    namespace: company.observability
    tasks:
      - id: list
        type: io.kestra.plugin.datadog.downtimes.List
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        currentOnly: true
        store: true
    """))
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    @Schema(title = "Only active downtimes")
    private Property<Boolean> currentOnly;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000.")
    private Property<Integer> maxResults;
    @Schema(title = "Store results", description = "Write downtime rows to an ION file instead of returning them inline.")
    private Property<Boolean> store;
    @Schema(title = "Include sensitive output values", description = "False by default. Filters credential-like keys and HTTP URLs.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        int limit = MonitorIO.limit(context.render(maxResults).as(Integer.class).orElse(null));
        boolean storage = context.render(store).as(Boolean.class).orElse(false);
        boolean include = context.render(includeSecrets).as(Boolean.class).orElse(false);
        var params = DatadogClient.query();
        context.render(currentOnly).as(Boolean.class).ifPresent(value -> params.put("current_only", value));
        int pageSize = Math.min(100, limit);
        params.put("page[limit]", pageSize);
        var client = client(context, true);
        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = 0; rows.size() < limit; offset += pageSize) {
            params.put("page[offset]", offset);
            var response = client.get("/api/v2/downtime", params).map();
            if (!(response.get("data") instanceof java.util.List<?> data)) {
                throw new IllegalStateException("Datadog downtime response must contain a data array");
            }
            for (Object row : data) {
                if (!(row instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("Datadog downtime data must contain objects");
                }
                if (rows.size() < limit) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    rows.add(MonitorIO.redactMap(item, include));
                }
            }
            if (data.size() < pageSize) {
                break;
            }
        }
        return Output.builder().downtimes(storage ? null : rows).uri(storage ? MonitorIO.store(context, rows, false) : null).size(rows.size()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtimes", description = "v2 data rows. Omitted when store is true.")
        private final java.util.List<Map<String, Object>> downtimes;
        @Schema(title = "Number of returned downtimes")
        private final Integer size;
        @Schema(title = "Result file URI")
        private final URI uri;
    }
}
