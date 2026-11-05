package io.kestra.plugin.datadog.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.MockDatadogServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
        var index = resource("/openapi/index.json");
        var qualified = id;
        if (!index.has(qualified)) {
            var matches = new ArrayList<String>();
            index.fieldNames().forEachRemaining(key -> { if (key.endsWith(":" + id)) { matches.add(key); } });
            if (matches.size() != 1) { throw new AssertionError("Unknown or ambiguous vendored operation " + id); }
            qualified = matches.getFirst();
        }
        return new OpenApiContract(qualified, resource("/openapi/" + index.path(qualified).asText()));
    }

    public static void assertRequests(List<MockDatadogServer.Received> requests, String... operationIds) {
        var contracts = Arrays.stream(operationIds).map(OpenApiContract::of).toList();
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
        try (var input = OpenApiContract.class.getResourceAsStream(name)) {
            if (input == null) { throw new AssertionError("Missing contract resource " + name); }
            return JacksonMapper.ofJson().readTree(input);
        } catch (IOException e) { throw new AssertionError("Cannot read contract resource " + name, e); }
    }

    public void assertRequest(MockDatadogServer.Received request) {
        require(operation.path("method").asText().equals(request.method()), "request method differs");
        var template = operation.path("path").asText();
        var actual = pathPattern(template).matcher(request.path());
        require(actual.matches(), "request path differs");
        var path = new HashMap<String, List<String>>();
        var placeholders = Pattern.compile("\\{([^}]+)}").matcher(template);
        var index = 1;
        while (placeholders.find()) {
            var value = actual.group(index++);
            require(!value.isBlank(), "empty path parameter");
            path.put(placeholders.group(1), List.of(value));
        }
        var query = query(request.query());
        for (var name : query.keySet()) {
            var known = false;
            for (var p : operation.path("parameters")) {
                if (p.path("in").asText().equals("query") && p.path("name").asText().equals(name)) { known = true; }
            }
            require(known, "unknown query parameter " + name);
        }
        for (var p : operation.path("parameters")) {
            var name = p.path("name").asText();
            var location = p.path("in").asText();
            var values = switch (location) {
                case "path" -> path.get(name);
                case "query" -> query.get(name);
                case "header" -> request.header(name) == null ? null : List.of(request.header(name));
                default -> throw new AssertionError("Unsupported parameter location " + location);
            };
            if (values == null) { require(!p.path("required").asBoolean(), "missing required " + location + " parameter " + name); continue; }
            var schema = validator.resolve(p.path("schema"));
            JsonNode value;
            if (schema.path("type").asText().equals("array")) {
                var array = JacksonMapper.ofJson().createArrayNode();
                for (var raw : values) {
                    for (var part : p.path("explode").asBoolean() ? new String[]{raw} : raw.split(",", -1)) { array.add(parameter(schema.path("items"), part)); }
                }
                value = array;
            } else {
                require(values.size() == 1, "duplicate scalar parameter " + name);
                value = parameter(schema, values.getFirst());
            }
            validate(schema, value, location + "/" + name, true);
        }
        var present = request.body() != null && !request.body().isBlank();
        var body = operation.path("request");
        require(present || !body.path("required").asBoolean(), "missing required request body");
        if (present) {
            var content = body.path("content");
            var media = media(content, request.header("Content-Type"));
            require(media != null, "request Content-Type is missing or not declared");
            require(media.contains("json"), "contract helper supports JSON request bodies only");
            validate(content.path(media).path("schema"), json(request.body()), "request", true);
        }
    }

    public void assertResponse(int status, String body) { assertResponse(status, "application/json", body); }

    public void assertResponse(int status, String contentType, String body) {
        var response = operation.path("responses").path(String.valueOf(status));
        require(!response.isMissingNode(), "response status " + status + " is not a declared success");
        var content = response.path("content");
        if (content.isEmpty()) { require(body == null || body.isBlank(), "body on a no-content response"); return; }
        var media = media(content, contentType);
        require(media != null, "response Content-Type is not declared");
        require(body != null && !body.isBlank(), "response body is missing");
        require(media.contains("json"), "contract helper supports JSON response bodies only");
        validate(content.path(media).path("schema"), json(body), "response", false);
    }

    private static String media(JsonNode content, String type) {
        if (type == null) { return null; }
        var names = content.fieldNames();
        while (names.hasNext()) {
            var name = names.next();
            if (type.equals(name) || type.startsWith(name + ";")) { return name; }
        }
        return null;
    }

    private JsonNode json(String raw) {
        try { return JacksonMapper.ofJson().readTree(raw); }
        catch (IOException e) { throw new AssertionError(id + ": invalid JSON"); }
    }

    private JsonNode parameter(JsonNode rawSchema, String value) {
        var schema = validator.resolve(rawSchema);
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
        var values = new HashMap<String, List<String>>();
        if (raw != null && !raw.isBlank()) {
            for (var pair : raw.split("&")) {
                var entry = pair.split("=", 2);
                var name = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                var value = entry.length == 2 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "";
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        return values;
    }

    private static Pattern pathPattern(String template) {
        var placeholders = Pattern.compile("\\{[^}]+}").matcher(template);
        var regex = new StringBuilder("^");
        var start = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(template.substring(start, placeholders.start()))).append("(.+?)");
            start = placeholders.end();
        }
        regex.append(Pattern.quote(template.substring(start))).append('$');
        return Pattern.compile(regex.toString());
    }
}
