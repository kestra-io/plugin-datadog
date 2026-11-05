package io.kestra.plugin.datadog.monitors;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

/** Datadog operationIds: GetMonitorNotificationRules, GetMonitorNotificationRule,
 * ListMonitorConfigPolicies, GetMonitorConfigPolicy, ListMonitorUserTemplates and GetMonitorUserTemplate. */
@ValidDatadogLimits
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Read Datadog monitor configuration", description = "Reads notification rules, configuration policies or user templates from the v2 API. Set resourceId to read one object. Needs monitors_read. Notification rules are paged; policy and template lists have no paging parameters in the API. Fails if the result exceeds maxResults.")
@Plugin(examples = @Example(title = "Audit monitor notification rules", full = true, code = """
    id: monitor_configuration
    namespace: company.observability
    tasks:
      - id: rules
        type: io.kestra.plugin.datadog.monitors.Configuration
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        resource: NOTIFICATION_RULES
        fetchType: STORE
    """))
public class Configuration extends AbstractMonitorTask implements RunnableTask<Configuration.Output> {
    @NotNull
    @Schema(title = "Configuration resource")
    private Property<Resource> resource;
    @Schema(title = "Resource id", description = "Optional id. Without it the task lists the selected resource.")
    private Property<String> resourceId;
    @Schema(title = "Maximum results", description = "Between 1 and 10000. Defaults to 1000. Exceeding the limit fails instead of truncating an audit.")
    private Property<@Min(1) @Max(10000) Integer> maxResults;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            Resource selected = context.render(resource).as(Resource.class).orElseThrow(() -> new IllegalArgumentException("resource is required"));
            String id = context.render(resourceId).as(String.class).orElse(null);
            if (id != null && maxResults != null) {
                throw new IllegalArgumentException("`maxResults` cannot be used with `resourceId`");
            }
            if (id != null && (id.isBlank() || id.equals(".") || id.equals(".."))) {
                throw new IllegalArgumentException("resourceId must identify one object");
            }
            int limit = MonitorIO.limit(context.render(maxResults).as(Integer.class).orElse(null));
            boolean include = includeSecrets(context);
            String path = "/api/v2/monitor/" + selected.path + (id == null ? "" : "/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
            var params = DatadogClient.query();
            java.util.List<Map<String, Object>> rows = new ArrayList<>();
            for (int page = 0; ; page++) {
                if (id == null && selected == Resource.NOTIFICATION_RULES) {
                    params.put("page", page);
                    params.put("per_page", Math.min(100, limit + 1));
                }
                Object data = client.get(path, params).map().get("data");
                if (id != null) {
                    if (!(data instanceof Map<?, ?> map)) {
                        throw new IllegalStateException("Datadog configuration response must contain a data object");
                    }
                    rows.add(MonitorIO.redactMap(MonitorDefinitions.object(map), include));
                    break;
                }
                if (!(data instanceof java.util.List<?> list)) {
                    throw new IllegalStateException("Datadog configuration response must contain a data array");
                }
                for (Object row : list) {
                    if (!(row instanceof Map<?, ?> map)) {
                        throw new IllegalStateException("Datadog configuration data must contain objects");
                    }
                    rows.add(MonitorIO.redactMap(MonitorDefinitions.object(map), include));
                    if (rows.size() > limit) {
                        throw new IllegalStateException("Configuration exceeds maxResults");
                    }
                }
                if (selected != Resource.NOTIFICATION_RULES || list.size() < (int) params.get("per_page")) {
                    break;
                }
            }
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), rows);
            return Output.builder().data(result.rows()).row(result.row()).uri(result.uri()).size(result.size()).build();
        }
    }

    public enum Resource {
        NOTIFICATION_RULES("notification_rule"), POLICIES("policy"), TEMPLATES("template");
        private final String path;
        Resource(String path) {
            this.path = path;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Configuration objects", description = "One row for resourceId or the full list. Omitted with STORE.")
        private final java.util.List<Map<String, Object>> data;
        @Schema(title = "First configuration", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of objects")
        private final Integer size;
        @Schema(title = "Result file URI")
        private final URI uri;
    }
}
