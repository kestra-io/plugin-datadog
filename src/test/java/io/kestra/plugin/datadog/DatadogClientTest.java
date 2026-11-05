package io.kestra.plugin.datadog;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
class DatadogClientTest {
    @Inject
    private RunContextFactory runContextFactory;

    private DatadogClient client(String site, String apiUrl, String appKey, boolean needsAppKey) throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());
        return DatadogClient.create(runContext, Property.ofValue("api"), appKey == null ? null : Property.ofValue(appKey),
            site == null ? null : Property.ofValue(site), apiUrl == null ? null : Property.ofValue(apiUrl), needsAppKey);
    }

    @Test
    void baseUrlFollowsTheSite() throws Exception {
        assertEquals("https://api.datadoghq.com", client(null, null, null, false).apiBase());
        assertEquals("https://api.datadoghq.eu", client("datadoghq.eu", null, null, false).apiBase());
        assertEquals("https://api.us3.datadoghq.com", client("us3.datadoghq.com", null, null, false).apiBase());
        assertEquals("https://http-intake.logs.datadoghq.eu", client("datadoghq.eu", null, null, false).logsIntakeBase(null));
        assertEquals("http://localhost:1/x", client(null, "http://localhost:1/x/", null, false).apiBase());
    }

    @Test
    void siteCannotAddAPathAPortOrCredentials() {
        for (String bad : new String[]{"evil.com/path", "evil.com:8080", "user@evil.com", "a b", "evil.com?x=1", "-bad.com"}) {
            assertThrows(IllegalArgumentException.class, () -> client(bad, null, null, false), bad);
        }
    }

    @Test
    void apiUrlMustBeHttp() {
        assertThrows(IllegalArgumentException.class, () -> client(null, "ftp://example.com", null, false));
        assertThrows(IllegalArgumentException.class, () -> client(null, "not a url", null, false));
    }

    @Test
    void appKeyIsRequiredOnlyWhenTheCallNeedsIt() throws Exception {
        client(null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> client(null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> client(null, null, "   ", true));
        client(null, null, "app", true);
    }

    @Test
    void blankApiKeyIsRefused() {
        RunContext runContext = runContextFactory.of(Map.of());
        assertThrows(IllegalArgumentException.class, () -> DatadogClient.create(runContext, Property.ofValue("  "), null, null, null, false));
    }

    @Test
    void aNetworkFailureIsATransientErrorWithStatusMinusOne() throws Exception {
        try (DatadogClient client = client(null, "http://127.0.0.1:1", null, false)) {
            DatadogClient.DatadogException error = assertThrows(DatadogClient.DatadogException.class, () -> client.post("/api/v1/events", Map.of()));
            assertEquals(-1, error.getStatus());
            assertTrue(error.isTransient());
        }
    }

    @Test
    void theStatusSaysWhetherACallMayWorkLater() {
        assertTrue(new DatadogClient.DatadogException(429, "x").isTransient());
        assertTrue(new DatadogClient.DatadogException(503, "x").isTransient());
        assertFalse(new DatadogClient.DatadogException(403, "x").isTransient());
        assertFalse(new DatadogClient.DatadogException(404, "x").isTransient());
    }

    @Test
    void errorsOfDatadogAreShown() {
        assertEquals("Forbidden; other", DatadogClient.errors("{\"errors\":[\"Forbidden\",\"other\"]}"));
        assertEquals("plain text", DatadogClient.errors("plain text"));
        assertEquals("", DatadogClient.errors(""));
        assertTrue(DatadogClient.errors("x".repeat(500)).endsWith("..."));
    }

    @Test
    void queryStringIsEncodedAndSkipsNulls() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", "avg:system.cpu{env:prod} by {host}");
        params.put("skip", null);
        params.put("page", 2);
        String query = DatadogClient.queryString(params);
        assertEquals("?query=avg%3Asystem.cpu%7Benv%3Aprod%7D+by+%7Bhost%7D&page=2", query);
        assertFalse(query.contains("skip"));
        assertEquals("", DatadogClient.queryString(Map.of()));
    }

    @Test
    void sendsPutPatchAndDeleteWithABody() throws Exception {
        MockDatadogServer server = new MockDatadogServer();
        try {
            server.reply("PUT", "/x", 200, "{\"ok\":true}").reply("PATCH", "/x", 200, "[1,2]").reply("DELETE", "/x", 200, "{}");
            DatadogClient client = DatadogClient.create(runContextFactory.of(Map.of()), Property.ofValue("k"), Property.ofValue("a"), null, Property.ofValue(server.url()), true);

            assertEquals(Map.of("ok", true), client.put("/x", Map.of("a", 1)).map());
            assertEquals(java.util.List.of(1, 2), client.patch("/x", Map.of("b", 2)).list());
            client.request("DELETE", "/x", Map.of("force", "true"), Map.of("c", 3));

            assertEquals(3, server.requests.size());
            assertEquals("{\"a\":1}", server.requests.get(0).body());
            assertEquals("PATCH", server.requests.get(1).method());
            assertEquals("force=true", server.requests.get(2).query());
            assertEquals("{\"c\":3}", server.requests.get(2).body());
            assertThrows(IllegalStateException.class, () -> client.put("/missing", Map.of()).list().get(0));
        } finally {
            server.stop();
        }
    }
}
