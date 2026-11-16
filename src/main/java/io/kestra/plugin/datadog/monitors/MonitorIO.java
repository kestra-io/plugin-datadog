package io.kestra.plugin.datadog.monitors;

import java.util.LinkedHashMap;
import java.util.List;
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
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), SENSITIVE.matcher(String.valueOf(key)).matches() ? "[REDACTED]" : redact(item, false)));
            return result;
        }
        if (value instanceof List<?> list) {
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

}
