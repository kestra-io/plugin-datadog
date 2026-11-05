package io.kestra.plugin.datadog.logs;

import io.kestra.plugin.datadog.DatadogClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Total count requests for operationIds AggregateLogs and AggregateSpans. */
public final class Counts {
    private Counts() {
    }

    public static long query(DatadogClient client, Map<String, Object> filter, boolean spans) throws Exception {
        Map<String, Object> attributes = Map.of("filter", filter, "compute", List.of(Map.of("aggregation", "count", "type", "total")));
        String path = spans ? "/api/v2/spans/analytics/aggregate" : "/api/v2/logs/analytics/aggregate";
        Map<String, Object> answer = client.post(path, spans ? Telemetry.envelope(attributes, "aggregate_request") : attributes).map();
        Telemetry.complete(answer);
        if (Telemetry.cursor(answer) != null) {
            throw new IllegalStateException("Datadog returned a paged answer for a total count");
        }
        List<Map<String, Object>> buckets = Telemetry.buckets(answer, spans);
        if (buckets.isEmpty()) {
            return 0;
        }
        if (buckets.size() != 1 || !(buckets.getFirst().get("computes") instanceof Map<?, ?> computes)
            || !(computes.get("c0") instanceof Number || computes.get("c0") instanceof String)) {
            throw new IllegalStateException("Datadog returned a total count without a numeric computes.c0");
        }
        try {
            long value = new BigDecimal(computes.get("c0").toString()).longValueExact();
            if (value < 0) {
                throw new ArithmeticException("negative count");
            }
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("Datadog returned a count outside the nonnegative integer range", e);
        }
    }

    public static boolean breached(long count, long threshold) {
        return count > threshold;
    }

    public static long threshold(Long value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException("The Datadog `threshold` is required and must be nonnegative");
        }
        return value;
    }
}
