package io.kestra.plugin.datadog.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.MockDatadogServer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline contract assertions backed by reference-preserving OpenAPI schemas. */
public final class OpenApiContract {
    private final String id;
    private final JsonNode operation;
    private final SchemaValidator validator;

    private OpenApiContract(String id, JsonNode document) {
        this.id = id;
        operation = document.path("operations").path(id);
        validator = new SchemaValidator(document);
        require(!operation.isMissingNode(), "operation is absent from bundle");
    }

    public static OpenApiContract of(String id) {
        JsonNode index = resource("/openapi/index.json");
        String qualified = id;
        if (!index.has(qualified)) {
            List<String> matches = new ArrayList<>();
            index.fieldNames().forEachRemaining(key -> { if (key.endsWith(":" + id)) { matches.add(key); } });
            if (matches.size() != 1) { throw new AssertionError("Unknown or ambiguous vendored operation " + id); }
            qualified = matches.getFirst();
        }
        return new OpenApiContract(qualified, resource("/openapi/" + index.path(qualified).asText()));
    }

    public static void assertRequests(List<MockDatadogServer.Received> requests, String... operationIds) {
        List<OpenApiContract> contracts = java.util.Arrays.stream(operationIds).map(OpenApiContract::of).toList();
        for (var request : requests) {
            OpenApiContract match = null;
            for (var contract : contracts) {
                if (request.method().equals(contract.operation.path("method").asText()) &&
                    pathPattern(contract.operation.path("path").asText()).matcher(request.path()).matches()) { match = contract; break; }
            }
            if (match == null) { throw new AssertionError("No declared contract matches " + request.method() + " " + request.path()); }
            match.assertRequest(request);
        }
    }

    private static JsonNode resource(String name) {
        try (InputStream input = OpenApiContract.class.getResourceAsStream(name)) {
            if (input == null) { throw new AssertionError("Missing contract resource " + name); }
            return JacksonMapper.ofJson().readTree(input);
        } catch (java.io.IOException e) { throw new AssertionError("Cannot read contract resource " + name, e); }
    }

    public void assertRequest(MockDatadogServer.Received request) {
        require(operation.path("method").asText().equals(request.method()), "request method differs");
        String template = operation.path("path").asText();
        var actual = pathPattern(template).matcher(request.path());
        require(actual.matches(), "request path differs");
        Map<String, List<String>> path = new HashMap<>();
        var placeholders = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(template);
        int index = 1;
        while (placeholders.find()) {
            String value = actual.group(index++);
            require(!value.isBlank(), "empty path parameter");
            path.put(placeholders.group(1), List.of(value));
        }
        Map<String, List<String>> query = query(request.query());
        for (String name : query.keySet()) {
            boolean known = false;
            for (JsonNode p : operation.path("parameters")) {
                if (p.path("in").asText().equals("query") && p.path("name").asText().equals(name)) { known = true; }
            }
            require(known, "unknown query parameter " + name);
        }
        for (JsonNode p : operation.path("parameters")) {
            String name = p.path("name").asText();
            String location = p.path("in").asText();
            List<String> values = switch (location) {
                case "path" -> path.get(name);
                case "query" -> query.get(name);
                case "header" -> request.header(name) == null ? null : List.of(request.header(name));
                default -> throw new AssertionError("Unsupported parameter location " + location);
            };
            if (values == null) { require(!p.path("required").asBoolean(), "missing required " + location + " parameter " + name); continue; }
            JsonNode schema = validator.resolve(p.path("schema"));
            JsonNode value;
            if (schema.path("type").asText().equals("array")) {
                ArrayNode array = JacksonMapper.ofJson().createArrayNode();
                for (String raw : values) {
                    for (String part : p.path("explode").asBoolean() ? new String[]{raw} : raw.split(",", -1)) { array.add(parameter(schema.path("items"), part)); }
                }
                value = array;
            } else {
                require(values.size() == 1, "duplicate scalar parameter " + name);
                value = parameter(schema, values.getFirst());
            }
            validate(schema, value, location + "/" + name, true);
        }
        boolean present = request.body() != null && !request.body().isBlank();
        JsonNode body = operation.path("request");
        require(present || !body.path("required").asBoolean(), "missing required request body");
        if (present) {
            JsonNode content = body.path("content");
            String media = media(content, request.header("Content-Type"));
            require(media != null, "request Content-Type is missing or not declared");
            require(media.contains("json"), "contract helper supports JSON request bodies only");
            validate(content.path(media).path("schema"), json(request.body()), "request", true);
        }
    }

    public void assertResponse(int status, String body) { assertResponse(status, "application/json", body); }

    public void assertResponse(int status, String contentType, String body) {
        JsonNode response = operation.path("responses").path(String.valueOf(status));
        require(!response.isMissingNode(), "response status " + status + " is not a declared success");
        JsonNode content = response.path("content");
        if (content.isEmpty()) { require(body == null || body.isBlank(), "body on a no-content response"); return; }
        String media = media(content, contentType);
        require(media != null, "response Content-Type is not declared");
        require(body != null && !body.isBlank(), "response body is missing");
        require(media.contains("json"), "contract helper supports JSON response bodies only");
        validate(content.path(media).path("schema"), json(body), "response", false);
    }

    private static String media(JsonNode content, String type) {
        if (type == null) { return null; }
        var names = content.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (type.equals(name) || type.startsWith(name + ";")) { return name; }
        }
        return null;
    }

    private JsonNode json(String raw) {
        try { return JacksonMapper.ofJson().readTree(raw); }
        catch (java.io.IOException e) { throw new AssertionError(id + ": invalid JSON"); }
    }

    private JsonNode parameter(JsonNode rawSchema, String value) {
        JsonNode schema = validator.resolve(rawSchema);
        return switch (schema.path("type").asText()) {
            case "integer", "number" -> {
                try { yield DecimalNode.valueOf(new BigDecimal(value)); }
                catch (NumberFormatException e) { throw new AssertionError(id + ": parameter is not numeric"); }
            }
            case "boolean" -> { require(value.equals("true") || value.equals("false"), "parameter is not boolean"); yield BooleanNode.valueOf(Boolean.parseBoolean(value)); }
            default -> TextNode.valueOf(value);
        };
    }

    private void validate(JsonNode schema, JsonNode value, String path, boolean request) {
        try { validator.validate(schema, value, path, request); }
        catch (AssertionError e) { throw new AssertionError(id + ": " + e.getMessage()); }
    }

    private void require(boolean condition, String message) { SchemaValidator.require(condition, id + ": " + message); }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> values = new HashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split("&")) {
                String[] entry = pair.split("=", 2);
                String name = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                String value = entry.length == 2 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "";
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        return values;
    }

    private static java.util.regex.Pattern pathPattern(String template) {
        var placeholders = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(template);
        StringBuilder regex = new StringBuilder("^");
        int start = 0;
        while (placeholders.find()) {
            regex.append(java.util.regex.Pattern.quote(template.substring(start, placeholders.start()))).append("(.+?)");
            start = placeholders.end();
        }
        regex.append(java.util.regex.Pattern.quote(template.substring(start))).append('$');
        return java.util.regex.Pattern.compile(regex.toString());
    }
}
