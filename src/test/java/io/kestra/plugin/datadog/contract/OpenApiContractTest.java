package io.kestra.plugin.datadog.contract;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiContractTest {
    private MockDatadogServer.Received request(String method, String path, String query, String body) {
        return new MockDatadogServer.Received(method, path, query, Map.of("content-type", "application/json"), body);
    }

    @Test
    void requestContractRejectsWrongMethodPathMissingQueryAndBodyFields() {
        var query = OpenApiContract.of("v1:QueryMetrics");
        query.assertRequest(request("GET", "/api/v1/query", "from=1&to=2&query=avg%3Ax%7B*%7D", ""));
        assertThrows(AssertionError.class, () -> query.assertRequest(request("POST", "/api/v1/query", "", "")));
        assertThrows(AssertionError.class, () -> query.assertRequest(request("GET", "/api/v1/wrong", "", "")));
        assertThrows(AssertionError.class, () -> query.assertRequest(request("GET", "/api/v1/query", "from=1&to=2", "")));
        assertThrows(AssertionError.class, () -> query.assertRequest(request("GET", "/api/v1/query", "from=1&to=2&query=x&unknown=y", "")));
        assertThrows(AssertionError.class, () -> query.assertRequest(request("GET", "/api/v1/query", "from=bad&to=2&query=x", "")));
        var event = OpenApiContract.of("v1:CreateEvent");
        event.assertRequest(request("POST", "/api/v1/events", null, "{\"title\":\"demo\",\"text\":\"hello\"}"));
        assertThrows(AssertionError.class, () -> event.assertRequest(request("POST", "/api/v1/events", null, "{}")));
        assertThrows(AssertionError.class, () -> event.assertRequest(request("POST", "/api/v1/events", null, "")));
        assertThrows(AssertionError.class, () -> event.assertRequest(request("POST", "/api/v1/events", null, "{\"title\":1,\"text\":\"hello\"}")));
        assertThrows(AssertionError.class, () -> OpenApiContract.of("missing"));
    }

    @Test
    void responseContractChecksStatusRequiredFieldsTypesAndNoContent() {
        var log = OpenApiContract.of("v2:ListLogs");
        log.assertResponse(200, "{\"data\":[{\"id\":\"1\",\"type\":\"log\"}]}");
        assertThrows(AssertionError.class, () -> log.assertResponse(201, "{}"));
        assertThrows(AssertionError.class, () -> log.assertResponse(200, "{\"data\":{}}"));
        assertThrows(AssertionError.class, () -> log.assertResponse(200, "{\"data\":[{\"type\":\"wrong\"}]}"));
        var cancel = OpenApiContract.of("v2:CancelDowntime");
        cancel.assertResponse(204, "");
        assertThrows(AssertionError.class, () -> cancel.assertResponse(204, "{}"));
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
