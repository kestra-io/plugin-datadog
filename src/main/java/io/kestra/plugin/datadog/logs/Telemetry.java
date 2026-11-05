package io.kestra.plugin.datadog.logs;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared wire helpers for the telemetry endpoints. */
public final class Telemetry {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private Telemetry() {
    }

    public static String timestamp(String field, String value) {
        if (value != null && (value.matches("now(?:[+-][0-9]+(?:ms|s|m|h|d|w|M|y))?(?:/[smhdwMy])?") || value.matches("[0-9]{1,17}"))) {
            return value;
        }
        try {
            OffsetDateTime.parse(value == null ? "" : value);
            return value;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The Datadog `" + field + "` must be an ISO-8601 instant, epoch milliseconds or a relative time such as now-15m");
        }
    }

    public static int limit(int value) {
        if (value < 1 || value > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxResults` must be between 1 and 10000");
        }
        return value;
    }

    public static Map<String, Object> envelope(Map<String, Object> attributes, String resourceType) {
        return Map.of("data", Map.of("type", resourceType, "attributes", attributes));
    }

    public static void complete(Map<String, Object> answer) {
        if (answer.get("meta") instanceof Map<?, ?> meta) {
            if (meta.get("status") != null && !"done".equals(meta.get("status"))) {
                throw new IllegalStateException("Datadog returned incomplete telemetry results (meta.status)");
            }
            if (meta.get("warnings") instanceof List<?> warnings && !warnings.isEmpty()) {
                throw new IllegalStateException("Datadog returned telemetry warnings; refusing incomplete results");
            }
        }
    }

    public static String cursor(Map<String, Object> answer) {
        if (answer.get("meta") instanceof Map<?, ?> meta && meta.get("page") instanceof Map<?, ?> page && page.get("after") instanceof String value && !value.isBlank()) {
            return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Datadog returned an unexpected telemetry response: an array was expected");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>)) {
                throw new IllegalStateException("Datadog returned a telemetry row that is not an object");
            }
            rows.add((Map<String, Object>) row);
        }
        return rows;
    }

    public static List<Map<String, Object>> buckets(Map<String, Object> answer, boolean spans) {
        if (spans) {
            return rows(answer.get("data")).stream().map(row -> {
                if (!(row.get("attributes") instanceof Map<?, ?> attributes)) {
                    throw new IllegalStateException("Datadog returned a span bucket without attributes");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                attributes.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }).toList();
        }
        if (!(answer.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Datadog returned an aggregate without data");
        }
        return rows(data.get("buckets"));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        extra.forEach((key, value) -> {
            if (out.get(key) instanceof Map<?, ?> left && value instanceof Map<?, ?> right) {
                out.put(key, merge((Map<String, Object>) left, (Map<String, Object>) right));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    /** Redacts credential fields and URLs before results reach outputs or storage. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> row, boolean includeSecrets) {
        return includeSecrets ? row : (Map<String, Object>) redactValue(row);
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                boolean sensitive = normalized.endsWith("apikey") || normalized.endsWith("appkey") || normalized.endsWith("applicationkey")
                    || normalized.endsWith("password") || normalized.endsWith("secret") || normalized.endsWith("token")
                    || normalized.endsWith("webhookurl") || normalized.equals("webhook") || normalized.endsWith("privatekey");
                out.put(name, sensitive ? "[REDACTED]" : redactValue(item));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Telemetry::redactValue).toList();
        }
        return value instanceof String text ? URL.matcher(text).replaceAll("[REDACTED_URL]") : value;
    }

    /** Streams rows to a temporary ION file and publishes it only on success. */
    public static final class Sink implements AutoCloseable {
        private final RunContext context;
        private final List<Map<String, Object>> rows;
        private final Path path;
        private final OutputStream writer;
        private int size;

        public Sink(RunContext context, boolean store) throws Exception {
            this.context = context;
            rows = store ? null : new ArrayList<>();
            path = store ? context.workingDir().createTempFile(".ion") : null;
            writer = store ? new BufferedOutputStream(Files.newOutputStream(path)) : null;
        }

        public void add(Map<String, Object> row) throws Exception {
            if (writer != null) {
                FileSerde.write(writer, row);
            } else {
                rows.add(row);
            }
            size++;
        }

        public int size() {
            return size;
        }

        public List<Map<String, Object>> rows() {
            return rows;
        }

        public URI uri() throws Exception {
            if (writer == null) {
                return null;
            }
            writer.flush();
            return context.storage().putFile(path.toFile());
        }

        @Override
        public void close() throws Exception {
            if (writer != null) {
                writer.close();
                Files.deleteIfExists(path);
            }
        }
    }
}
