package io.kestra.plugin.datadog.slos;

import io.kestra.plugin.datadog.DatadogClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Helpers for GetSLO, ListSLOs and GetSLOHistory. */
final class Slos {
    static final String PATH = "/api/v1/slo";

    private Slos() {
    }

    static String path(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("`sloId` must be a non-blank SLO identifier");
        }
        return PATH + "/" + id;
    }

    static void window(Long from, Long to) {
        if (from == null || to == null || from < 0 || to <= from) {
            throw new IllegalArgumentException("`from` and `to` must be epoch seconds with 0 <= from < to");
        }
    }

    static double target(Double target) {
        if (target == null || !Double.isFinite(target) || target <= 0 || target >= 100) {
            throw new IllegalArgumentException("`target` must be finite and strictly between 0 and 100");
        }
        return target;
    }

    static double minimum(double minimum) {
        if (!Double.isFinite(minimum) || minimum > 100) {
            throw new IllegalArgumentException("`minRemainingBudget` must be finite and at most 100");
        }
        return minimum;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Datadog did not return an object at " + field);
        }
        return (Map<String, Object>) value;
    }

    static void noErrors(Map<String, Object> response) {
        var errors = response.get("errors");
        if (errors != null && !(errors instanceof List<?> list && list.isEmpty())) {
            throw new IllegalStateException("Datadog reported SLO query errors; no valid result is available");
        }
    }

    static Map<String, Object> history(DatadogClient client, String id, long from, long to, Double target, boolean corrections) throws Exception {
        var params = DatadogClient.query();
        params.put("from_ts", from);
        params.put("to_ts", to);
        if (target != null) {
            params.put("target", target);
        }
        params.put("apply_correction", corrections);
        var response = client.get(path(id) + "/history", params).map();
        noErrors(response);
        return object(response.get("data"), "data");
    }

    static Budget budget(Map<String, Object> history, double target) {
        target(target);
        var overall = object(history.get("overall"), "data.overall");
        noErrors(overall);
        if (Boolean.TRUE.equals(overall.get("preview"))) {
            throw new IllegalStateException("Datadog is replaying SLO history; preview data cannot pass a budget gate");
        }
        if (!(overall.get("sli_value") instanceof Number value) || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0 || value.doubleValue() > 100) {
            throw new IllegalStateException("Datadog did not return a finite data.overall.sli_value between 0 and 100");
        }
        // Datadog's documented percent-of-budget formula. Decimal math keeps exact threshold equality stable.
        var hundred = BigDecimal.valueOf(100);
        var objective = BigDecimal.valueOf(target);
        var remaining = hundred.multiply(new BigDecimal(value.toString()).subtract(objective))
            .divide(hundred.subtract(objective), MathContext.DECIMAL128).doubleValue();
        return new Budget(value.doubleValue(), remaining);
    }

    record Budget(double sli, double remaining) {
    }

    static int limit(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("`maxResults` must be between 1 and 10000");
        }
        return limit;
    }

    static List<Map<String, Object>> collect(DatadogClient client, Map<String, Object> query, int limit) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < limit) {
            var size = Math.min(1000, limit - rows.size());
            query.put("limit", size);
            query.put("offset", rows.size());
            var response = client.get(PATH, query).map();
            noErrors(response);
            if (!(response.get("data") instanceof List<?> page)) {
                throw new IllegalStateException("Datadog did not return an SLO data array");
            }
            for (var row : page) {
                if (rows.size() < limit) {
                    rows.add(object(row, "data[]"));
                }
            }
            if (page.size() < size) {
                break;
            }
        }
        return rows;
    }

}
