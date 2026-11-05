package io.kestra.plugin.datadog.monitors;

import io.kestra.plugin.datadog.DatadogClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads the monitor list page by page, for the {@link io.kestra.plugin.datadog.monitors.List} task and the {@link StateChange} trigger. */
final class Monitors {
    static final int PAGE_SIZE = 200;

    private Monitors() {
    }

    /** Collects up to {@code limit} monitors with the Datadog filters already set in {@code params}. */
    static List<Map<String, Object>> collect(DatadogClient client, Map<String, Object> params, int limit) throws Exception {
        var pageSize = Math.min(PAGE_SIZE, limit);
        params.put("page_size", pageSize);

        var monitors = new ArrayList<Map<String, Object>>();
        for (int page = 0; monitors.size() < limit; page++) {
            params.put("page", page);
            var json = client.get("/api/v1/monitor", params).json();
            if (!(json instanceof List<?> rows)) {
                throw new IllegalStateException("Datadog returned an unexpected answer for the monitor list: a JSON array was expected");
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?>)) {
                    throw new IllegalStateException("Datadog returned a non-object in the monitor list");
                }
                if (row instanceof Map<?, ?> raw && monitors.size() < limit) {
                    @SuppressWarnings("unchecked")
                    var monitor = (Map<String, Object>) raw;
                    monitors.add(monitor);
                }
            }
            // a short page is the last one
            if (rows.size() < pageSize) {
                break;
            }
        }
        return monitors;
    }

    /** Joins the non-blank values with a comma, the way the Datadog filters expect them. */
    static Optional<String> join(List<String> values) {
        var kept = values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
        return kept.isEmpty() ? Optional.empty() : Optional.of(String.join(",", kept));
    }
}
