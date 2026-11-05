package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeysTest extends OrganizationTestSupport {
    private String keyResponse() throws Exception {
        return json(Map.of("data", resource("new-id", "api_keys", Map.of("key", VALUE, "name", "pipeline", "last4", "1234"))));
    }

    @Test
    void createRedactsByDefaultAndCanExplicitlyReturnTheValue() throws Exception {
        server.reply("POST", "/api/v2/api_keys", 201, keyResponse());
        KeyOutput output = connection(Create.builder()).name(Property.ofValue("pipeline")).build().run(runContext());
        assertEquals("new-id", output.getKeyId());
        assertEquals("[REDACTED]", output.getKey());
        assertEquals("****1234", output.getPreview());
        noCredential(output);
        assertEquals(Map.of("data", Map.of("type", "api_keys", "attributes", Map.of("name", "pipeline"))), server.only().json());
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        KeyOutput included = connection(Create.builder()).name(Property.ofValue("pipeline")).includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertEquals(VALUE, included.getKey());
    }

    @Test
    void applicationKeyCreationRequiresExplicitScopesAndUsesCurrentUser() throws Exception {
        server.reply("POST", "/api/v2/current_user/application_keys", 201, json(Map.of("data", resource("new-id", "application_keys", Map.of("key", VALUE, "scopes", List.of("user_access_read"))))));
        KeyOutput output = connection(Create.builder()).name(Property.ofValue("reader")).keyKind(Property.ofValue(KeyKind.APPLICATION))
            .scopes(Property.ofValue(List.of("user_access_read"))).build().run(runContext());
        noCredential(output);
        assertEquals(List.of("user_access_read"), output.getScopes());
        assertEquals(Map.of("data", Map.of("type", "application_keys", "attributes", Map.of("name", "reader", "scopes", List.of("user_access_read")))), server.only().json());
    }

    @Test
    void rotateChecksTheOldIdCreatesReplacementAndNeverRevokesIt() throws Exception {
        server.reply("GET", "/api/v2/api_keys/old-id", 200, keyResponse());
        server.reply("POST", "/api/v2/api_keys", 201, keyResponse());
        KeyOutput output = connection(Rotate.builder()).oldKeyId(Property.ofValue("old-id")).name(Property.ofValue("replacement")).build().run(runContext());
        noCredential(output);
        assertEquals("new-id", output.getKeyId());
        assertEquals(List.of("GET", "POST"), server.requests.stream().map(request -> request.method()).toList());
        KeyOutput included = connection(Rotate.builder()).oldKeyId(Property.ofValue("old-id")).name(Property.ofValue("replacement"))
            .includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertEquals(VALUE, included.getKey());
    }

    @Test
    void rotateApplicationKeyWithExplicitScopes() throws Exception {
        server.reply("GET", "/api/v2/application_keys/old", 200, "{}");
        server.reply("POST", "/api/v2/current_user/application_keys", 201, keyResponse());
        connection(Rotate.builder()).oldKeyId(Property.ofValue("old")).name(Property.ofValue("new")).keyKind(Property.ofValue(KeyKind.APPLICATION))
            .scopes(Property.ofValue(List.of())).build().run(runContext());
        assertEquals("/api/v2/current_user/application_keys", server.requests.get(1).path());
        assertTrue(server.requests.get(1).body().contains("\"scopes\":[]"));
    }

    @Test
    void validationHappensBeforeCreatingAKey() {
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).name(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).keyKind(Property.ofValue(KeyKind.APPLICATION)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).keyKind(Property.ofValue(KeyKind.APPLICATION)).scopes(Property.ofValue(null)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).scopes(Property.ofValue(List.of())).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).keyKind(Property.ofValue(KeyKind.APPLICATION)).scopes(Property.ofValue(List.of(" "))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Rotate.builder()).name(Property.ofValue("x")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Rotate.builder()).oldKeyId(Property.ofValue("..")).name(Property.ofValue("x")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Revoke.builder()).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void revokeIsSingleObjectAndOnlyIgnores404() throws Exception {
        server.reply("DELETE", "/api/v2/api_keys/key id", 204, "");
        assertTrue(connection(Revoke.builder()).keyId(Property.ofValue("key id")).build().run(runContext()).getRevoked());
        assertEquals("/api/v2/api_keys/key id", server.only().path());
        server.reply("DELETE", "/api/v2/application_keys/gone", 404, "{}");
        assertFalse(connection(Revoke.builder()).keyKind(Property.ofValue(KeyKind.APPLICATION)).keyId(Property.ofValue("gone"))
            .ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getRevoked());
        server.reply("DELETE", "/api/v2/api_keys/gone", 403, "{}");
        assertThrows(DatadogClient.DatadogException.class, () -> connection(Revoke.builder()).keyId(Property.ofValue("gone"))
            .ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void bothInventoriesPageAndRedactWithExplicitOptIn() throws Exception {
        for (String path : List.of("/api/v2/api_keys", "/api/v2/application_keys")) {
            server.requests.clear();
            String type = path.endsWith("api_keys") ? "api_keys" : "application_keys";
            server.reply("GET", path, 200, page(List.of(resource("a", type, Map.of("name", "a", "key", VALUE))), 2));
            server.reply("GET", path, 200, page(List.of(resource("b", type, Map.of("name", "b"))), 2));
            var task = path.endsWith("api_keys")
                ? connection(ListApiKeys.builder()).pageSize(Property.ofValue(1)).filter(Property.ofValue("pipeline")).build()
                : connection(ListApplicationKeys.builder()).pageSize(Property.ofValue(1)).filter(Property.ofValue("pipeline")).build();
            var output = task.run(runContext());
            noCredential(output);
            assertEquals(2, output.getSize());
            assertFalse(output.getTruncated());
            assertTrue(server.requests.get(1).query().contains("page%5Bnumber%5D=1"));
            assertTrue(server.requests.get(0).query().contains("filter=pipeline"));
            server.reply("GET", path, 200, page(List.of(resource("a", type, Map.of("key", VALUE))), 1));
            var included = path.endsWith("api_keys")
                ? connection(ListApiKeys.builder()).includeSecrets(Property.ofValue(true)).build().run(runContext())
                : connection(ListApplicationKeys.builder()).includeSecrets(Property.ofValue(true)).build().run(runContext());
            assertTrue(json(included).contains(VALUE));
        }
    }

    @Test
    void storedRowsAreRedactedAndDoNotPopulateInlineOutput() throws Exception {
        server.reply("GET", "/api/v2/api_keys", 200, page(List.of(resource("a", "api_keys", Map.of("key", VALUE))), 1));
        var context = runContext();
        var output = connection(ListApiKeys.builder()).store(Property.ofValue(true)).build().run(context);
        assertTrue(output.getRows().isEmpty());
        assertEquals(1, output.getSize());
        assertNotNull(output.getUri());
        List<Object> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.storage().getFile(output.getUri())))) {
            FileSerde.reader(reader, rows::add);
        }
        assertEquals(1, rows.size());
        noCredential(rows);
    }

    @Test
    void listLimitsAndBrokenPaginationAreNotSilent() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> connection(ListApiKeys.builder()).pageSize(Property.ofValue(0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(ListApplicationKeys.builder()).pageSize(Property.ofValue(101)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(ListApiKeys.builder()).maxResults(Property.ofValue(10001)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(ListApplicationKeys.builder()).maxResults(Property.ofValue(0)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
        String page = page(List.of(resource("a", "api_keys", Map.of())), 2);
        server.reply("GET", "/api/v2/api_keys", 200, page);
        var limited = connection(ListApiKeys.builder()).maxResults(Property.ofValue(1)).pageSize(Property.ofValue(1)).build().run(runContext());
        assertTrue(limited.getTruncated());
        assertThrows(IllegalStateException.class, () -> connection(ListApiKeys.builder()).pageSize(Property.ofValue(1)).build().run(runContext()));
        server.reply("GET", "/api/v2/api_keys", 200, "{}");
        assertThrows(IllegalStateException.class, () -> connection(ListApiKeys.builder()).build().run(runContext()));
    }

    @Test
    void responseAndParsingErrorsNeverEchoCredentials() throws Exception {
        for (int status : List.of(400, 403, 404, 429, 500)) {
            server.reply("POST", "/api/v2/api_keys", status, json(Map.of("errors", List.of(VALUE))));
            var error = assertThrows(DatadogClient.DatadogException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).build().run(runContext()));
            assertEquals(status, error.getStatus());
            assertFalse(error.toString().contains(VALUE));
            assertNull(error.getCause());
        }
        server.reply("POST", "/api/v2/api_keys", 201, "{\"key\":\"" + VALUE);
        var error = assertThrows(IllegalStateException.class, () -> connection(Create.builder()).name(Property.ofValue("x")).build().run(runContext()));
        assertFalse(error.toString().contains(VALUE));
        assertNull(error.getCause());
    }

    @Test
    void nestedRedactionDoesNotMutateTheResponse() throws Exception {
        Map<String, Object> raw = Map.of("key", VALUE, "nested", List.of(Map.of("api_key", VALUE, "password", VALUE, "access_token", VALUE, "webhookUrl", VALUE)), "name", "safe");
        Object safe = AccessApi.redact(raw, false);
        noCredential(safe);
        assertEquals("safe", AccessApi.object(safe).get("name"));
        assertTrue(json(raw).contains(VALUE));
        assertSame(raw, AccessApi.redact(raw, true));
    }
}
