package io.kestra.plugin.datadog.logs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractAggregate extends AbstractWindow {
    @Schema(title = "Computations", description = "Computations in order. Results use c0, c1 and so on. Defaults to a total count.")
    private Property<List<Compute>> compute;

    @Schema(title = "Group by", description = "Facet groups and their bucket limits. Advanced histogram and sort fields can be set with extraBody.")
    private Property<List<GroupBy>> groupBy;

    @Schema(title = "Timezone", description = "Timezone used by Datadog for the aggregate options.")
    private Property<String> timezone;

    @Schema(title = "Starting page cursor", description = "Optional aggregate cursor for logs or RUM. Spans aggregates do not support paging.")
    private Property<String> pageCursor;

    @Schema(title = "Extra request body", description = "Recursively merged last into the complete API body. Arrays replace typed arrays. For spans use data.attributes. Advanced fields are validated by Datadog.")
    private Property<Map<String, Object>> extraBody;

    @Builder.Default
    @Schema(title = "Maximum buckets", description = "Return at most this many buckets across pages. Between 1 and 10000.", defaultValue = "1000")
    private Property<@Min(1) @Max(10000) Integer> maxResults = Property.ofValue(1000);

    @Builder.Default
    @Schema(title = "Fetch type", description = "FETCH returns buckets, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Builder.Default
    @Schema(title = "Include secrets", description = "Return credential fields and HTTP URLs without redaction.", defaultValue = "false")
    private Property<Boolean> includeSecrets = Property.ofValue(false);

    protected Output aggregate(RunContext context, String path, boolean spans) throws Exception {
        int cap = Telemetry.limit(context.render(maxResults).as(Integer.class).orElse(1000));
        boolean reveal = context.render(includeSecrets).as(Boolean.class).orElse(false);
        List<Compute> computations = context.render(compute).asList(Compute.class);
        if (computations.isEmpty()) {
            computations = List.of(Compute.builder().aggregation(Aggregation.COUNT).build());
        }
        List<Map<String, Object>> renderedCompute = new ArrayList<>();
        for (Compute item : computations) {
            renderedCompute.add(item.api());
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("compute", renderedCompute);
        attributes.put("filter", filter(context));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupBy item : context.render(groupBy).asList(GroupBy.class)) {
            groups.add(item.api());
        }
        if (!groups.isEmpty()) {
            attributes.put("group_by", groups);
        }
        context.render(timezone).as(String.class).ifPresent(value -> attributes.put("options", Map.of("timezone", value)));
        String cursor = context.render(pageCursor).as(String.class).orElse(null);
        if (spans && cursor != null) {
            throw new IllegalArgumentException("The Datadog spans aggregate does not support `pageCursor`");
        }
        if (cursor != null) {
            attributes.put("page", Map.of("cursor", cursor));
        }
        Map<String, Object> body = Telemetry.merge(spans ? Telemetry.envelope(attributes, "aggregate_request") : attributes,
            context.render(extraBody).asMap(String.class, Object.class));
        validateMergedWindow(body, spans);
        Set<String> seen = new HashSet<>();
        try (var client = client(context, true);
             var sink = new DatadogFetch.Sink<Map<String, Object>>(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH))) {
            do {
                Map<String, Object> answer = client.post(path, body).map();
                Telemetry.complete(answer);
                for (Map<String, Object> bucket : Telemetry.buckets(answer, spans)) {
                    if (sink.size() == cap) {
                        break;
                    }
                    sink.add(Telemetry.redact(bucket, reveal));
                }
                cursor = spans ? null : Telemetry.cursor(answer);
                if (sink.size() == cap) {
                    break;
                }
                if (cursor != null) {
                    if (!seen.add(cursor)) {
                        throw new IllegalStateException("Datadog repeated an aggregate page cursor");
                    }
                    body = Telemetry.merge(body, Map.of("page", Map.of("cursor", cursor)));
                }
            } while (cursor != null);
            var result = sink.result();
            return Output.builder().buckets(result.rows()).row(result.row()).size(result.size()).uri(result.uri()).nextCursor(cursor).build();
        }
    }

    private static void validateMergedWindow(Map<String, Object> body, boolean spans) {
        Object value = body;
        if (spans && body.get("data") instanceof Map<?, ?> data) {
            value = data.get("attributes");
        }
        if (value instanceof Map<?, ?> attributes && attributes.get("filter") instanceof Map<?, ?> filter) {
            for (String field : List.of("from", "to")) {
                if (filter.containsKey(field)) {
                    Telemetry.timestamp("extraBody.filter." + field, String.valueOf(filter.get(field)));
                }
            }
        }
    }

    public enum Aggregation {
        COUNT, CARDINALITY, PC75, PC90, PC95, PC98, PC99, SUM, MIN, MAX, AVG, MEDIAN;

        public String api() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ComputeType {
        TOTAL, TIMESERIES;

        public String api() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Compute {
        @Schema(title = "Aggregation", description = "COUNT, AVG, SUM, CARDINALITY or a percentile such as PC95.")
        private Aggregation aggregation;

        @Schema(title = "Metric", description = "Measure or facet for the computation. Required except for COUNT.")
        private String metric;

        @Schema(title = "Compute type", description = "TOTAL or TIMESERIES. Defaults to TOTAL.")
        private ComputeType computeType;

        @Schema(title = "Time bucket interval", description = "Datadog interval such as 1m or 1h. Only used with TIMESERIES.")
        private String interval;

        Map<String, Object> api() {
            if (aggregation == null) {
                throw new IllegalArgumentException("The Datadog `compute.aggregation` is required");
            }
            if (aggregation != Aggregation.COUNT && (metric == null || metric.isBlank())) {
                throw new IllegalArgumentException("The Datadog `compute.metric` is required for this aggregation");
            }
            if (interval != null && (!interval.matches("[1-9][0-9]*(ms|s|m|h|d)") || computeType != ComputeType.TIMESERIES)) {
                throw new IllegalArgumentException("The Datadog `compute.interval` needs a positive duration and TIMESERIES");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("aggregation", aggregation.api());
            out.put("type", computeType == null ? "total" : computeType.api());
            if (metric != null) {
                out.put("metric", metric);
            }
            if (interval != null) {
                out.put("interval", interval);
            }
            return out;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupBy {
        @Schema(title = "Facet", description = "Facet to group by, such as service.")
        private String facet;

        @Schema(title = "Bucket limit", description = "Positive bucket limit for this facet. Datadog applies its product limit across facets.")
        @Min(1)
        @Max(10000)
        private Integer limit;

        Map<String, Object> api() {
            if (facet == null || facet.isBlank()) {
                throw new IllegalArgumentException("The Datadog `groupBy.facet` is required");
            }
            if (limit != null && (limit < 1 || limit > 10000)) {
                throw new IllegalArgumentException("The Datadog `groupBy.limit` must be between 1 and 10000");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("facet", facet);
            if (limit != null) {
                out.put("limit", limit);
            }
            return out;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Buckets", description = "Bucket by and computes objects. Present with FETCH.")
        private final List<Map<String, Object>> buckets;

        @Schema(title = "First bucket", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;

        @Schema(title = "Number of buckets")
        private final Integer size;

        @Schema(title = "Stored ION file")
        private final URI uri;

        @Schema(title = "Next page cursor", description = "Cursor when the cap stopped paging. Null at the end. The cap can truncate a page; use a larger cap to retain that entire page.")
        private final String nextCursor;
    }
}
