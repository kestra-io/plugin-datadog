package io.kestra.plugin.datadog.monitors;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Storage and conservative output filtering shared by the monitor and downtime tasks. */
public final class MonitorIO {
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(api.?key|app(?:lication)?.?key|password|secret|token|webhook).*");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);

    private MonitorIO() {
    }

    public static Object redact(Object value, boolean includeSecrets) {
        if (includeSecrets) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), SENSITIVE.matcher(String.valueOf(key)).matches() ? "[REDACTED]" : redact(item, false)));
            return result;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(item -> redact(item, false)).toList();
        }
        return value instanceof String text ? URL.matcher(text).replaceAll("[REDACTED_URL]") : value;
    }

    public static Object redactField(String field, Object value, boolean includeSecrets) {
        return !includeSecrets && SENSITIVE.matcher(field).matches() ? "[REDACTED]" : redact(value, includeSecrets);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, Object> value, boolean includeSecrets) {
        return (Map<String, Object>) redact(value, includeSecrets);
    }

    public static long positiveId(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("The Datadog `monitorId` must be a positive integer");
        }
        return id;
    }

    public static int limit(Integer value) {
        int limit = value == null ? 1000 : value;
        if (limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("The result limit must be between 1 and 10000");
        }
        return limit;
    }
}
