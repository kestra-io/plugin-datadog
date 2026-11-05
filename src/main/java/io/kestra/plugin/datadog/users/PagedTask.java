package io.kestra.plugin.datadog.users;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Bounded organization list output with optional internal storage. */
@ValidDatadogLimits
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class PagedTask extends AbstractDatadogTask implements RunnableTask<PagedTask.Output> {
    @Schema(title = "Page size", description = "Number of resources requested per page. Between 1 and 100.", defaultValue = "100")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(100) Integer> pageSize;

    @Schema(title = "Maximum results", description = "Maximum resources to return. Between 1 and 10000. Check truncated before treating the output as a full inventory.", defaultValue = "1000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include secrets", description = "Include credential and URL fields in output and storage. These fields are redacted by default.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    protected Output collect(RunContext context, String path, Map<String, Object> query) throws Exception {
        try (var client = client(context, true)) {
            var size = AccessApi.range(context, pageSize, 100, 100, "pageSize");
            var max = AccessApi.range(context, maxResults, 1000, 10000, "maxResults");
            return output(context, AccessApi.collect(client, path, query, size, max));
        }
    }

    @SuppressWarnings("unchecked")
    protected Output output(RunContext context, AccessApi.Page page) throws Exception {
        var safe = (List<Map<String, Object>>) AccessApi.redact(page.rows(), AccessApi.flag(context, includeSecrets));
        var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), safe);
        return Output.builder().rows(result.rows()).row(result.row()).size(result.size()).truncated(page.truncated()).uri(result.uri()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Resource rows", description = "Datadog resources with credential fields redacted unless includeSecrets is true. Empty with STORE.")
        private final List<Map<String, Object>> rows;
        @Schema(title = "First resource", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Number of rows")
        private final Integer size;
        @Schema(title = "Incomplete inventory", description = "True when the result limit was reached before the list ended.")
        private final Boolean truncated;
        @Schema(title = "Stored ION file")
        private final URI uri;
    }
}
