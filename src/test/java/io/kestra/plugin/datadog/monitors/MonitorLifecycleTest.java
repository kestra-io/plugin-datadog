package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MonitorLifecycleTest extends AbstractDatadogTest {
    private Create.CreateBuilder<?, ?> create() {
        return Create.builder().id(IdUtils.create()).type(Create.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).definition(Property.ofValue(ApplyTest.definition()));
    }

    private Update.UpdateBuilder<?, ?> update() {
        return Update.builder().id(IdUtils.create()).type(Update.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).monitorId(Property.ofValue(123L)).definition(Property.ofValue(Map.of("message", "Updated")));
    }

    private Get.GetBuilder<?, ?> get() {
        return Get.builder().id(IdUtils.create()).type(Get.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).monitorId(Property.ofValue(123L));
    }

    private Delete.DeleteBuilder<?, ?> delete() {
        return Delete.builder().id(IdUtils.create()).type(Delete.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).monitorId(Property.ofValue(123L));
    }

    private Validate.ValidateBuilder<?, ?> validate() {
        return Validate.builder().id(IdUtils.create()).type(Validate.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).definition(Property.ofValue(ApplyTest.definition()));
    }

    private Search.SearchBuilder<?, ?> search() {
        return Search.builder().id(IdUtils.create()).type(Search.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    private CanDelete.CanDeleteBuilder<?, ?> canDelete() {
        return CanDelete.builder().id(IdUtils.create()).type(CanDelete.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).monitorIds(Property.ofValue(java.util.List.of(123L, 124L)));
    }

    private Export.ExportBuilder<?, ?> export() {
        return Export.builder().id(IdUtils.create()).type(Export.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @Test
    void createsDefinitionAndReturnsId() throws Exception {
        var response = ApplyTest.definition();
        response.put("id", 123);
        server.reply("POST", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(response));
        assertEquals(123L, create().build().run(runContext()).getMonitorId());
        assertEquals(ApplyTest.definition(), server.only().json());
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
    }

    @Test
    void updatesOnlyRequestedMonitor() throws Exception {
        server.reply("PUT", "/api/v1/monitor/123", 200, "{\"id\":123,\"type\":\"query alert\",\"query\":\"test\",\"message\":\"Updated\"}");
        assertEquals(123L, update().build().run(runContext()).getMonitorId());
        assertEquals(Map.of("message", "Updated"), server.only().json());
    }

    @Test
    void updateCanIgnoreOnlyMissingMonitors() throws Exception {
        server.reply("PUT", "/api/v1/monitor/123", 404, "{\"errors\":[\"Monitor not found\"]}");
        assertFalse(update().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getUpdated());
        server.reply("PUT", "/api/v1/monitor/123", 403, "{\"errors\":[\"Forbidden\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> update().ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void listStoresFilteredRowsAndRejectsMalformedRows() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v1/monitor", 200, "[{\"id\":123,\"message\":\"https://hooks.example.org/value\"}]");
        var task = List.builder().id(IdUtils.create()).type(List.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url())).store(Property.ofValue(true)).build();
        var output = task.run(context);
        assertNull(output.getMonitors());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("[REDACTED_URL]", JacksonMapper.ofIon().readValue(input, Map.class).get("message"));
        }
        server.reply("GET", "/api/v1/monitor", 200, "[42]");
        assertThrows(IllegalStateException.class, () -> task.run(context));
    }

    @Test
    void getUsesFlagsAndFiltersSensitiveOutputUnlessOptedIn() throws Exception {
        server.reply("GET", "/api/v1/monitor/123", 200, "{\"id\":123,\"type\":\"query alert\",\"query\":\"test\",\"message\":\"https://hooks.example.org/value\",\"api_key\":\"private-value\"}");
        var output = get().withAssets(Property.ofValue(true)).withDowntimes(Property.ofValue(true)).build().run(runContext());
        assertEquals("[REDACTED]", output.getMonitor().get("api_key"));
        assertEquals("[REDACTED_URL]", output.getMonitor().get("message"));
        assertTrue(server.only().query().contains("with_assets=true"));
        assertTrue(server.only().query().contains("with_downtimes=true"));
        assertEquals("private-value", get().includeSecrets(Property.ofValue(true)).build().run(runContext()).getMonitor().get("api_key"));
    }

    @Test
    void validatesBothNewAndExistingWithoutSaving() throws Exception {
        assertTrue(validate().build().run(runContext()).getValid());
        assertTrue(validate().monitorId(Property.ofValue(123L)).build().run(runContext()).getValid());
        assertEquals(java.util.List.of("/api/v1/monitor/validate", "/api/v1/monitor/123/validate"), server.requests.stream().map(r -> r.path()).toList());
    }

    @Test
    void deletesOneMonitorAndOnlyIgnores404WhenRequested() throws Exception {
        server.reply("DELETE", "/api/v1/monitor/123", 200, "{\"deleted_monitor_id\":123}");
        assertTrue(delete().build().run(runContext()).getDeleted());
        assertNull(server.only().query());
        server.reply("DELETE", "/api/v1/monitor/123", 404, "{\"errors\":[\"Monitor not found\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> delete().build().run(runContext()));
        assertFalse(delete().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
    }

    @Test
    void checksDeletionDependencies() throws Exception {
        server.reply("GET", "/api/v1/monitor/can_delete", 200, "{\"data\":{\"ok\":[123]},\"errors\":{\"124\":[\"Referenced in composite monitor 125\"]}}");
        var output = canDelete().build().run(runContext());
        assertEquals(Map.of("ok", java.util.List.of(123)), output.getResult().get("data"));
        assertEquals("monitor_ids=123,124", URLDecoder.decode(server.only().query(), StandardCharsets.UTF_8));
    }

    @Test
    void searchPagesAndFacetsAreNotSummed() throws Exception {
        var rows = IntStream.range(0, 100).mapToObj(i -> Map.of("id", i, "name", "CPU " + i, "type", "query alert", "status", "Alert")).toList();
        var facets = Map.of("status", java.util.List.of(Map.of("name", "Alert", "count", 101)));
        server.reply("GET", "/api/v1/monitor/search", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("monitors", rows, "counts", facets, "metadata", Map.of("page_count", 2, "total_count", 101))));
        server.reply("GET", "/api/v1/monitor/search", 200, "{\"monitors\":[{\"id\":100,\"name\":\"CPU 100\"}],\"metadata\":{\"page_count\":2}}");
        var output = search().query(Property.ofValue("status:alert")).sort(Property.ofValue("name,asc")).build().run(runContext());
        assertEquals(101, output.getSize());
        assertEquals(facets, output.getCounts());
        assertEquals(2, server.requests.size());
        assertTrue(URLDecoder.decode(server.requests.getFirst().query(), StandardCharsets.UTF_8).contains("sort=name,asc"));
        assertTrue(server.requests.getLast().query().contains("page=1"));
    }

    @Test
    void searchCanStoreRowsAndRejectsUnexpectedAnswers() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v1/monitor/search", 200, "{\"monitors\":[{\"id\":123}]}");
        var output = search().store(Property.ofValue(true)).build().run(context);
        assertNull(output.getMonitors());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(123, JacksonMapper.ofIon().readValue(input, Map.class).get("id"));
        }
        server.reply("GET", "/api/v1/monitor/search", 200, "{}");
        assertThrows(IllegalStateException.class, () -> search().build().run(context));
    }

    @Test
    void exportStripsServerFieldsAndYamlRoundTrips() throws Exception {
        var context = runContext();
        var response = ApplyTest.definition();
        response.put("id", 123);
        response.put("overall_state", "Alert");
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(java.util.List.of(response)));
        var output = export().build().run(context);
        assertEquals(1, output.getSize());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(java.util.List.of(ApplyTest.definition()), JacksonMapper.ofYaml().readValue(input, java.util.List.class));
        }
        server.reply("GET", "/api/v1/monitor/123", 200, JacksonMapper.ofJson().writeValueAsString(response));
        var applied = Apply.builder().id(IdUtils.create()).type(Apply.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url())).from(Property.ofValue(output.getUri().toString())).plan(Property.ofValue(true)).build().run(context);
        assertEquals(Apply.Action.UNCHANGED, applied.getResults().getFirst().getAction());
    }

    @Test
    void exportIonAndRefusesTruncation() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(java.util.List.of(ApplyTest.definition(), ApplyTest.definition())));
        assertThrows(IllegalStateException.class, () -> export().maxMonitors(Property.ofValue(1)).build().run(context));
        var output = export().format(Property.ofValue(Export.Format.ION)).build().run(context);
        try (var input = context.storage().getFile(output.getUri()); var iterator = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(2, iterator.readAll().size());
        }
    }

    @Test
    void requiredFieldsIdsAndLimitsAreValidatedBeforeRequests() {
        assertThrows(IllegalArgumentException.class, () -> create().definition(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> update().definition(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> validate().definition(null).build().run(runContext()));
        for (Long id : java.util.Arrays.asList(null, 0L, -1L)) {
            assertThrows(IllegalArgumentException.class, () -> get().monitorId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> update().monitorId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> delete().monitorId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> validate().monitorId(Property.ofValue(0L)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> canDelete().monitorIds(Property.ofValue(java.util.List.of())).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> canDelete().monitorIds(Property.ofValue(java.util.List.of(-1L))).build().run(runContext()));
        for (int limit : new int[]{0, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> search().maxResults(Property.ofValue(limit)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> export().maxMonitors(Property.ofValue(limit)).build().run(runContext()));
        }
        for (var field : Map.<String, Object>of("options", "bad", "tags", java.util.List.of(1), "restricted_roles", "bad", "priority", 1.5, "name", 42, "message", false, "draft_status", "other", "assets", "bad", "unknown", "bad").entrySet()) {
            var invalid = ApplyTest.definition();
            invalid.put(field.getKey(), field.getValue());
            assertThrows(IllegalArgumentException.class, () -> create().definition(Property.ofValue(invalid)).build().run(runContext()), field.getKey());
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void allLifecycleTasksPropagateErrorClasses() throws Exception {
        record Case(String method, String path, RunnableTask<?> task) { }
        for (int status : new int[]{400, 403, 404, 429, 500}) {
            var cases = java.util.List.of(
                new Case("POST", "/api/v1/monitor", create().build()),
                new Case("PUT", "/api/v1/monitor/123", update().build()),
                new Case("GET", "/api/v1/monitor/123", get().build()),
                new Case("DELETE", "/api/v1/monitor/123", delete().build()),
                new Case("POST", "/api/v1/monitor/validate", validate().build()),
                new Case("POST", "/api/v1/monitor/123/validate", validate().monitorId(Property.ofValue(123L)).build()),
                new Case("GET", "/api/v1/monitor/search", search().build()),
                new Case("GET", "/api/v1/monitor/can_delete", canDelete().build()),
                new Case("GET", "/api/v1/monitor", export().build()),
                new Case("GET", "/api/v1/monitor", Apply.builder().id(IdUtils.create()).type(Apply.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
                    .apiUrl(Property.ofValue(server.url())).definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(ApplyTest.definition()))).build())
            );
            for (Case c : cases) {
                server.reply(c.method(), c.path(), status, "{\"errors\":[\"Request failed\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> c.task().run(runContext()), c.task().getClass().getSimpleName());
                assertEquals(status, error.getStatus());
                assertFalse(error.getMessage().contains(API_KEY));
                assertFalse(error.getMessage().contains(APP_KEY));
            }
        }
    }
}
