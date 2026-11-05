package io.kestra.plugin.datadog.logs;

import io.kestra.plugin.datadog.ValidDatadogLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@ValidDatadogLimits
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractSearch extends AbstractWindow {
    @Schema(title = "Sort order", description = "ASCENDING for oldest first or DESCENDING for newest first. Omit to use the API default.")
    private Property<Sort> sort;

    @Builder.Default
    @Schema(title = "Maximum results", description = "Read until the cursor ends or this cap is reached. Between 1 and 10000.", defaultValue = "100")
    private Property<@Min(1) @Max(10000) Integer> maxResults = Property.ofValue(100);

    @Builder.Default
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Builder.Default
    @Schema(title = "Include secrets", description = "Return credential fields and HTTP URLs without redaction.", defaultValue = "false")
    private Property<Boolean> includeSecrets = Property.ofValue(false);

    protected Output search(RunContext context, String path, boolean spans, boolean flatten) throws Exception {
        int cap = Telemetry.limit(context.render(maxResults).as(Integer.class).orElse(100));
        Map<String, Object> filter = filter(context);
        Sort order = context.render(sort).as(Sort.class).orElse(null);
        boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
        Set<String> seen = new HashSet<>();
        String cursor = null;
        try (var client = client(context, true);
             var sink = new DatadogFetch.Sink<Map<String, Object>>(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH))) {
            do {
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("limit", Math.min(1000, cap - sink.size()));
                if (cursor != null) {
                    page.put("cursor", cursor);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("filter", filter);
                body.put("page", page);
                if (order != null) {
                    body.put("sort", order.api());
                }
                Map<String, Object> answer = client.post(path, spans ? Telemetry.envelope(body, "search_request") : body).map();
                Telemetry.complete(answer);
                for (Map<String, Object> row : Telemetry.rows(answer.get("data"))) {
                    if (sink.size() == cap) {
                        break;
                    }
                    if (flatten) {
                        Map<String, Object> flat = new LinkedHashMap<>();
                        if (row.get("attributes") instanceof Map<?, ?> attributes) {
                            attributes.forEach((k, v) -> flat.put(String.valueOf(k), v));
                        }
                        flat.put("id", row.get("id"));
                        sink.add(Telemetry.redact(flat, reveal));
                    } else {
                        sink.add(Telemetry.redact(row, reveal));
                    }
                }
                cursor = sink.size() < cap ? Telemetry.cursor(answer) : null;
                if (cursor != null && !seen.add(cursor)) {
                    throw new IllegalStateException("Datadog repeated a telemetry page cursor");
                }
            } while (cursor != null);
            var result = sink.result();
            return Output.builder().events(result.rows()).row(result.row()).size(result.size()).uri(result.uri()).build();
        }
    }

    public enum Sort {
        ASCENDING("timestamp"), DESCENDING("-timestamp");

        private final String api;

        Sort(String api) {
            this.api = api;
        }

        public String api() {
            return api;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Events", description = "Datadog resources with id, type and attributes. Present with FETCH.")
        private final List<Map<String, Object>> events;

        @Schema(title = "First event", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;

        @Schema(title = "Number of events")
        private final Integer size;

        @Schema(title = "Stored ION file", description = "Internal storage URI with STORE.")
        private final URI uri;
    }
}
