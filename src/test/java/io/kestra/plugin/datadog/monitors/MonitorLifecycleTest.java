package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).monitorIds(Property.ofValue(List.of(123L, 124L)));
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
    void listPreservesMessagesInStoredRowsAndRejectsMalformedRows() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v1/monitor", 200, "[{\"id\":123,\"message\":\"https://hooks.example.org/value\"}]");
        var task = io.kestra.plugin.datadog.monitors.List.builder().id(IdUtils.create()).type(io.kestra.plugin.datadog.monitors.List.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url())).fetchType(Property.ofValue(FetchType.STORE)).build();
        var output = task.run(context);
        assertNull(output.getMonitors());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("https://hooks.example.org/value", JacksonMapper.ofIon().readValue(input, Map.class).get("message"));
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
        assertEquals(List.of("/api/v1/monitor/validate", "/api/v1/monitor/123/validate"), server.requests.stream().map(r -> r.path()).toList());
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
        assertEquals(Map.of("ok", List.of(123)), output.getResult().get("data"));
        assertEquals("monitor_ids=123,124", URLDecoder.decode(server.only().query(), StandardCharsets.UTF_8));
    }

    @Test
    void deletionConflictReturnsDependencyErrors() throws Exception {
        var response = Map.of("data", Map.of("ok", List.of(123)), "errors", Map.of("124", List.of("Referenced in composite monitor 125")));
        server.reply("GET", "/api/v1/monitor/can_delete", 409, JacksonMapper.ofJson().writeValueAsString(response));
        assertEquals(response, canDelete().build().run(runContext()).getResult());
        assertEquals("GET", server.only().method());
    }

    @Test
    void deserializedDeletionCheckAcceptsIntegerIds() throws Exception {
        var task = YamlParser.parse(JacksonMapper.ofJson().writeValueAsString(canDelete().build()), CanDelete.class);
        server.reply("GET", "/api/v1/monitor/can_delete", 200, "{\"data\":{\"ok\":[123,124]}}");
        var output = task.run(runContextFactory.of(task, Map.of()));
        assertEquals(Map.of("ok", List.of(123, 124)), output.getResult().get("data"));
        assertEquals("monitor_ids=123,124", URLDecoder.decode(server.only().query(), StandardCharsets.UTF_8));
    }

    @Test
    void dryRunsValidateWithoutPersistingChanges() throws Exception {
        var created = create().dryRun(Property.ofValue(true)).build().run(runContext());
        assertTrue(created.getDryRun());
        assertNull(created.getMonitorId());
        server.reply("GET", "/api/v1/monitor/123", 200, "{\"id\":123,\"type\":\"query alert\",\"query\":\"test\",\"message\":\"Old\"}");
        var updated = update().dryRun(Property.ofValue(true)).build().run(runContext());
        assertTrue(updated.getDryRun());
        assertFalse(updated.getUpdated());
        server.reply("GET", "/api/v1/monitor/123", 200, "{\"id\":123}");
        server.reply("GET", "/api/v1/monitor/can_delete", 200, "{\"data\":{\"ok\":[123]}}");
        var deleted = delete().dryRun(Property.ofValue(true)).build().run(runContext());
        assertTrue(deleted.getDryRun());
        assertFalse(deleted.getDeleted());
        assertEquals(List.of("/api/v1/monitor/validate", "/api/v1/monitor/123", "/api/v1/monitor/123/validate", "/api/v1/monitor/123", "/api/v1/monitor/can_delete"), server.requests.stream().map(r -> r.path()).toList());
        assertEquals(ApplyTest.definition(), server.requests.getFirst().json());
        assertEquals(Map.of("type", "query alert", "query", "test", "message", "Updated"), server.requests.get(2).json());
        assertTrue(server.requests.stream().noneMatch(r -> r.method().equals("PUT") || r.method().equals("DELETE") || r.method().equals("POST") && r.path().equals("/api/v1/monitor")));
    }

    @Test
    void dryRunsStillFailValidationAndDependencyErrors() {
        server.reply("POST", "/api/v1/monitor/validate", 400, "{\"errors\":[\"Invalid query\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> create().dryRun(Property.ofValue(true)).build().run(runContext()));
        server.reply("POST", "/api/v1/monitor/123/validate", 400, "{\"errors\":[\"Invalid query\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> update().dryRun(Property.ofValue(true)).build().run(runContext()));
        server.reply("GET", "/api/v1/monitor/123", 200, "{\"id\":123}");
        server.reply("GET", "/api/v1/monitor/can_delete", 409, "{\"data\":{\"ok\":[]},\"errors\":{\"123\":[\"Referenced\"]}}");
        assertEquals(409, assertThrows(DatadogClient.DatadogException.class, () -> delete().dryRun(Property.ofValue(true)).build().run(runContext())).getStatus());
        assertTrue(server.requests.stream().noneMatch(r -> r.method().equals("DELETE")));
    }

    @Test
    void dryRunOnlyIgnores404WhenRequested() throws Exception {
        server.reply("GET", "/api/v1/monitor/123", 404, "{\"errors\":[\"Monitor not found\"]}");
        assertFalse(delete().dryRun(Property.ofValue(true)).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
        server.reply("GET", "/api/v1/monitor/123", 403, "{\"errors\":[\"Forbidden\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> delete().dryRun(Property.ofValue(true)).ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
        server.reply("GET", "/api/v1/monitor/123", 200, "{\"id\":123,\"type\":\"query alert\",\"query\":\"test\"}");
        server.reply("POST", "/api/v1/monitor/123/validate", 404, "{\"errors\":[\"Monitor not found\"]}");
        assertFalse(update().dryRun(Property.ofValue(true)).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getUpdated());
    }

    @Test
    void searchPagesAndFacetsAreNotSummed() throws Exception {
        var rows = IntStream.range(0, 100).mapToObj(i -> Map.of("id", i, "name", "CPU " + i, "type", "query alert", "status", "Alert")).toList();
        var facets = Map.of("status", List.of(Map.of("name", "Alert", "count", 101)));
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
        var output = search().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertNull(output.getMonitors());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(123, JacksonMapper.ofIon().readValue(input, Map.class).get("id"));
        }
        server.reply("GET", "/api/v1/monitor/search", 200, "{}");
        assertThrows(IllegalStateException.class, () -> search().build().run(context));
    }

    @Test
    void exportPreservesMessagesAndNewFieldsAndIonRoundTrips() throws Exception {
        var context = runContext();
        var response = ApplyTest.definition();
        response.put("message", "Runbook https://example.org/runbook @team-platform {{#is_alert}}Check CPU{{/is_alert}}");
        response.put("future_option", Map.of("enabled", true));
        var desired = new LinkedHashMap<>(response);
        response.put("id", 123);
        response.put("overall_state", "Alert");
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(List.of(response)));
        var output = export().build().run(context);
        assertEquals(1, output.getSize());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(desired, JacksonMapper.ofIon().readValue(input, Map.class));
        }
        server.reply("GET", "/api/v1/monitor/123", 200, JacksonMapper.ofJson().writeValueAsString(response));
        var applied = Apply.builder().id(IdUtils.create()).type(Apply.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url())).from(output.getUri().toString()).dryRun(Property.ofValue(true)).build().run(context);
        assertEquals(Apply.Action.UNCHANGED, applied.getResults().getFirst().getAction());
        assertTrue(server.requests.stream().allMatch(r -> r.method().equals("GET")));

        var yaml = export().format(Property.ofValue(Export.Format.YAML)).build().run(context);
        try (var input = context.storage().getFile(yaml.getUri())) {
            assertEquals(List.of(desired), JacksonMapper.ofYaml().readValue(input, List.class));
        }
    }

    @Test
    void exportIonAndRefusesTruncation() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(List.of(ApplyTest.definition(), ApplyTest.definition())));
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
        assertThrows(IllegalArgumentException.class, () -> get().monitorId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> update().monitorId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> delete().monitorId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> canDelete().monitorIds(Property.ofValue(List.of())).build().run(runContext()));
        for (var id : new long[]{0, -1}) {
            var tasks = List.of(
                get().monitorId(Property.ofValue(id)).build(),
                update().monitorId(Property.ofValue(id)).build(),
                delete().monitorId(Property.ofValue(id)).build(),
                validate().monitorId(Property.ofValue(id)).build());
            for (var task : tasks) {
                assertThrows(jakarta.validation.ConstraintViolationException.class, () -> ((RunnableTask<?>) task).run(runContextFactory.of(task, Map.of())), task.getClass().getSimpleName());
            }
            var canDelete = canDelete().monitorIds(Property.ofValue(List.of(id))).build();
            assertThrows(IllegalArgumentException.class, () -> canDelete.run(runContextFactory.of(canDelete, Map.of())));
        }
        for (var limit : new int[]{0, 10001}) {
            var tasks = List.of(search().maxResults(Property.ofValue(limit)).build(), export().maxMonitors(Property.ofValue(limit)).build());
            for (var task : tasks) {
                assertThrows(jakarta.validation.ConstraintViolationException.class, () -> ((RunnableTask<?>) task).run(runContextFactory.of(task, Map.of())), task.getClass().getSimpleName());
            }
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void tasksWithDifferentDefinitionsAreDistinctAndInputsStayOutOfStringOutput() {
        var first = Apply.builder().id("apply").type(Apply.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .from(Map.of("message", "private-definition-one")).build();
        var second = Apply.builder().id("apply").type(Apply.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .from(Map.of("message", "private-definition-two")).build();
        assertNotEquals(first, second);
        var builder = create();
        var create = builder.definition(Property.ofValue(Map.of("message", "private-definition-one"))).build();
        assertNotEquals(create, builder.definition(Property.ofValue(Map.of("message", "private-definition-two"))).build());
        for (var task : List.of(first, create)) {
            assertFalse(task.toString().contains(API_KEY));
            assertFalse(task.toString().contains(APP_KEY));
            assertFalse(task.toString().contains("private-definition-one"));
        }
    }

    @Test
    void allLifecycleTasksPropagateErrorClasses() throws Exception {
        record Case(String method, String path, RunnableTask<?> task) { }
        for (var status : new int[]{400, 403, 404, 429, 500}) {
            var cases = List.of(
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
                    .apiUrl(Property.ofValue(server.url())).from(ApplyTest.definition()).build())
            );
            for (var c : cases) {
                server.reply(c.method(), c.path(), status, "{\"errors\":[\"Request failed\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> c.task().run(runContext()), c.task().getClass().getSimpleName());
                assertEquals(status, error.getStatus());
                assertFalse(error.getMessage().contains(API_KEY));
                assertFalse(error.getMessage().contains(APP_KEY));
            }
        }
    }
}
