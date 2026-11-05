package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared request and output handling for the incident response packages. */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class ResponseSupport extends AbstractDatadogTask {
    @Schema(title = "Include sensitive response fields", description = "Returns structured key, token, password and webhook fields when true. False by default.")
    private Property<Boolean> includeSecrets;

    protected String required(RunContext context, Property<String> property, String name) throws Exception {
        String value = context.render(property).as(String.class).orElse("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("`" + name + "` must not be blank");
        }
        return value;
    }

    protected String identifier(RunContext context, Property<String> property, String name) throws Exception {
        String value = required(context, property, name);
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("`" + name + "` must be a single identifier without path or query characters");
        }
        return value;
    }

    protected <T> T required(RunContext context, Property<T> property, Class<T> type, String name) throws Exception {
        return context.render(property).as(type).orElseThrow(() -> new IllegalArgumentException("`" + name + "` is required"));
    }

    protected String uuid(RunContext context, Property<String> property, String name) throws Exception {
        String value = required(context, property, name);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("`" + name + "` must be a UUID");
        }
        return value;
    }

    protected boolean dryRun(RunContext context, Property<Boolean> property) throws Exception {
        return context.render(property).as(Boolean.class).orElse(true);
    }

    protected static Map<String, Object> resource(String type, String id, Map<String, Object> attributes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        if (id != null) {
            data.put("id", id);
        }
        if (attributes != null) {
            data.put("attributes", attributes);
        }
        return data;
    }

    protected ResponseOutput send(RunContext context, String method, String path, Map<String, Object> query,
                                  Map<String, Object> body, boolean preview) throws Exception {
        try (var client = client(context, true)) {
            return send(context, client, client.apiBase(), method, path, query, body, preview);
        }
    }

    protected ResponseOutput send(RunContext context, DatadogClient client, String base, String method, String path,
                                  Map<String, Object> query, Map<String, Object> body, boolean preview) throws Exception {
        if (preview) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("method", method);
            request.put("url", base + path);
            request.put("query", query);
            if (body != null) {
                request.put("body", body);
            }
            return ResponseOutput.builder().dryRun(true).request(safe(context, request)).build();
        }
        DatadogClient.Response response = client.exchange(method, base, path, query, body, true);
        Map<String, Object> answer = safe(context, response.map());
        Object data = answer.get("data");
        String id = data instanceof Map<?, ?> map && map.get("id") != null ? String.valueOf(map.get("id")) : null;
        return ResponseOutput.builder().id(id).statusCode(response.status()).dryRun(false).body(answer).build();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> safe(RunContext context, Map<String, Object> value) throws Exception {
        return context.render(includeSecrets).as(Boolean.class).orElse(false) ? value : (Map<String, Object>) redact(value);
    }

    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = key.toString();
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                boolean sensitive = normalized.contains("password") || normalized.contains("secret")
                    || normalized.contains("token") || normalized.equals("apikey") || normalized.equals("appkey")
                    || normalized.equals("applicationkey") || normalized.contains("webhook");
                result.put(name, sensitive ? "[REDACTED]" : redact(child));
            });
            return result;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(ResponseSupport::redact).toList();
        }
        return io.kestra.plugin.datadog.monitors.MonitorIO.redact(value, false);
    }
}
