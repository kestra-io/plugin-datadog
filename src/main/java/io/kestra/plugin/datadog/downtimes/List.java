package io.kestra.plugin.datadog.downtimes;

import io.kestra.plugin.datadog.ValidDatadogLimits;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog v2 operationId: ListDowntimes. */
@ValidDatadogLimits
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
        fetchType: STORE
    """))
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    @Schema(title = "Only active downtimes")
    private Property<Boolean> currentOnly;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000.")
    private Property<@Min(1) @Max(10000) Integer> maxResults;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);
    @Schema(title = "Include sensitive output values", description = "False by default. Filters credential-like keys and HTTP URLs.")
    private Property<Boolean> includeSecrets;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            int limit = MonitorIO.limit(context.render(maxResults).as(Integer.class).orElse(null));
            boolean include = context.render(includeSecrets).as(Boolean.class).orElse(false);
            var params = DatadogClient.query();
            context.render(currentOnly).as(Boolean.class).ifPresent(value -> params.put("current_only", value));
            int pageSize = Math.min(100, limit);
            params.put("page[limit]", pageSize);
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
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), rows);
            return Output.builder().downtimes(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtimes", description = "v2 data rows. Omitted with STORE.")
        private final java.util.List<Map<String, Object>> downtimes;
        @Schema(title = "First downtime", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of returned downtimes")
        private final Integer size;
        @Schema(title = "Result file URI")
        private final URI uri;
    }
}
