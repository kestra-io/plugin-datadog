package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** Bounded paging and optional streaming storage for the incident response list tasks. */
@ValidDatadogLimits
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class PaginatedResponseTask extends ResponseSupport {
    @Schema(title = "Page size", description = "Plugin page size from 1 to 100. Defaults to 50.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(100) Integer> pageSize;

    @Schema(title = "Maximum results", description = "Plugin result limit from 1 to 10000. Defaults to 1000.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    protected CollectionOutput collect(RunContext context, String path, Map<String, Object> query,
                                       String sizeKey, boolean numbered, boolean search) throws Exception {
        var rSize = context.render(pageSize).as(Integer.class).orElse(50);
        var rLimit = context.render(maxResults).as(Integer.class).orElse(1000);
        if (rSize < 1 || rSize > 100) {
            throw new IllegalArgumentException("`pageSize` must be between 1 and 100");
        }
        if (rLimit < 1 || rLimit > 10000) {
            throw new IllegalArgumentException("`maxResults` must be between 1 and 10000");
        }
        var count = 0;
        try (var client = client(context, true);
             var sink = new DatadogFetch.Sink<Map<String, Object>>(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH))) {
            var position = 0L;
            while (count < rLimit) {
                // Keep numbered page sizes stable so a last partial page does not shift its offset.
                var requested = numbered ? rSize : Math.min(rSize, rLimit - count);
                query.put(sizeKey, requested);
                query.put(numbered ? "page[number]" : "page[offset]", position);
                var answer = client.get(path, query).map();
                var rows = search ? nested(answer, "data", "attributes", "incidents") : answer.get("data");
                if (!(rows instanceof List<?> page)) {
                    throw new IllegalStateException("Datadog list response must contain a resource array");
                }
                for (var row : page) {
                    if (!(row instanceof Map<?, ?>)) {
                        throw new IllegalStateException("Datadog list returned a non-object resource");
                    }
                    if (count == rLimit) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    var safe = safe(context, (Map<String, Object>) row);
                    sink.add(safe);
                    count++;
                }
                var pagination = nested(answer, "meta", "pagination");
                if (pagination == null) {
                    pagination = nested(answer, "meta", "page");
                }
                if (!numbered && pagination instanceof Map<?, ?> meta && meta.containsKey("next_offset")) {
                    var next = meta.get("next_offset");
                    if (next == null || page.isEmpty()) {
                        break;
                    }
                    if (!(next instanceof Number number) || number.longValue() <= position) {
                        throw new IllegalStateException("Datadog pagination did not advance");
                    }
                    position = number.longValue();
                } else {
                    if (page.size() < requested) {
                        break;
                    }
                    position += numbered ? 1 : page.size();
                }
            }
            var result = sink.result();
            return CollectionOutput.builder().items(result.rows()).row(result.row()).count(count).limitReached(count == rLimit)
                .uri(result.uri()).build();
        }
    }

    private static Object nested(Map<String, Object> map, String... keys) {
        var value = (Object) map;
        for (var key : keys) {
            value = value instanceof Map<?, ?> child ? child.get(key) : null;
        }
        return value;
    }
}
