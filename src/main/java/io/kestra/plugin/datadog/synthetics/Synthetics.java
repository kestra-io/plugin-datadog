package io.kestra.plugin.datadog.synthetics;

import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.monitors.MonitorIO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared validation and paging for ListTests, SearchTests and TriggerCITests. */
final class Synthetics {
    private Synthetics() {
    }

    static String id(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("`" + name + "` must be a nonblank Datadog identifier");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Datadog returned no object for " + name);
        }
        return (Map<String, Object>) value;
    }

    static List<Map<String, Object>> rows(Object value, String name) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("Datadog returned no array for " + name);
        }
        return values.stream().map(row -> object(row, name)).toList();
    }

    static int limit(int value) {
        if (value < 1 || value > 10_000) {
            throw new IllegalArgumentException("`maxTests` must be between 1 and 10000");
        }
        return value;
    }

    static List<Map<String, Object>> collect(DatadogClient client, String search, int limit) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        var pageSize = Math.min(100, limit);
        for (var page = 0; result.size() < limit; page++) {
            var searching = search != null && !search.isBlank();
            var query = searching
                ? Map.of("text", search, "count", pageSize, "start", page * pageSize, "include_full_config", false)
                : Map.of("page_size", pageSize, "page_number", page);
            var body = client.get("/api/v1/synthetics/tests" + (searching ? "/search" : ""), query).map();
            var rows = rows(body.get("tests"), "tests");
            for (var row : rows) {
                if (result.size() < limit) {
                    result.add(row);
                }
            }
            if (rows.size() < pageSize) {
                break;
            }
        }
        return result;
    }

    /** Only a documented summary is exposed by default. Arbitrary nested request content can contain credentials. */
    static Map<String, Object> summary(Map<String, Object> raw, boolean includeSecrets) {
        if (includeSecrets) {
            return raw;
        }
        var safe = new LinkedHashMap<String, Object>();
        for (var key : List.of("public_id", "name", "type", "subtype", "status", "tags", "locations", "monitor_id")) {
            if (raw.containsKey(key)) {
                safe.put(key, raw.get(key));
            }
        }
        return MonitorIO.redactMap(safe, false);
    }

}
