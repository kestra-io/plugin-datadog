package io.kestra.plugin.datadog.monitors;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Writable projection and field diff for CreateMonitor, UpdateMonitor and ListMonitors. */
public final class MonitorDefinitions {
    static final String KEY_PREFIX = "managed_by:kestra:";
    private static final Set<String> WRITABLE = Set.of("name", "type", "query", "message", "tags", "options", "priority", "restricted_roles", "draft_status", "assets");
    private static final Set<String> SERVER = Set.of("id", "created", "modified", "deleted", "creator", "multi", "overall_state", "overall_state_modified", "state", "matching_downtimes");

    private MonitorDefinitions() {
    }

    static Map<String, Object> writable(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (WRITABLE.contains(key)) {
                result.put(key, value);
            }
        });
        if (result.get("options") instanceof Map<?, ?> options) {
            Map<String, Object> kept = object(options);
            kept.remove("aggregation");
            kept.remove("device_ids");
            result.put("options", kept);
        }
        return result;
    }

    static Map<String, Object> validate(Map<String, Object> source, boolean complete) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("A non-empty monitor `definition` is required");
        }
        for (String field : source.keySet()) {
            if (!WRITABLE.contains(field) && !SERVER.contains(field)) {
                throw new IllegalArgumentException("Unknown monitor definition field: " + field);
            }
        }
        Map<String, Object> result = writable(source);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The monitor definition must contain writable fields");
        }
        for (String field : java.util.List.of("query", "type")) {
            if (complete || result.containsKey(field)) {
                if (!(result.get(field) instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("The monitor definition `" + field + "` must not be blank");
                }
            }
        }
        if (result.containsKey("type")) {
            result.put("type", MonitorType.fromValue(result.get("type")).value());
        }
        for (String field : java.util.List.of("name", "message", "draft_status")) {
            if (result.containsKey(field) && !(result.get(field) instanceof String)) {
                throw new IllegalArgumentException("The monitor definition `" + field + "` must be a string");
            }
        }
        if (result.containsKey("draft_status")) {
            result.put("draft_status", DraftStatus.fromValue(result.get("draft_status")).value);
        }
        for (String field : java.util.List.of("tags", "restricted_roles")) {
            Object value = result.get(field);
            if (value == null && field.equals("restricted_roles")) {
                continue;
            }
            if (result.containsKey(field) && (!(value instanceof java.util.List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))) {
                throw new IllegalArgumentException("The monitor definition `" + field + "` must be an array of strings");
            }
        }
        if (result.containsKey("options") && !(result.get("options") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("The monitor `options` must be an object");
        }
        if (result.get("priority") != null) {
            Object value = result.get("priority");
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 5) {
                throw new IllegalArgumentException("The monitor `priority` must be an integer between 1 and 5");
            }
        }
        if (result.containsKey("assets") && result.get("assets") != null && !(result.get("assets") instanceof java.util.List<?>)) {
            throw new IllegalArgumentException("The monitor `assets` must be an array");
        }
        return result;
    }

    private enum DraftStatus {
        DRAFT("draft"), PUBLISHED("published");
        private final String value;
        DraftStatus(String value) {
            this.value = value;
        }
        static DraftStatus fromValue(Object value) {
            for (DraftStatus status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("The monitor `draft_status` must be draft or published");
        }
    }

    static String key(Map<String, Object> definition) {
        Object raw = definition.get("tags");
        java.util.List<String> keys = raw instanceof java.util.List<?> tags
            ? tags.stream().filter(String.class::isInstance).map(String.class::cast).filter(tag -> tag.startsWith(KEY_PREFIX)).toList()
            : java.util.List.of();
        if (keys.size() != 1 || !keys.getFirst().substring(KEY_PREFIX.length()).matches("[a-z0-9][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Each Apply definition needs exactly one managed_by:kestra:<key> tag; use 1 to 128 lowercase letters, digits, dots, underscores or hyphens");
        }
        return keys.getFirst();
    }

    static Map<String, Object> object(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Definition object keys must be strings");
            }
            result.put(text, value);
        });
        return result;
    }

    static java.util.List<Change> diff(Map<String, Object> current, Map<String, Object> desired) {
        java.util.List<Change> result = new ArrayList<>();
        compare("", writable(current), writable(desired), result);
        return result;
    }

    private static void compare(String path, Map<String, Object> current, Map<String, Object> desired, java.util.List<Change> result) {
        desired.forEach((key, after) -> {
            Object before = current.get(key);
            String field = path + "/" + key.replace("~", "~0").replace("/", "~1");
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
        Map<String, Object> result = new LinkedHashMap<>(current);
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
