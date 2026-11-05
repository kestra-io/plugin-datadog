package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Helpers for ListDashboards, GetDashboard, CreateDashboard and UpdateDashboard. */
public final class Dashboards {
    static final String PATH = "/api/v1/dashboard";
    static final String REDACTED = "[REDACTED]";
    private static final Set<String> READ_ONLY = Set.of("id", "author_handle", "author_name", "created_at", "modified_at", "url", "experience_type");

    private Dashboards() {
    }

    static String path(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("`dashboardId` must be a non-blank dashboard identifier");
        }
        return PATH + "/" + id;
    }

    static Map<String, Object> definition(Map<String, Object> source) {
        if (source == null) {
            throw new IllegalArgumentException("`definition` is required");
        }
        if (!(source.get("title") instanceof String title) || title.isBlank()) {
            throw new IllegalArgumentException("`definition.title` must be a non-blank string");
        }
        Layout.fromApi(source.get("layout_type"));
        if (!(source.get("widgets") instanceof java.util.List<?>)) {
            throw new IllegalArgumentException("`definition.widgets` must be an array");
        }
        Map<String, Object> writable = new LinkedHashMap<>(source);
        READ_ONLY.forEach(writable::remove);
        return writable;
    }

    enum Layout {
        ORDERED("ordered"), FREE("free");

        private final String value;

        Layout(String value) {
            this.value = value;
        }

        static Layout fromApi(Object value) {
            for (Layout layout : values()) {
                if (layout.value.equals(value)) {
                    return layout;
                }
            }
            throw new IllegalArgumentException("`definition.layout_type` must be ordered or free");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Datadog returned an invalid dashboard object");
        }
        return (Map<String, Object>) value;
    }

    static int limit(int value) {
        if (value < 1 || value > 10_000) {
            throw new IllegalArgumentException("`maxResults` must be between 1 and 10000");
        }
        return value;
    }

    static java.util.List<Map<String, Object>> collect(DatadogClient client, Map<String, Object> params, int limit) throws Exception {
        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < limit) {
            int count = Math.min(100, limit - rows.size());
            params.put("count", count);
            params.put("start", rows.size());
            Object response = client.get(PATH, params).map().get("dashboards");
            if (!(response instanceof java.util.List<?> page)) {
                throw new IllegalStateException("Datadog did not return a dashboards array");
            }
            for (Object row : page) {
                if (rows.size() < limit) {
                    rows.add(object(row));
                }
            }
            if (page.size() < count) {
                break;
            }
        }
        return rows;
    }

    static URI storeJson(RunContext context, Object value) throws Exception {
        var file = context.workingDir().createTempFile(".json");
        JacksonMapper.ofJson().writeValue(file.toFile(), value);
        return context.storage().putFile(file.toFile());
    }

    static URI storeRows(RunContext context, java.util.List<Map<String, Object>> rows) throws Exception {
        var file = context.workingDir().createTempFile(".ion");
        try (var writer = Files.newBufferedWriter(file)) {
            for (var row : rows) {
                writer.write(JacksonMapper.ofIon().writeValueAsString(row));
                writer.newLine();
            }
        }
        return context.storage().putFile(file.toFile());
    }

    @SuppressWarnings("unchecked")
    static <T> T redact(T value, boolean includeSecrets) {
        if (includeSecrets) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                String name = key.toString();
                result.put(name, sensitiveField(name) && entry != null ? REDACTED : redact(entry, false));
            });
            return (T) result;
        }
        if (value instanceof java.util.List<?> list) {
            return (T) list.stream().map(item -> redact(item, false)).toList();
        }
        if (value instanceof String text && text.matches("(?is).*https?://\\S*(?:webhook|hooks\\.slack\\.com/services|hooks\\.zapier\\.com|[?&](?:token|api_key|password|access_token)=|[^/\\s]+:[^/\\s]+@)\\S*.*")) {
            return (T) REDACTED;
        }
        return value;
    }

    private static boolean sensitiveField(String name) {
        String normalized = name.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
        return Set.of("apikey", "applicationkey", "appkey", "password", "token", "accesstoken", "secret", "webhook", "webhookurl").contains(normalized);
    }

    static boolean containsRedaction(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(Dashboards::containsRedaction);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().anyMatch(Dashboards::containsRedaction);
        }
        return REDACTED.equals(value);
    }

    /** Only widget envelope ids are generated. Ids inside definitions and tab references retain their meaning. */
    private static Object normalize(Object value, boolean widgets, boolean ignoreWidgetIds) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(widgets && ignoreWidgetIds && key.equals("id"))) {
                    result.put(key.toString(), normalize(item, key.equals("widgets"), ignoreWidgetIds));
                }
            });
            return result;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(item -> normalize(item, widgets, ignoreWidgetIds)).toList();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }

    static java.util.List<Change> diff(Map<String, Object> before, Map<String, Object> after, boolean includeSecrets) {
        boolean ignoreWidgetIds = !(after.get("tabs") instanceof java.util.List<?> tabs) || tabs.isEmpty();
        java.util.List<Change> changes = new ArrayList<>();
        compare("", normalize(before, false, ignoreWidgetIds), normalize(after, false, ignoreWidgetIds), changes, includeSecrets);
        return changes;
    }

    private static void compare(String path, Object before, Object after, java.util.List<Change> changes, boolean includeSecrets) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            Set<String> keys = new TreeSet<>();
            left.keySet().forEach(key -> keys.add(key.toString()));
            right.keySet().forEach(key -> keys.add(key.toString()));
            for (String key : keys) {
                compare(path + "/" + key.replace("~", "~0").replace("/", "~1"), left.get(key), right.get(key), changes, includeSecrets);
            }
        } else if (before instanceof java.util.List<?> left && after instanceof java.util.List<?> right) {
            for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
                compare(path + "/" + index, index < left.size() ? left.get(index) : null, index < right.size() ? right.get(index) : null, changes, includeSecrets);
            }
        } else {
            // Redact with the field name as well as its value so scalar password/token changes stay private.
            String field = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> oldField = new LinkedHashMap<>();
            Map<String, Object> newField = new LinkedHashMap<>();
            oldField.put(field, before);
            newField.put(field, after);
            boolean sensitivePath = !includeSecrets && java.util.Arrays.stream(path.split("/"))
                .map(segment -> segment.replace("~1", "/").replace("~0", "~")).anyMatch(Dashboards::sensitiveField);
            changes.add(Change.builder().path(path)
                .before(sensitivePath && before != null ? REDACTED : redact(oldField, includeSecrets).get(field))
                .after(sensitivePath && after != null ? REDACTED : redact(newField, includeSecrets).get(field)).build());
        }
    }

    @Builder
    @Getter
    public static class Change {
        @Schema(title = "JSON pointer of the changed field")
        private final String path;
        @Schema(title = "Previous value", description = "Null for an added field. Known credentials are redacted by default.")
        private final Object before;
        @Schema(title = "Desired value", description = "Null for a removed field. Known credentials are redacted by default.")
        private final Object after;
    }
}
