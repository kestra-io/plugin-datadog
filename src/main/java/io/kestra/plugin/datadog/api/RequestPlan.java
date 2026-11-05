package io.kestra.plugin.datadog.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure request validation and serialization. No network or credential access. */
public record RequestPlan(String method, URI uri, Map<String, String> headers, String contentType) {
    public static RequestPlan prepare(Catalog.Operation op, String site, String apiUrl,
                                      Map<String, Object> pathParams, Map<String, Object> query,
                                      Map<String, String> headers, Object body) {
        var normalized = new LinkedHashMap<String, String>();
        headers.forEach((name, value) -> {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null || value.contains("\r") || value.contains("\n")) {
                throw op.invalid("invalid header " + name);
            }
            var lower = name.toLowerCase(Locale.ROOT);
            if (List.of("dd-api-key", "dd-application-key", "host", "content-length", "transfer-encoding").contains(lower)) {
                throw op.invalid("header " + name + " is managed by the connection");
            }
            if (normalized.put(lower, value) != null) {
                throw op.invalid("duplicate header " + name);
            }
        });
        for (var name : pathParams.keySet()) {
            if (op.parameter("path", name) == null) { throw op.invalid("unknown path parameter " + name); }
        }
        for (var name : query.keySet()) {
            if (op.parameter("query", name) == null) { throw op.invalid("unknown query parameter " + name); }
        }
        var path = op.path();
        var pairs = new ArrayList<String>();
        for (var p : op.parameters()) {
            var name = p.path("name").asText();
            var location = p.path("in").asText();
            var value = switch (location) {
                case "path" -> pathParams.get(name);
                case "query" -> query.get(name);
                case "header" -> normalized.get(name.toLowerCase(Locale.ROOT));
                default -> throw op.invalid("unsupported parameter location " + location);
            };
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (p.path("required").asBoolean()) { throw op.invalid("missing required " + location + " parameter " + name); }
                if (value == null) { continue; }
            }
            validate(op, p, value, location + " parameter " + name);
            if (location.equals("path")) {
                var segment = String.valueOf(value);
                if (segment.equals(".") || segment.equals("..")) { throw op.invalid("invalid path parameter " + name); }
                path = path.replace("{" + name + "}", encode(segment));
            } else if (location.equals("query")) {
                if (value instanceof List<?> values) {
                    if (p.path("explode").asBoolean()) {
                        values.forEach(v -> pairs.add(encode(name) + "=" + encode(String.valueOf(v))));
                    } else {
                        pairs.add(encode(name) + "=" + encode(String.join(",", values.stream().map(String::valueOf).toList())));
                    }
                } else {
                    pairs.add(encode(name) + "=" + encode(String.valueOf(value)));
                }
            }
        }
        if (op.bodyRequired() && (body == null || (body instanceof String s && s.isBlank()))) {
            throw op.invalid("missing required body");
        }
        var contentType = normalized.get("content-type");
        var types = op.definition().path("contentTypes");
        if (body != null) {
            if (types.isEmpty()) { throw op.invalid("this operation has no request body"); }
            if (contentType == null) {
                contentType = types.get(0).asText();
                for (var t : types) { if (t.asText().equals("application/json")) { contentType = t.asText(); } }
            }
            var allowed = false;
            for (var t : types) {
                if (contentType.equals(t.asText()) || contentType.startsWith(t.asText() + ";")) { allowed = true; }
            }
            if (!allowed) { throw op.invalid("unsupported Content-Type"); }
        }
        return new RequestPlan(op.method(), URI.create(base(op, site, apiUrl) + path +
            (pairs.isEmpty() ? "" : "?" + String.join("&", pairs))), normalized, contentType);
    }

    public static void validate(Catalog.Operation op, JsonNode schema, Object value, String label) {
        if (value == null) { throw op.invalid(label + " must not be null"); }
        var type = schema.path("type").asText("string");
        var text = String.valueOf(value);
        try {
            switch (type) {
                case "integer" -> {
                    var number = new BigDecimal(text);
                    number.toBigIntegerExact();
                    range(op, schema, number, label);
                }
                case "number" -> range(op, schema, new BigDecimal(text), label);
                case "boolean" -> { if (!text.equals("true") && !text.equals("false")) { throw op.invalid(label + " must be boolean"); } }
                case "array" -> {
                    if (!(value instanceof List<?> values)) { throw op.invalid(label + " must be an array"); }
                    if (schema.has("minItems") && values.size() < schema.get("minItems").asInt() ||
                        schema.has("maxItems") && values.size() > schema.get("maxItems").asInt()) { throw op.invalid(label + " has an invalid item count"); }
                    for (var entry : values) { validate(op, schema.path("items"), entry, label + " item"); }
                }
                default -> {
                    if (!(value instanceof String)) { throw op.invalid(label + " must be a string"); }
                    if (schema.has("minLength") && text.length() < schema.get("minLength").asInt() ||
                        schema.has("maxLength") && text.length() > schema.get("maxLength").asInt()) { throw op.invalid(label + " has an invalid length"); }
                }
            }
        } catch (NumberFormatException | ArithmeticException e) {
            throw op.invalid(label + " must be " + type);
        }
        if (schema.has("enum")) {
            var allowed = false;
            for (var choice : schema.get("enum")) { if (choice.asText().equals(text)) { allowed = true; } }
            if (!allowed) { throw op.invalid(label + " is not an allowed enum value"); }
        }
    }

    private static void range(Catalog.Operation op, JsonNode schema, BigDecimal value, String label) {
        if (schema.has("minimum") && value.compareTo(schema.get("minimum").decimalValue()) < 0 ||
            schema.has("maximum") && value.compareTo(schema.get("maximum").decimalValue()) > 0) {
            throw op.invalid(label + " is outside its allowed range");
        }
    }

    public static String base(Catalog.Operation op, String site, String override) {
        var domain = site == null || site.isBlank() ? "datadoghq.com" : site.trim();
        if (!domain.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")) { throw op.invalid("site must be a domain name"); }
        String base;
        if (override != null && !override.isBlank()) {
            base = override.trim();
        } else {
            var server = op.definition().path("servers").path(0);
            base = server.path("url").asText("https://api.{site}");
            var variables = server.path("variables").fields();
            while (variables.hasNext()) {
                var variable = variables.next();
                var value = variable.getValue().path("default").asText();
                if (variable.getKey().equals("site")) {
                    // A server can use a full intake host as the site default.
                    value = value.endsWith(".datadoghq.com") ? value.substring(0, value.length() - "datadoghq.com".length()) + domain : domain;
                }
                base = base.replace("{" + variable.getKey() + "}", value);
            }
            base = base.replace("{site}", domain);
        }
        URI uri;
        try { uri = URI.create(base); } catch (IllegalArgumentException e) { throw op.invalid("invalid API base URL"); }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null ||
            uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw op.invalid("API base URL must be http or https without credentials, query or fragment");
        }
        return base.replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
