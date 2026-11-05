package io.kestra.plugin.datadog.api;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CallTest extends AbstractDatadogTest {
    private Call.CallBuilder<?, ?> call(String operation) {
        return Call.builder().id(IdUtils.create()).type(Call.class.getName()).operationId(Property.ofValue(operation))
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .maxRetries(Property.ofValue(0));
    }

    @TestFactory
    Stream<DynamicTest> validationMatrixRejectsBeforeNetwork() {
        record Case(String name, String operation, Consumer<Call.CallBuilder<?, ?>> configure) { }
        return Stream.of(
            new Case("unknown operation", "NoSuchOperation", b -> { }),
            new Case("blank operation", " ", b -> { }),
            new Case("ambiguous operation", "SubmitLog", b -> { }),
            new Case("missing path", "GetMonitor", b -> { }),
            new Case("blank path", "v2:GetUser", b -> b.pathParams(Property.ofValue(Map.of("user_id", " ")))),
            new Case("invalid path integer", "GetMonitor", b -> b.pathParams(Property.ofValue(Map.of("monitor_id", "wrong")))),
            new Case("unknown path", "ListRoles", b -> b.pathParams(Property.ofValue(Map.of("typo", "x")))),
            new Case("missing query", "v1:ListEvents", b -> { }),
            new Case("unknown query", "ListRoles", b -> b.query(Property.ofValue(Map.of("typo", "x")))),
            new Case("invalid query enum", "ListRoles", b -> b.query(Property.ofValue(Map.of("sort", "wrong")))),
            new Case("invalid query integer", "ListRoles", b -> b.query(Property.ofValue(Map.of("page[size]", 1.5)))),
            new Case("missing body", "CreateMonitor", b -> { }),
            new Case("blank body", "CreateMonitor", b -> b.body(" ")),
            new Case("null JSON body", "CreateMonitor", b -> b.body("null")),
            new Case("malformed JSON body", "CreateMonitor", b -> b.body("{")),
            new Case("unexpected body", "ListRoles", b -> b.body(Map.of())),
            new Case("unsupported content type", "CreateMonitor", b -> b.body(Map.of()).headers(Property.ofValue(Map.of("Content-Type", "text/xml")))),
            new Case("bad header enum", "v2:SubmitLog", b -> b.body("[]").headers(Property.ofValue(Map.of("Content-Encoding", "br")))),
            new Case("newline header", "ListRoles", b -> b.headers(Property.ofValue(Map.of("x-example", "bad\r\nvalue")))),
            new Case("key header override", "ListRoles", b -> b.headers(Property.ofValue(Map.of("DD-API-KEY", "bad")))),
            new Case("bad site", "ListRoles", b -> b.site(Property.ofValue("https://bad"))),
            new Case("bad API URL", "ListRoles", b -> b.apiUrl(Property.ofValue("ftp://bad"))),
            new Case("API URL credentials", "ListRoles", b -> b.apiUrl(Property.ofValue("http://user:pass@localhost"))),
            new Case("missing API key", "ListRoles", b -> b.apiKey(Property.ofValue(""))),
            new Case("missing app key", "ListRoles", b -> b.appKey(Property.ofValue(""))),
            new Case("unsupported pagination", "GetMonitor", b -> b.paginate(Property.ofValue(true))),
            new Case("zero maximum", "ListRoles", b -> b.maxItems(Property.ofValue(0))),
            new Case("large maximum", "ListRoles", b -> b.maxItems(Property.ofValue(1_000_001))),
            new Case("negative retries", "ListRoles", b -> b.maxRetries(Property.ofValue(-1))),
            new Case("large retries", "ListRoles", b -> b.maxRetries(Property.ofValue(11))),
            new Case("negative retry wait", "ListRoles", b -> b.maxRetryDelay(Property.ofValue(-1))),
            new Case("large retry wait", "ListRoles", b -> b.maxRetryDelay(Property.ofValue(3601))),
            new Case("invalid pointer", "ListRoles", b -> b.redact(Property.ofValue(List.of("not/a/pointer")))),
            new Case("invalid pointer escape", "ListRoles", b -> b.redact(Property.ofValue(List.of("/bad~x")))),
            new Case("zero page size", "ListRoles", b -> b.paginate(Property.ofValue(true)).query(Property.ofValue(Map.of("page[size]", 0))))
        ).map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            var builder = call(c.operation()); c.configure().accept(builder);
            int before = server.requests.size();
            var failure = assertThrows(IllegalArgumentException.class, () -> builder.build().run(runContext()));
            assertTrue(failure.getMessage().contains("Datadog"), failure.getMessage());
            assertEquals(before, server.requests.size());
        }));
    }

    @Test
    void sendsJsonWithCustomHeadersAndRedactsTheReply() throws Exception {
        server.reply("POST", "/api/v1/monitor", 200, "{\"id\":1,\"options\":{\"token\":\"private-value\"}}");
        Call.Output output = call("CreateMonitor").body(Map.of("name", "demo", "type", "query alert", "query", "avg(last_5m):avg:system.cpu.user{*} > 90"))
            .headers(Property.ofValue(Map.of("X-Example", "yes"))).redact(Property.ofValue(List.of("/options/token"))).build().run(runContext());
        assertEquals("query alert", server.only().json().get("type"));
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        assertEquals("yes", server.only().header("X-Example"));
        assertEquals(200, output.getStatusCode());
        assertTrue(output.getBody().toString().contains("[REDACTED]"));
        assertFalse(output.getBody().toString().contains("private-value"));
    }

    @Test
    void bodyFromInternalStorageAndNoContent() throws Exception {
        var context = runContext();
        var uri = context.storage().putFile(new ByteArrayInputStream("[{\"message\":\"hello\"}]".getBytes(StandardCharsets.UTF_8)), "body.json");
        server.reply("POST", "/api/v2/logs", 202, "{}");
        call("v2:SubmitLog").appKey(null).body(uri.toString()).build().run(context);
        assertEquals("hello", ((Map<?, ?>) server.only().jsonList().getFirst()).get("message"));
        server.reply("DELETE", "/api/v1/monitor/1", 204, "");
        var output = call("DeleteMonitor").pathParams(Property.ofValue(Map.of("monitor_id", 1))).build().run(context);
        assertEquals(204, output.getStatusCode());
        assertNull(output.getBody());
        assertEquals(0, output.getCount());
    }

    @Test
    void cursorPaginationStopsAtMaximumAndRedactsEachPage() throws Exception {
        server.reply("GET", "/api/v2/audit/events", 200, "{\"data\":[{\"id\":\"1\",\"token\":\"first\"}],\"meta\":{\"page\":{\"after\":\"next\"}}}")
            .reply("GET", "/api/v2/audit/events", 200, "{\"data\":[{\"id\":\"2\",\"token\":\"second\"},{\"id\":\"3\"}]}");
        var output = call("ListAuditLogs").paginate(Property.ofValue(true)).maxItems(Property.ofValue(2))
            .redact(Property.ofValue(List.of("/data/0/token"))).build().run(runContext());
        assertEquals(2, output.getCount());
        assertEquals(2, output.getPages());
        assertEquals(2, output.getItems().size());
        assertTrue(server.requests.get(1).query().contains("page%5Bcursor%5D=next"));
        assertEquals("[REDACTED]", ((Map<?, ?>) output.getItems().get(1)).get("token"));
    }

    @Test
    void pageNumbersHonorInitialPositionAndStoreIon() throws Exception {
        server.reply("GET", "/api/v2/roles", 200, "{\"data\":[{\"type\":\"roles\",\"id\":\"1\"}]}")
            .reply("GET", "/api/v2/roles", 200, "{\"data\":[]}");
        var context = runContext();
        var output = call("ListRoles").paginate(Property.ofValue(true)).store(Property.ofValue(true))
            .query(Property.ofValue(Map.of("page[size]", 1, "page[number]", 3))).build().run(context);
        assertEquals(1, output.getCount());
        assertEquals(2, output.getPages());
        assertNull(output.getItems());
        assertNotNull(output.getUri());
        try (var stream = context.storage().getFile(output.getUri())) {
            assertEquals("1", JacksonMapper.ofIon().readTree(stream).path("id").asText());
        }
        assertTrue(server.requests.get(0).query().contains("page%5Bnumber%5D=3"));
        assertTrue(server.requests.get(1).query().contains("page%5Bnumber%5D=4"));
    }

    @Test
    void offsetPaginationUsesThePageLimit() throws Exception {
        var op = Catalog.get().operation("ListCatalogEntity");
        server.reply("GET", op.path(), 200, "{\"data\":[{\"id\":\"1\"}]}")
            .reply("GET", op.path(), 200, "{\"data\":[]}");
        call(op.id()).paginate(Property.ofValue(true)).query(Property.ofValue(Map.of("page[limit]", 1, "page[offset]", 7))).build().run(runContext());
        assertTrue(server.requests.get(1).query().contains("page%5Boffset%5D=8"));
    }

    @Test
    void repeatedCursorAndWrongResponseShapeFail() throws Exception {
        server.reply("GET", "/api/v2/audit/events", 200, "{\"data\":[{}],\"meta\":{\"page\":{\"after\":\"same\"}}}");
        assertThrows(IllegalArgumentException.class, () -> call("ListAuditLogs").paginate(Property.ofValue(true)).build().run(runContext()));
        assertEquals(2, server.requests.size());
        server.reply("GET", "/api/v2/roles", 200, "{\"data\":{}}");
        assertThrows(IllegalArgumentException.class, () -> call("ListRoles").paginate(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void errorsExposeStatusWithoutEchoingPayloadOrCredentials() throws Exception {
        for (int status : List.of(400, 403, 404, 429, 500, 503)) {
            server.reply("GET", "/api/v2/roles", status, "{\"errors\":[\"" + API_KEY + "\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> call("ListRoles").build().run(runContext()));
            assertEquals(status, error.getStatus());
            assertTrue(error.getMessage().contains("v2:ListRoles"));
            assertFalse(error.getMessage().contains(API_KEY));
        }
        assertEquals(6, server.requests.size(), "Underlying HTTP client must not add retries");
    }

    @Test
    void retriesOnlyIdempotentMethodsAndHonorsReset() throws Exception {
        for (String method : List.of("POST", "PATCH", "GET", "PUT", "DELETE")) {
            assertEquals(!List.of("POST", "PATCH").contains(method), Transport.retryable(method, 429));
            assertEquals(!List.of("POST", "PATCH").contains(method), Transport.retryable(method, 503));
            assertFalse(Transport.retryable(method, 403));
        }
        assertEquals(17, Transport.retryDelay("17", 0));
        assertEquals(4, Transport.retryDelay("bad", 2));
        AtomicInteger requests = new AtomicInteger();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int count = requests.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-RateLimit-Reset", "0");
            exchange.sendResponseHeaders(count % 2 == 1 ? 429 : 200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        local.start();
        try {
            String url = "http://127.0.0.1:" + local.getAddress().getPort();
            call("ListRoles").apiUrl(Property.ofValue(url)).maxRetries(Property.ofValue(1)).build().run(runContext());
            assertEquals(2, requests.get());
            assertThrows(DatadogClient.DatadogException.class, () -> call("CreateMonitor").body(Map.of())
                .apiUrl(Property.ofValue(url)).maxRetries(Property.ofValue(2)).build().run(runContext()));
            assertEquals(3, requests.get());
        } finally { local.stop(0); }
    }

    @Test
    void textAndBinaryAnswersRemainIntactAndCannotBypassRedaction() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean text = exchange.getRequestURI().getPath().startsWith("/text");
            byte[] bytes = text ? "ready".getBytes(StandardCharsets.UTF_8) : new byte[]{0, 1, (byte) 255, 127};
            exchange.getResponseHeaders().set("Content-Type", text ? "text/plain" : "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        local.start();
        try {
            String url = "http://127.0.0.1:" + local.getAddress().getPort();
            var context = runContext();
            var binary = call("ListRoles").apiUrl(Property.ofValue(url)).build().run(context);
            try (var input = context.storage().getFile(binary.getUri())) { assertArrayEquals(new byte[]{0, 1, (byte) 255, 127}, input.readAllBytes()); }
            var text = call("ListRoles").apiUrl(Property.ofValue(url + "/text")).build().run(context);
            assertEquals("ready", text.getBody());
            assertThrows(IllegalArgumentException.class, () -> call("ListRoles").apiUrl(Property.ofValue(url))
                .redact(Property.ofValue(List.of("/key"))).build().run(context));
        } finally { local.stop(0); }
    }

    @Test
    void redactionHandlesEscapesArraysAndRootWithoutMutatingTheInput() throws Exception {
        var op = Catalog.get().operation("ListRoles");
        var input = JacksonMapper.ofJson().readTree("{\"a/b\":{\"~key\":1},\"list\":[1,2]}");
        var safe = Redaction.apply(input, Redaction.pointers(op, List.of("/a~1b/~0key", "/list/1", "/missing")));
        assertEquals("[REDACTED]", safe.at("/a~1b/~0key").asText());
        assertEquals("[REDACTED]", safe.at("/list/1").asText());
        assertEquals(2, input.at("/list/1").asInt());
        assertEquals("[REDACTED]", Redaction.apply(input, Redaction.pointers(op, List.of(""))).asText());
    }

    @Test
    void operationsSearchNeedsNoConnection() throws Exception {
        var output = Operations.builder().id(IdUtils.create()).type(Operations.class.getName()).tag(Property.ofValue("monitors"))
            .method(Property.ofValue(Operations.Method.GET)).text(Property.ofValue("monitor")).build().run(runContext());
        assertFalse(output.getOperations().isEmpty());
        assertEquals(output.getOperations().size(), output.getCount());
        output.getOperations().forEach(row -> {
            assertEquals("GET", row.get("method"));
            assertTrue(row.get("qualifiedId").toString().contains(":"));
        });
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void multipartUploadPreservesBoundaryAndFileBytes() throws Exception {
        var context = runContext();
        var uri = context.storage().putFile(new ByteArrayInputStream("<metadata/>".getBytes(StandardCharsets.UTF_8)), "idp.xml");
        server.reply("POST", "/api/v2/saml_configurations/idp_metadata", 200, "");
        call("UploadIdPMetadata").body(Map.of("idp_file", uri.toString())).build().run(context);
        String contentType = server.only().header("Content-Type");
        assertTrue(contentType.startsWith("multipart/form-data;"), contentType);
        assertTrue(contentType.contains("boundary="));
        assertTrue(server.only().body().contains("<metadata/>"));
        assertTrue(server.only().body().contains("name=\"idp_file\""));
    }

    @Test
    void identityContentEncodingIsPassedThroughAsDeclaredByV2Logs() throws Exception {
        server.reply("POST", "/api/v2/logs", 202, "{}");
        call("v2:SubmitLog").body("[{\"message\":\"unchanged\"}]")
            .headers(Property.ofValue(Map.of("Content-Encoding", "identity"))).build().run(runContext());
        assertEquals("identity", server.only().header("Content-Encoding"));
        assertEquals("unchanged", ((Map<?, ?>) server.only().jsonList().getFirst()).get("message"));
    }
}
