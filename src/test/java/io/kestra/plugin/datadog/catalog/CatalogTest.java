package io.kestra.plugin.datadog.catalog;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.kestra.plugin.datadog.keys.OrganizationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTest extends OrganizationTestSupport {
    private static final String PATH = "/api/v2/services/definitions/checkout";

    private Map<String, Object> definition(String team) {
        return Map.of("schema-version", "v2.2", "dd-service", "checkout", "team", team);
    }

    private String response(Map<String, Object> definition) throws Exception {
        return json(Map.of("data", resource("checkout", "service-definition", Map.of("schema", definition))));
    }

    @Test
    void createThenRepeatIsUnchanged() throws Exception {
        Map<String, Object> desired = definition("payments");
        server.reply("GET", PATH, 404, "{}").reply("GET", PATH, 200, response(desired));
        server.reply("POST", "/api/v2/services/definitions", 200, json(Map.of("data", List.of(resource("checkout", "service-definition", Map.of("schema", desired))))));
        var task = connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(desired)).build();
        var first = task.run(runContext());
        assertEquals(UpsertServiceDefinition.Status.CREATED, first.getStatus());
        assertEquals(desired, server.requests.get(1).json());
        assertTrue(server.requests.get(0).query().contains("schema_version=v2.2"));
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests,
            "v2:GetServiceDefinition", "v2:CreateOrUpdateServiceDefinitions");
        server.requests.clear();
        var second = task.run(runContext());
        assertEquals(UpsertServiceDefinition.Status.UNCHANGED, second.getStatus());
        assertTrue(second.getDiff().isEmpty());
        assertEquals("GET", server.only().method());
        io.kestra.plugin.datadog.contract.OpenApiContract.of("v2:GetServiceDefinition").assertRequest(server.only());
    }

    @Test
    void updatePreservesOmittedFieldsAndHasFieldDiff() throws Exception {
        Map<String, Object> current = new LinkedHashMap<>(definition("old-team"));
        current.put("tags", List.of("env:prod"));
        current.put("extensions", Map.of("keep", true, "password", VALUE));
        Map<String, Object> desired = new LinkedHashMap<>(definition("payments"));
        desired.put("extensions", Map.of("added", true));
        Map<String, Object> expected = new LinkedHashMap<>(definition("payments"));
        expected.put("tags", List.of("env:prod"));
        expected.put("extensions", Map.of("keep", true, "password", VALUE, "added", true));
        server.reply("GET", PATH, 200, response(current)).reply("GET", PATH, 200, response(expected));
        server.reply("POST", "/api/v2/services/definitions", 200, "{\"data\":[]}");
        var task = connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(desired)).build();
        var output = task.run(runContext());
        assertEquals(UpsertServiceDefinition.Status.UPDATED, output.getStatus());
        assertEquals(expected, server.requests.get(1).json());
        var change = output.getDiff().stream().filter(item -> item.getField().equals("/team")).findFirst().orElseThrow();
        assertEquals("old-team", change.getBefore());
        assertEquals("payments", change.getAfter());
        noCredential(output);
        server.requests.clear();
        assertEquals(UpsertServiceDefinition.Status.UNCHANGED, task.run(runContext()).getStatus());
        assertEquals("GET", server.only().method());
    }

    @Test
    void dryRunRedactsDiffValuesUnlessExplicitlyIncluded() throws Exception {
        server.reply("GET", PATH, 200, response(definition("payments")));
        Map<String, Object> desired = new LinkedHashMap<>(definition("payments"));
        desired.put("extensions", Map.of("password", VALUE, "nested", List.of(Map.of("token", VALUE))));
        desired.put("links", List.of(Map.of("name", "hook", "type", "other", "url", VALUE)));
        var builder = connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(desired)).dryRun(Property.ofValue(true));
        var output = builder.build().run(runContext());
        assertEquals(UpsertServiceDefinition.Status.UPDATED, output.getStatus());
        assertTrue(output.getDryRun());
        assertFalse(output.getDiff().isEmpty());
        noCredential(output);
        assertEquals("GET", server.only().method());
        assertTrue(json(builder.includeSecrets(Property.ofValue(true)).build().run(runContext())).contains(VALUE));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void nestedCredentialContainerPathsStayRedacted() {
        List<UpsertServiceDefinition.Change> changes = new ArrayList<>();
        ServiceDefinitions.diff("/extensions/password", Map.of("value", "old"), Map.of("value", VALUE), changes, false);
        assertEquals("[REDACTED]", changes.get(0).getAfter());
    }

    @Test
    void invalidDefinitionsFailBeforeRequests() {
        assertThrows(IllegalArgumentException.class, () -> connection(UpsertServiceDefinition.builder()).build().run(runContext()));
        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(Map.of());
        invalid.add(Map.of("schema-version", "v2.1", "dd-service", "checkout"));
        invalid.add(Map.of("schema-version", "v2.2", "dd-service", " "));
        invalid.add(Map.of("schema-version", "v2.2", "dd-service", ".."));
        for (Map<String, Object> invalidField : List.of(
            Map.<String, Object>of("tags", "wrong"), Map.<String, Object>of("tags", List.of(42)),
            Map.<String, Object>of("team", 42), Map.<String, Object>of("extensions", "wrong"), Map.<String, Object>of("typo", "wrong"),
            Map.<String, Object>of("contacts", List.of(Map.of("type", "email"))),
            Map.<String, Object>of("contacts", List.of("wrong")),
            Map.<String, Object>of("links", List.of(Map.of("name", "docs", "type", "doc", "url", "https://example.com", "typo", "wrong"))),
            Map.<String, Object>of("integrations", Map.of("unknown", Map.of())),
            Map.<String, Object>of("integrations", Map.of("opsgenie", Map.of("region", "US"))),
            Map.<String, Object>of("integrations", Map.of("opsgenie", Map.of("service-url", "https://example.com", "region", "INVALID"))))) {
            Map<String, Object> value = new LinkedHashMap<>(definition("payments"));
            value.putAll(invalidField);
            invalid.add(value);
        }
        for (Map<String, Object> value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(value)).build().run(runContext()));
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void validComplexV22DefinitionIsAccepted() {
        Map<String, Object> desired = new LinkedHashMap<>(definition("payments"));
        desired.put("contacts", List.of(Map.of("type", "email", "contact", "team@example.com", "name", "team")));
        desired.put("integrations", Map.of("opsgenie", Map.of("service-url", "https://example.com", "region", "EU"), "pagerduty", Map.of("service-url", "https://example.com")));
        desired.put("links", List.of(Map.of("name", "docs", "type", "doc", "url", "https://example.com", "provider", "wiki")));
        desired.put("languages", List.of("java"));
        assertDoesNotThrow(() -> ServiceDefinitions.validate(desired));
    }

    @Test
    void serviceNameIsNormalizedInLookupAndBody() throws Exception {
        server.reply("GET", PATH, 404, "{}");
        server.reply("POST", "/api/v2/services/definitions", 200, "{\"data\":[]}");
        connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(Map.of("schema-version", "v2.2", "dd-service", " checkout "))).build().run(runContext());
        assertEquals(PATH, server.requests.get(0).path());
        assertEquals("checkout", server.requests.get(1).json().get("dd-service"));
    }

    @Test
    void wrongServiceResponseDoesNotOverwriteAnotherService() throws Exception {
        server.reply("GET", PATH, 200, response(Map.of("schema-version", "v2.2", "dd-service", "other")));
        assertThrows(IllegalStateException.class, () -> connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(definition("payments"))).build().run(runContext()));
        assertEquals("GET", server.only().method());
    }

    @Test
    void deleteRequiresOneServiceAndOnlyIgnores404() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> connection(Delete.builder()).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
        server.reply("DELETE", PATH, 204, "");
        assertTrue(connection(Delete.builder()).serviceName(Property.ofValue("checkout")).build().run(runContext()).getDeleted());
        assertEquals(PATH, server.only().path());
        server.reply("DELETE", PATH, 404, "{}");
        assertFalse(connection(Delete.builder()).serviceName(Property.ofValue("checkout")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
        server.reply("DELETE", PATH, 500, "{}");
        assertThrows(DatadogClient.DatadogException.class, () -> connection(Delete.builder()).serviceName(Property.ofValue("checkout")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }
}
