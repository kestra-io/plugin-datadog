package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared validation and response handling for organization workflows. Never echoes response bodies in errors. */
public final class AccessApi {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private AccessApi() {
    }

    public static String required(RunContext context, Property<String> property, String name) throws Exception {
        return required(context.render(property).as(String.class).orElse(null), name);
    }

    public static String required(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("`" + name + "` must be a non-blank string");
        }
        return text.trim();
    }

    public static String segment(String value) {
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("An object identifier cannot be a dot path segment");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static int range(RunContext context, Property<Integer> property, int fallback, int max, String name) throws Exception {
        int value = context.render(property).as(Integer.class).orElse(fallback);
        if (value < 1 || value > max) {
            throw new IllegalArgumentException("`" + name + "` must be between 1 and " + max);
        }
        return value;
    }

    public static boolean flag(RunContext context, Property<Boolean> property) throws Exception {
        return context.render(property).as(Boolean.class).orElse(false);
    }

    /** Preserve null versus an explicit empty list for destructive reconciliation inputs. */
    public static List<String> explicitList(RunContext context, Property<List<String>> property, String name) throws Exception {
        if (property == null) {
            throw new IllegalArgumentException("`" + name + "` must be an explicit list");
        }
        List<String> values;
        try {
            // RunContextProperty.asList replaces a rendered null with an empty list.
            // Use the underlying renderer here so null cannot request removal of every member.
            values = Property.asList(property, context, String.class);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("`" + name + "` must render to an explicit list");
        }
        if (values == null) {
            throw new IllegalArgumentException("`" + name + "` must render to an explicit list");
        }
        return values;
    }

    public static DatadogClient.Response request(DatadogClient client, String method, String path, Map<String, ?> query, Object body) throws Exception {
        DatadogClient.Response response;
        try {
            response = client.exchange(method, client.apiBase(), path, query, body, false);
        } catch (Exception e) {
            // Transport exception causes can include headers or a response containing a newly created key.
            throw new IllegalStateException("Datadog organization request could not be completed");
        }
        if (!response.ok()) {
            throw new DatadogClient.DatadogException(response.status(), "Datadog answered with HTTP " + response.status());
        }
        return response;
    }

    public static Map<String, Object> json(DatadogClient.Response response) {
        try {
            return response.map();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Datadog returned an invalid JSON object");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Datadog returned an invalid resource object");
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> optionalObject(Object value) {
        return value == null ? Map.of() : object(value);
    }

    public static List<Map<String, Object>> rows(Map<String, Object> response) {
        if (!(response.get("data") instanceof List<?> values)) {
            throw new IllegalStateException("Datadog returned an invalid resource list");
        }
        return values.stream().map(AccessApi::object).toList();
    }

    public static boolean delete(DatadogClient client, String path, boolean ignoreNotFound) throws Exception {
        try {
            request(client, "DELETE", path, Map.of(), null);
            return true;
        } catch (DatadogClient.DatadogException e) {
            if (ignoreNotFound && e.getStatus() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Redacts credential fields recursively before output or storage. URL fields are redacted conservatively. */
    public static Object redact(Object value, boolean includeSecrets) {
        if (includeSecrets) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                boolean sensitive = normalized.equals("key") || normalized.endsWith("apikey") || normalized.endsWith("appkey")
                    || normalized.contains("secret") || normalized.contains("password") || normalized.contains("token")
                    || normalized.contains("credential") || normalized.contains("authorization") || normalized.contains("webhook")
                    || normalized.endsWith("url") || normalized.equals("href");
                safe.put(name, sensitive && item != null ? "[REDACTED]" : redact(item, false));
            });
            return safe;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> redact(item, false)).toList();
        }
        return value instanceof String text ? URL.matcher(text).replaceAll("[REDACTED_URL]") : value;
    }

    public record Page(List<Map<String, Object>> rows, boolean truncated) {
    }

    /** Number-based JSON:API paging. Uses totals when present and refuses repeated resource IDs. */
    public static Page collect(DatadogClient client, String path, Map<String, Object> query, int pageSize, int maxResults) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(query);
        params.put("page[size]", pageSize);
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int page = 0; ; page++) {
            params.put("page[number]", page);
            Map<String, Object> response = json(request(client, "GET", path, params, null));
            List<Map<String, Object>> rows = rows(response);
            for (Map<String, Object> row : rows) {
                String id = required(row.get("id"), "response id");
                if (!ids.add(id)) {
                    throw new IllegalStateException("Datadog pagination repeated a resource; no complete snapshot is available");
                }
                if (result.size() < maxResults) {
                    result.add(row);
                }
            }
            Map<String, Object> meta = optionalObject(response.get("meta"));
            Object total = optionalObject(meta.get("page")).get("total_filtered_count");
            if (total == null) {
                total = optionalObject(meta.get("pagination")).get("total");
            }
            boolean more = total instanceof Number count ? ids.size() < count.longValue() : rows.size() >= pageSize;
            if (rows.isEmpty() && more) {
                throw new IllegalStateException("Datadog pagination ended before its total count");
            }
            if (!more || result.size() >= maxResults) {
                return new Page(result, ids.size() > maxResults || more);
            }
        }
    }
}
