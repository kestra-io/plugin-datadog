package io.kestra.plugin.datadog.contract;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiContractTest {
    private MockDatadogServer.Received request(String method, String path, String query, String body) {
        return new MockDatadogServer.Received(method, path, query, Map.of("content-type", "application/json"), body);
    }

    @Test
    void requestContractRejectsWrongMethodPathMissingQueryAndBodyFields() {
        var canDelete = OpenApiContract.of("v1:CheckCanDeleteMonitor");
        canDelete.assertRequest(request("GET", "/api/v1/monitor/can_delete", "monitor_ids=1%2C2", ""));
        assertThrows(AssertionError.class, () -> canDelete.assertRequest(request("POST", "/api/v1/monitor/can_delete", "monitor_ids=1", "")));
        assertThrows(AssertionError.class, () -> canDelete.assertRequest(request("GET", "/api/v1/wrong", "monitor_ids=1", "")));
        assertThrows(AssertionError.class, () -> canDelete.assertRequest(request("GET", "/api/v1/monitor/can_delete", "", "")));
        assertThrows(AssertionError.class, () -> canDelete.assertRequest(request("GET", "/api/v1/monitor/can_delete", "monitor_ids=1&unknown=y", "")));
        assertThrows(AssertionError.class, () -> canDelete.assertRequest(request("GET", "/api/v1/monitor/can_delete", "monitor_ids=bad", "")));
        var create = OpenApiContract.of("v1:CreateMonitor");
        create.assertRequest(request("POST", "/api/v1/monitor", null, "{\"type\":\"metric alert\",\"query\":\"avg(last_5m):avg:system.cpu.user{*} > 1\"}"));
        assertThrows(AssertionError.class, () -> create.assertRequest(request("POST", "/api/v1/monitor", null, "{}")));
        assertThrows(AssertionError.class, () -> create.assertRequest(request("POST", "/api/v1/monitor", null, "")));
        assertThrows(AssertionError.class, () -> create.assertRequest(request("POST", "/api/v1/monitor", null, "{\"type\":1,\"query\":\"x\"}")));
        assertThrows(AssertionError.class, () -> OpenApiContract.of("missing"));
    }

    @Test
    void responseContractChecksStatusRequiredFieldsAndTypes() {
        var list = OpenApiContract.of("v1:ListMonitors");
        list.assertResponse(200, "[{\"id\":1,\"name\":\"cpu\",\"type\":\"metric alert\",\"query\":\"q\"}]");
        assertThrows(AssertionError.class, () -> list.assertResponse(201, "[]"));
        assertThrows(AssertionError.class, () -> list.assertResponse(200, "{}"));
        assertThrows(AssertionError.class, () -> list.assertResponse(200, "[{\"id\":\"one\"}]"));
        var delete = OpenApiContract.of("v1:DeleteMonitor");
        delete.assertResponse(200, "{\"deleted_monitor_id\":1}");
        assertThrows(AssertionError.class, () -> delete.assertResponse(200, "{\"deleted_monitor_id\":\"one\"}"));
    }

    @Test
    void schemaValidatorChecksCompositionReferencesAndBounds() throws Exception {
        var document = JacksonMapper.ofJson().readTree("{\"components\":{\"schemas\":{\"Name\":{\"type\":\"string\",\"minLength\":2}}}}");
        var validator = new SchemaValidator(document);
        record Case(String schema, String good, String bad) { }
        for (var c : List.of(
            new Case("{\"$ref\":\"#/components/schemas/Name\"}", "\"ok\"", "\"x\""),
            new Case("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "2", "true"),
            new Case("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "2", "false"),
            new Case("{\"allOf\":[{\"type\":\"number\",\"minimum\":1},{\"type\":\"number\",\"maximum\":3}]}", "2", "5"),
            new Case("{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"integer\"}}", "[1,2]", "[1,1]"),
            new Case("{\"type\":\"object\",\"additionalProperties\":false}", "{}", "{\"extra\":1}"),
            new Case("{\"type\":\"string\",\"pattern\":\"^ok$\"}", "\"ok\"", "\"bad\""),
            new Case("{\"type\":\"string\",\"format\":\"date\"}", "\"2026-11-05\"", "\"bad\""),
            new Case("{\"type\":\"integer\",\"nullable\":true}", "null", "1.5")
        )) {
            var schema = JacksonMapper.ofJson().readTree(c.schema());
            validator.validate(schema, JacksonMapper.ofJson().readTree(c.good()), "fixture", true);
            assertThrows(AssertionError.class, () -> validator.validate(schema, JacksonMapper.ofJson().readTree(c.bad()), "fixture", true));
        }
    }
}
