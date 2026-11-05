package io.kestra.plugin.datadog.api;

import io.kestra.core.serializers.JacksonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    private final Catalog.Operation op = Catalog.get().operation("ListRoles");

    @Test
    void primitiveBoundsArraysAndEnumsRejectInvalidValues() throws Exception {
        record Case(String schema, Object good, List<Object> bad) { }
        for (var c : List.of(
            new Case("{\"type\":\"integer\",\"minimum\":1,\"maximum\":3}", 2, List.of(0, 4, 1.5, "wrong")),
            new Case("{\"type\":\"number\",\"minimum\":1,\"maximum\":3}", 2.5, List.of(0, 4, "NaN")),
            new Case("{\"type\":\"boolean\"}", true, List.of("yes", 1)),
            new Case("{\"type\":\"string\",\"minLength\":1,\"maxLength\":3}", "a", List.of("", "four", 5)),
            new Case("{\"type\":\"string\",\"enum\":[\"ok\"]}", "ok", List.of("bad")),
            new Case("{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"integer\"}}", List.of(1), List.of("bad", List.of(), List.of(1, 2, 3), List.of("bad")))
        )) {
            var schema = JacksonMapper.ofJson().readTree(c.schema());
            assertDoesNotThrow(() -> RequestPlan.validate(op, schema, c.good(), "value"));
            c.bad().forEach(value -> assertThrows(IllegalArgumentException.class, () -> RequestPlan.validate(op, schema, value, "value")));
            assertThrows(IllegalArgumentException.class, () -> RequestPlan.validate(op, schema, null, "value"));
        }
    }

    @Test
    void queryArraysUseExplodeOrCommaAsSpecified() throws Exception {
        var template = "{\"operationId\":\"Fixture\",\"api\":\"v1\",\"method\":\"GET\",\"path\":\"/test\",\"parameters\":[{\"name\":\"tags\",\"in\":\"query\",\"type\":\"array\",\"items\":{\"type\":\"string\"},\"explode\":%s}]}";
        for (var explode : List.of(true, false)) {
            var fixture = new Catalog.Operation(JacksonMapper.ofJson().readTree(template.formatted(explode)));
            var plan = RequestPlan.prepare(fixture, null, "http://localhost", Map.of(), Map.of("tags", List.of("a b", "c")), Map.of(), null);
            assertEquals(explode ? "tags=a%20b&tags=c" : "tags=a%20b%2Cc", plan.uri().getRawQuery());
        }
    }

    @Test
    void pagingDetectionDistinguishesAllThreeStyles() {
        assertEquals(Paging.Style.CURSOR, Paging.detect(Catalog.get().operation("ListAuditLogs")).style());
        assertEquals(Paging.Style.OFFSET, Paging.detect(Catalog.get().operation("ListCatalogEntity")).style());
        assertEquals(Paging.Style.NUMBER, Paging.detect(op).style());
        assertNull(Paging.detect(Catalog.get().operation("GetMonitor")));
    }
}
