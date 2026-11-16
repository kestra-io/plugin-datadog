package io.kestra.plugin.datadog.monitors;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Writable projection and field diff for CreateMonitor, UpdateMonitor and ListMonitors. */
public final class MonitorDefinitions {
    static final String KEY_PREFIX = "managed_by:kestra:";
    private static final Set<String> SERVER = Set.of("id", "created", "modified", "deleted", "creator", "multi", "overall_state", "overall_state_modified", "state", "matching_downtimes");

    private MonitorDefinitions() {
    }

    static Map<String, Object> writable(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!SERVER.contains(key)) {
                result.put(key, value);
            }
        });
        if (result.get("options") instanceof Map<?, ?> options) {
            var kept = object(options);
            kept.remove("aggregation");
            kept.remove("device_ids");
            result.put("options", kept);
        }
        return result;
    }

    static Map<String, Object> validate(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("A non-empty monitor `definition` is required");
        }
        var result = writable(source);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The monitor definition must contain writable fields");
        }
        return result;
    }

    static String key(Map<String, Object> definition) {
        var raw = definition.get("tags");
        var keys = raw instanceof List<?> tags
            ? tags.stream().filter(String.class::isInstance).map(String.class::cast).filter(tag -> tag.startsWith(KEY_PREFIX)).toList()
            : List.<String>of();
        if (keys.size() != 1 || !keys.getFirst().substring(KEY_PREFIX.length()).matches("[a-z0-9][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Each Apply definition needs exactly one managed_by:kestra:<key> tag; use 1 to 128 lowercase letters, digits, dots, underscores or hyphens");
        }
        return keys.getFirst();
    }

    static Map<String, Object> object(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Definition object keys must be strings");
            }
            result.put(text, value);
        });
        return result;
    }

    static List<Change> diff(Map<String, Object> current, Map<String, Object> desired) {
        var result = new ArrayList<Change>();
        compare("", writable(current), writable(desired), result);
        return result;
    }

    private static void compare(String path, Map<String, Object> current, Map<String, Object> desired, List<Change> result) {
        desired.forEach((key, after) -> {
            var before = current.get(key);
            var field = "%s/%s".formatted(path, key.replace("~", "~0").replace("/", "~1"));
            if (after instanceof Map<?, ?> map) {
                compare(field, before instanceof Map<?, ?> old ? object(old) : Map.of(), object(map), result);
            } else if (!equivalent(before, after)) {
                result.add(Change.builder().field(field).before(before).after(after).build());
            }
        });
    }

    private static boolean equivalent(Object before, Object after) {
        if (before instanceof Number left && after instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(before, after);
    }

    /** Merge managed nested fields so an update does not discard unowned option values. */
    static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> desired) {
        var result = new LinkedHashMap<String, Object>(current);
        desired.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                result.put(key, merge(current.get(key) instanceof Map<?, ?> old ? object(old) : Map.of(), object(map)));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    @Builder
    @Getter
    public static class Change {
        @Schema(title = "JSON pointer to the changed field")
        private final String field;
        @Schema(title = "Previous value")
        private final Object before;
        @Schema(title = "Desired value")
        private final Object after;
    }
}
