package io.kestra.plugin.datadog.incidents;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/** Bounded paging and optional streaming storage for the incident response list tasks. */
@ValidDatadogLimits
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class PaginatedResponseTask extends ResponseSupport {
    @Schema(title = "Page size", description = "Plugin page size from 1 to 100. Defaults to 50.")
    private Property<@Min(1) @Max(100) Integer> pageSize;

    @Schema(title = "Maximum results", description = "Plugin result limit from 1 to 10000. Defaults to 1000.")
    private Property<@Min(1) @Max(10000) Integer> maxResults;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    protected CollectionOutput collect(RunContext context, String path, Map<String, Object> query,
                                       String sizeKey, boolean numbered, boolean search) throws Exception {
        int size = context.render(pageSize).as(Integer.class).orElse(50);
        int limit = context.render(maxResults).as(Integer.class).orElse(1000);
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("`pageSize` must be between 1 and 100");
        }
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("`maxResults` must be between 1 and 10000");
        }
        int count = 0;
        try (var client = client(context, true);
             var sink = new DatadogFetch.Sink<Map<String, Object>>(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH))) {
            long position = 0;
            while (count < limit) {
                // Keep numbered page sizes stable so a last partial page does not shift its offset.
                int requested = numbered ? size : Math.min(size, limit - count);
                query.put(sizeKey, requested);
                query.put(numbered ? "page[number]" : "page[offset]", position);
                Map<String, Object> answer = client.get(path, query).map();
                Object rows = search ? nested(answer, "data", "attributes", "incidents") : answer.get("data");
                if (!(rows instanceof java.util.List<?> page)) {
                    throw new IllegalStateException("Datadog list response must contain a resource array");
                }
                for (Object row : page) {
                    if (!(row instanceof Map<?, ?>)) {
                        throw new IllegalStateException("Datadog list returned a non-object resource");
                    }
                    if (count == limit) {
                        break;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> safe = safe(context, (Map<String, Object>) row);
                    sink.add(safe);
                    count++;
                }
                Object pagination = nested(answer, "meta", "pagination");
                if (pagination == null) {
                    pagination = nested(answer, "meta", "page");
                }
                if (!numbered && pagination instanceof Map<?, ?> meta && meta.containsKey("next_offset")) {
                    Object next = meta.get("next_offset");
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
            return CollectionOutput.builder().items(result.rows()).row(result.row()).count(count).limitReached(count == limit)
                .uri(result.uri()).build();
        }
    }

    private static Object nested(Map<String, Object> map, String... keys) {
        Object value = map;
        for (String key : keys) {
            value = value instanceof Map<?, ?> child ? child.get(key) : null;
        }
        return value;
    }
}
