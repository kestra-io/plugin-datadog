package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticsTasksTest extends AbstractDatadogTest {
    private <B extends AbstractDatadogTask.AbstractDatadogTaskBuilder<?, ?>> B auth(B builder, Class<?> type) {
        builder.id(IdUtils.create());
        builder.type(type.getName());
        builder.apiKey(Property.ofValue(API_KEY));
        builder.appKey(Property.ofValue(APP_KEY));
        builder.apiUrl(Property.ofValue(server.url()));
        return builder;
    }

    private Map<String, Object> definition(String kind) {
        return new LinkedHashMap<>(Map.of("name", "Smoke", "type", kind, "message", "",
            "locations", java.util.List.of("aws:eu-west-3"), "options", Map.of("tick_every", 300),
            "config", Map.of("request", Map.of("method", "GET", "url", "https://example.com", "headers", Map.of("Authorization", "Bearer private-value")),
                "assertions", java.util.List.of(Map.of("type", "statusCode", "operator", "is", "target", 200)))));
    }

    private String response(String kind) throws Exception {
        var body = definition(kind);
        body.put("public_id", "abc-def-ghi");
        return JacksonMapper.ofJson().writeValueAsString(body);
    }

    @TestFactory
    Stream<DynamicTest> createAndUpdateRouteApiAndBrowserDefinitions() {
        return Stream.of("api", "browser").flatMap(kind -> Stream.of("POST", "PUT").map(method -> DynamicTest.dynamicTest(method + " " + kind, () -> {
            server.requests.clear();
            String path = "/api/v1/synthetics/tests/" + kind + (method.equals("PUT") ? "/abc-def-ghi" : "");
            server.reply(method, path, 200, response(kind));
            Map<String, Object> output;
            if (method.equals("POST")) {
                output = auth(Create.builder(), Create.class).definition(Property.ofValue(definition(kind))).build().run(runContext()).getTest();
            } else {
                output = auth(Update.builder(), Update.class).publicId(Property.ofValue("abc-def-ghi"))
                    .definition(Property.ofValue(definition(kind))).build().run(runContext()).getTest();
            }
            assertEquals("abc-def-ghi", output.get("public_id"));
            assertFalse(output.containsKey("config"));
            assertFalse(output.toString().contains("private-value"));
            assertEquals(definition(kind), server.only().json());
        })));
    }

    @TestFactory
    Stream<DynamicTest> getAllTestKindsAndRedactByDefault() {
        return Stream.of(Get.TestKind.values()).map(kind -> DynamicTest.dynamicTest("Get " + kind, () -> {
            server.requests.clear();
            String body = kind == Get.TestKind.MOBILE
                ? "{\"name\":\"Mobile\",\"type\":\"mobile\",\"public_id\":\"abc-def-ghi\",\"message\":\"\",\"config\":{},\"device_ids\":[],\"options\":{}}"
                : response(kind.apiValue);
            server.reply("GET", "/api/v1/synthetics/tests/" + kind.apiValue + "/abc-def-ghi", 200, body);
            var output = auth(Get.builder(), Get.class).publicId(Property.ofValue("abc-def-ghi")).testKind(Property.ofValue(kind)).build().run(runContext());
            assertEquals("abc-def-ghi", output.getTest().get("public_id"));
            assertFalse(output.getTest().containsKey("config"));
            assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        }));
    }

    @Test
    void fullDefinitionRequiresExplicitSecretOptIn() throws Exception {
        server.reply("GET", "/api/v1/synthetics/tests/api/abc-def-ghi", 200, response("api"));
        var output = auth(Get.builder(), Get.class).publicId(Property.ofValue("abc-def-ghi")).includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertTrue(output.getTest().toString().contains("private-value"));
    }

    @Test
    void listUsesPageNumberAndStopsAfterShortPage() throws Exception {
        var page = IntStream.range(0, 100).mapToObj(i -> Map.of("public_id", "test-" + i)).toList();
        server.reply("GET", "/api/v1/synthetics/tests", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("tests", page)));
        server.reply("GET", "/api/v1/synthetics/tests", 200, "{\"tests\":[" + response("api") + "]}");
        var output = auth(List.builder(), List.class).maxTests(Property.ofValue(200)).build().run(runContext());
        assertEquals(101, output.getSize());
        assertFalse(output.getLimitReached());
        assertTrue(server.requests.getFirst().query().contains("page_number=0"));
        assertTrue(server.requests.getLast().query().contains("page_number=1"));
        assertFalse(output.getTests().getLast().containsKey("config"));
    }

    @Test
    void searchUsesOffsetAndSignalsCap() throws Exception {
        server.reply("GET", "/api/v1/synthetics/tests/search", 200, "{\"tests\":[" + response("api") + "]}");
        var output = auth(List.builder(), List.class).searchQuery(Property.ofValue("tag:env:prod"))
            .maxTests(Property.ofValue(1)).build().run(runContext());
        assertTrue(output.getLimitReached());
        String query = URLDecoder.decode(server.only().query(), StandardCharsets.UTF_8);
        assertTrue(query.contains("text=tag:env:prod"));
        assertTrue(query.contains("count=1"));
        assertTrue(query.contains("start=0"));
    }

    @Test
    void listCanStoreRedactedIonInsteadOfInlineRecords() throws Exception {
        server.reply("GET", "/api/v1/synthetics/tests", 200, "{\"tests\":[" + response("api") + "]}");
        var context = runContextFactory.of(Map.of(
            "flow", Map.of("id", "test-flow", "namespace", "company.delivery"),
            "execution", Map.of("id", IdUtils.create()), "taskrun", Map.of("id", IdUtils.create())));
        var output = auth(List.builder(), List.class).store(Property.ofValue(true)).build().run(context);
        assertEquals(1, output.getSize());
        assertTrue(output.getTests().isEmpty());
        assertNotNull(output.getUri());
        try (var input = context.storage().getFile(output.getUri())) {
            String ion = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(ion.contains("abc-def-ghi"));
            assertFalse(ion.contains("private-value"));
        }
    }

    @Test
    void deletesExactlyOneWithoutForcedDependencies() throws Exception {
        server.reply("POST", "/api/v1/synthetics/tests/delete", 200,
            "{\"deleted_tests\":[{\"public_id\":\"abc-def-ghi\",\"deleted_at\":\"2026-11-05T10:00:00Z\"}]}");
        var output = auth(Delete.builder(), Delete.class).publicId(Property.ofValue("abc-def-ghi")).build().run(runContext());
        assertTrue(output.getDeleted());
        assertEquals(Map.of("public_ids", java.util.List.of("abc-def-ghi"), "force_delete_dependencies", false), server.only().json());
    }

    @Test
    void emptyDeletionIsNotReportedAsSuccess() throws Exception {
        server.reply("POST", "/api/v1/synthetics/tests/delete", 200, "{\"deleted_tests\":[]}");
        assertThrows(IllegalStateException.class, () -> auth(Delete.builder(), Delete.class).publicId(Property.ofValue("abc-def-ghi")).build().run(runContext()));
        assertFalse(auth(Delete.builder(), Delete.class).publicId(Property.ofValue("abc-def-ghi")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
    }

    @Test
    void ignoreMissingIsOptInForSingleObjectWrites() throws Exception {
        server.reply("POST", "/api/v1/synthetics/tests/delete", 404, "{\"errors\":[\"missing\"]}");
        assertFalse(auth(Delete.builder(), Delete.class).publicId(Property.ofValue("abc-def-ghi")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
        server.reply("PUT", "/api/v1/synthetics/tests/api/abc-def-ghi", 404, "{\"errors\":[\"missing\"]}");
        assertFalse(auth(Update.builder(), Update.class).publicId(Property.ofValue("abc-def-ghi")).definition(Property.ofValue(definition("api")))
            .ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getUpdated());
        server.reply("PUT", "/api/v1/synthetics/tests/abc-def-ghi/status", 404, "{\"errors\":[\"missing\"]}");
        assertFalse(auth(SetStatus.builder(), SetStatus.class).publicId(Property.ofValue("abc-def-ghi")).status(Property.ofValue(SetStatus.Status.PAUSED))
            .ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getAccepted());
    }

    @TestFactory
    Stream<DynamicTest> pauseAndResumeUseNewStatusAndBooleanResponse() {
        return Stream.of(SetStatus.Status.values()).map(status -> DynamicTest.dynamicTest(status.name(), () -> {
            server.requests.clear();
            server.reply("PUT", "/api/v1/synthetics/tests/abc-def-ghi/status", 200, "true");
            assertTrue(auth(SetStatus.builder(), SetStatus.class).publicId(Property.ofValue("abc-def-ghi")).status(Property.ofValue(status)).build().run(runContext()).getAccepted());
            assertEquals(Map.of("new_status", status.apiValue), server.only().json());
        }));
    }

    @Test
    void falseStatusResponseFails() {
        server.reply("PUT", "/api/v1/synthetics/tests/abc-def-ghi/status", 200, "false");
        assertThrows(IllegalStateException.class, () -> auth(SetStatus.builder(), SetStatus.class).publicId(Property.ofValue("abc-def-ghi"))
            .status(Property.ofValue(SetStatus.Status.PAUSED)).build().run(runContext()));
    }

    @Test
    void locationsReturnIdsAndNames() throws Exception {
        server.reply("GET", "/api/v1/synthetics/locations", 200, "{\"locations\":[{\"id\":\"aws:eu-west-3\",\"name\":\"Paris\"}]}");
        assertEquals("Paris", auth(Locations.builder(), Locations.class).build().run(runContext()).getLocations().getFirst().get("name"));
    }

    @Test
    void triggerReturnsBatchWithoutPolling() throws Exception {
        server.reply("POST", "/api/v1/synthetics/tests/trigger/ci", 200, "{\"batch_id\":\"batch-1\",\"results\":[{\"public_id\":\"abc-def-ghi\",\"result_id\":\"r1\",\"location\":1}]}");
        var result = auth(Trigger.builder(), Trigger.class).testIds(Property.ofValue(java.util.List.of("abc-def-ghi"))).build().run(runContext());
        assertEquals("batch-1", result.getBatchId());
        assertEquals(1, result.getResults().size());
        assertEquals(1, server.requests.size());
    }

    @TestFactory
    Stream<DynamicTest> latestAndDetailedResultsRouteBothKinds() {
        return Stream.of(Results.TestKind.values()).flatMap(kind -> Stream.of(false, true).map(detail -> DynamicTest.dynamicTest(kind + " detail=" + detail, () -> {
            server.requests.clear();
            String prefix = kind == Results.TestKind.BROWSER ? "browser/" : "";
            String path = "/api/v1/synthetics/tests/" + prefix + "abc-def-ghi/results" + (detail ? "/r1" : "");
            String row = "{\"result_id\":\"r1\",\"check_time\":1693491974.0,\"probe_dc\":\"aws:eu-west-3\",\"status\":0,\"result\":{}}";
            server.reply("GET", path, 200, detail ? row : "{\"results\":[" + row + "],\"last_timestamp_fetched\":1693491974}");
            var output = auth(Results.builder(), Results.class).publicId(Property.ofValue("abc-def-ghi")).testKind(Property.ofValue(kind))
                .resultId(detail ? Property.ofValue("r1") : null).build().run(runContext());
            assertEquals(1, output.getSize());
            assertEquals("r1", output.getResults().getFirst().get("result_id"));
            assertFalse(output.getResults().getFirst().containsKey("result"));
            assertEquals(path, server.only().path());
        })));
    }

    record Endpoint(String name, String method, String path, Callable<?> call) { }

    private Stream<Endpoint> endpoints() {
        return Stream.of(
            new Endpoint("List", "GET", "/api/v1/synthetics/tests", () -> auth(List.builder(), List.class).build().run(runContext())),
            new Endpoint("Get", "GET", "/api/v1/synthetics/tests/api/abc-def-ghi", () -> auth(Get.builder(), Get.class).publicId(Property.ofValue("abc-def-ghi")).build().run(runContext())),
            new Endpoint("Create", "POST", "/api/v1/synthetics/tests/api", () -> auth(Create.builder(), Create.class).definition(Property.ofValue(definition("api"))).build().run(runContext())),
            new Endpoint("Update", "PUT", "/api/v1/synthetics/tests/api/abc-def-ghi", () -> auth(Update.builder(), Update.class).publicId(Property.ofValue("abc-def-ghi")).definition(Property.ofValue(definition("api"))).build().run(runContext())),
            new Endpoint("Delete", "POST", "/api/v1/synthetics/tests/delete", () -> auth(Delete.builder(), Delete.class).publicId(Property.ofValue("abc-def-ghi")).build().run(runContext())),
            new Endpoint("SetStatus", "PUT", "/api/v1/synthetics/tests/abc-def-ghi/status", () -> auth(SetStatus.builder(), SetStatus.class).publicId(Property.ofValue("abc-def-ghi")).status(Property.ofValue(SetStatus.Status.LIVE)).build().run(runContext())),
            new Endpoint("Locations", "GET", "/api/v1/synthetics/locations", () -> auth(Locations.builder(), Locations.class).build().run(runContext())),
            new Endpoint("Results", "GET", "/api/v1/synthetics/tests/abc-def-ghi/results", () -> auth(Results.builder(), Results.class).publicId(Property.ofValue("abc-def-ghi")).build().run(runContext())),
            new Endpoint("Trigger", "POST", "/api/v1/synthetics/tests/trigger/ci", () -> auth(Trigger.builder(), Trigger.class).testIds(Property.ofValue(java.util.List.of("abc-def-ghi"))).build().run(runContext()))
        );
    }

    @TestFactory
    Stream<DynamicTest> taskHttpErrorMatrix() {
        return endpoints().flatMap(endpoint -> IntStream.of(400, 403, 404, 429, 500).mapToObj(status -> DynamicTest.dynamicTest(endpoint.name + " HTTP " + status, () -> {
            server.requests.clear();
            server.reply(endpoint.method, endpoint.path, status, "{\"errors\":[\"rejected\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, endpoint.call::call).getStatus());
            // The shared Kestra HTTP transport retries 429 before exposing it to DatadogClient.
            assertTrue(server.requests.stream().allMatch(request -> request.method().equals(endpoint.method) && request.path().equals(endpoint.path)));
            if (status != 429) {
                assertEquals(1, server.requests.size());
            }
        })));
    }

    @TestFactory
    Stream<DynamicTest> everyRequiredDefinitionFieldIsValidatedBeforeHttp() {
        return Stream.of("name", "message", "type", "config", "locations", "options").flatMap(key -> Stream.of(false, true).map(update -> DynamicTest.dynamicTest("missing " + key + " update=" + update, () -> {
            var body = definition("api");
            body.remove(key);
            if (update) {
                assertThrows(IllegalArgumentException.class, () -> auth(Update.builder(), Update.class).publicId(Property.ofValue("abc-def-ghi")).definition(Property.ofValue(body)).build().run(runContext()));
            } else {
                assertThrows(IllegalArgumentException.class, () -> auth(Create.builder(), Create.class).definition(Property.ofValue(body)).build().run(runContext()));
            }
            assertTrue(server.requests.isEmpty());
        })));
    }

    @Test
    void rejectsWrongDefinitionTypesAndUnsupportedKind() {
        for (String key : java.util.List.of("name", "message", "type", "config", "locations", "options")) {
            var body = definition("api");
            body.put(key, 42);
            assertThrows(IllegalArgumentException.class, () -> auth(Create.builder(), Create.class).definition(Property.ofValue(body)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> auth(Create.builder(), Create.class).definition(Property.ofValue(definition("mobile"))).build().run(runContext()));
        var browser = definition("browser");
        browser.put("config", Map.of());
        assertThrows(IllegalArgumentException.class, () -> auth(Create.builder(), Create.class).definition(Property.ofValue(browser)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void validatesIdsBodiesAndLimitsBeforeHttp() {
        assertThrows(IllegalArgumentException.class, () -> auth(Get.builder(), Get.class).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Delete.builder(), Delete.class).publicId(Property.ofValue("bad/id")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Update.builder(), Update.class).definition(Property.ofValue(definition("api"))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Create.builder(), Create.class).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(SetStatus.builder(), SetStatus.class).status(Property.ofValue(SetStatus.Status.LIVE)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(SetStatus.builder(), SetStatus.class).publicId(Property.ofValue("a")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Results.builder(), Results.class).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Results.builder(), Results.class).publicId(Property.ofValue("a")).resultId(Property.ofValue("../x")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(List.builder(), List.class).maxTests(Property.ofValue(0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(List.builder(), List.class).maxTests(Property.ofValue(10001)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> auth(Trigger.builder(), Trigger.class).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }
}
