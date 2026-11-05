package io.kestra.plugin.datadog.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest {
    @TestFactory
    Stream<DynamicTest> everyCatalogOperationBuildsItsDeclaredMethodAndPath() {
        return Catalog.get().operations().stream().map(op -> DynamicTest.dynamicTest(op.key(), () -> {
            Map<String, Object> paths = new LinkedHashMap<>();
            Map<String, Object> query = new LinkedHashMap<>();
            Map<String, String> headers = new LinkedHashMap<>();
            String expected = op.path();
            for (JsonNode p : op.parameters()) {
                if (!p.path("required").asBoolean()) { continue; }
                Object value = minimum(p);
                String name = p.path("name").asText();
                switch (p.path("in").asText()) {
                    case "path" -> { paths.put(name, value); expected = expected.replace("{" + name + "}", String.valueOf(value)); }
                    case "query" -> query.put(name, value);
                    case "header" -> headers.put(name, String.valueOf(value));
                    default -> fail("Unhandled parameter location");
                }
            }
            RequestPlan plan = RequestPlan.prepare(op, "datadoghq.eu", null, paths, query, headers, op.bodyRequired() ? Map.of() : null);
            assertEquals(op.method(), plan.method());
            assertEquals(expected, URLDecoder.decode(plan.uri().getRawPath(), StandardCharsets.UTF_8));
            assertEquals("https", plan.uri().getScheme());
            assertNotNull(plan.uri().getHost());
            assertFalse(plan.uri().toString().contains("{"));
            assertEquals(op.key(), Catalog.get().operation(op.key()).key());
        }));
    }

    static Object minimum(JsonNode schema) {
        if (schema.has("enum")) { return scalar(schema.path("enum").get(0)); }
        if (schema.has("default")) { return scalar(schema.get("default")); }
        return switch (schema.path("type").asText()) {
            case "integer" -> Math.max(1, schema.path("minimum").asLong(1));
            case "number" -> Math.max(1, schema.path("minimum").asDouble(1));
            case "boolean" -> true;
            case "array" -> List.of(minimum(schema.path("items")));
            default -> "a".repeat(Math.max(1, schema.path("minLength").asInt(1)));
        };
    }

    private static Object scalar(JsonNode node) {
        if (node.isNumber()) { return node.numberValue(); }
        if (node.isBoolean()) { return node.booleanValue(); }
        return node.asText();
    }

    @Test
    void allVersionCollisionsRequireQualification() {
        var groups = Catalog.get().operations().stream().collect(java.util.stream.Collectors.groupingBy(Catalog.Operation::id));
        groups.forEach((id, rows) -> {
            if (rows.size() > 1) { assertThrows(IllegalArgumentException.class, () -> Catalog.get().operation(id)); }
            else { assertEquals(rows.getFirst(), Catalog.get().operation(id)); }
        });
        assertThrows(IllegalArgumentException.class, () -> Catalog.get().operation(""));
        assertThrows(IllegalArgumentException.class, () -> Catalog.get().operation("NoSuchOperation"));
    }

    @Test
    void serverOverridesAndEncodingArePreserved() {
        assertEquals("https://http-intake.logs.us3.datadoghq.com", RequestPlan.base(Catalog.get().operation("v2:SubmitLog"), "us3.datadoghq.com", null));
        var op = Catalog.get().operation("v2:GetUser");
        var plan = RequestPlan.prepare(op, null, "http://localhost:9000/proxy/", Map.of("user_id", "a/b +?"), Map.of(), Map.of(), null);
        assertEquals("/proxy/api/v2/users/a%2Fb%20%2B%3F", plan.uri().getRawPath());
    }
}
