package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DashboardsTest extends AbstractDatadogTest {
    @org.junit.jupiter.api.AfterEach
    void requestsMatchPinnedContracts() {
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests,
            "v1:GetDashboard", "v1:ListDashboards", "v1:CreateDashboard", "v1:UpdateDashboard", "v1:DeleteDashboard");
    }

    // Dashboard, Widget and NoteWidgetDefinition from the v1 OpenAPI description.
    private static final String DASHBOARD = """
        {"id":"abc-def-ghi","title":"Orders","layout_type":"ordered","tags":["team:platform"],
         "author_handle":"owner@example.com","created_at":"2026-01-01T00:00:00Z",
         "widgets":[{"id":123,"definition":{"type":"note","content":"Orders runbook"}}]}
        """;

    private Map<String, Object> definition() throws Exception {
        return Dashboards.definition(JacksonMapper.ofJson().readValue(DASHBOARD, Map.class));
    }

    private Import.ImportBuilder<?, ?> importer() throws Exception {
        return Import.builder().id(IdUtils.create()).type(Import.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .definition(definition()).dashboardId(Property.ofValue("abc-def-ghi"));
    }

    private Create.CreateBuilder<?, ?> creator() throws Exception {
        return Create.builder().id(IdUtils.create()).type(Create.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).definition(definition());
    }

    private Get.GetBuilder<?, ?> getter() {
        return Get.builder().id(IdUtils.create()).type(Get.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).dashboardId(Property.ofValue("abc-def-ghi"));
    }

    private Delete.DeleteBuilder<?, ?> deleter() {
        return Delete.builder().id(IdUtils.create()).type(Delete.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).dashboardId(Property.ofValue("abc-def-ghi"));
    }

    private List.ListBuilder<?, ?> lister() {
        return List.builder().id(IdUtils.create()).type(List.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @Test
    void readsOneDashboardAndAuthenticates() throws Exception {
        server.reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD);
        assertEquals("Orders", getter().build().run(runContext()).getDashboard().get("title"));
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
    }

    @Test
    void createsAndRemovesServerOwnedFields() throws Exception {
        server.reply("POST", "/api/v1/dashboard", 200, DASHBOARD);
        var input = JacksonMapper.ofJson().readValue(DASHBOARD, Map.class);
        assertEquals("abc-def-ghi", creator().definition(input).build().run(runContext()).getDashboard().get("id"));
        assertFalse(server.only().json().containsKey("id"));
        assertFalse(server.only().json().containsKey("created_at"));
        assertEquals(definition().get("widgets"), server.only().json().get("widgets"));
    }

    @Test
    void replacesOneDashboard() throws Exception {
        server.reply("PUT", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD);
        var task = Update.builder().id(IdUtils.create()).type(Update.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .dashboardId(Property.ofValue("abc-def-ghi")).definition(definition()).build();
        assertEquals("Orders", task.run(runContext()).getDashboard().get("title"));
        assertEquals("PUT", server.only().method());
        assertEquals(definition(), server.only().json());
    }

    @Test
    void deletesOnlyTheIdentifiedDashboardAndHandlesMissing() throws Exception {
        server.reply("DELETE", "/api/v1/dashboard/abc-def-ghi", 200, "{\"deleted_dashboard_id\":\"abc-def-ghi\"}")
            .reply("DELETE", "/api/v1/dashboard/abc-def-ghi", 404, "{\"errors\":[\"Not found\"]}");
        assertTrue(deleter().build().run(runContext()).getDeleted());
        assertFalse(deleter().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
        assertThrows(DatadogClient.DatadogException.class, () -> deleter().build().run(runContext()));
    }

    @Test
    void validatesEveryRequiredDefinitionFieldBeforeCalling() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> creator().definition(null).build().run(runContext()));
        for (String field : java.util.List.of("title", "layout_type", "widgets")) {
            var missing = definition();
            missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> creator().definition(missing).build().run(runContext()), field);
        }
        for (var invalid : java.util.List.of(Map.of("title", ""), Map.of("title", 1), Map.of("layout_type", "grid"), Map.of("widgets", "invalid"))) {
            var body = definition();
            body.putAll(invalid);
            assertThrows(IllegalArgumentException.class, () -> creator().definition(body).build().run(runContext()));
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void validatesIdsBeforeAnyRequest() {
        for (String id : java.util.List.of("", " ", "../dashboard", "a?b", "a/b")) {
            assertThrows(IllegalArgumentException.class, () -> getter().dashboardId(Property.ofValue(id)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> deleter().dashboardId(Property.ofValue(id)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> getter().dashboardId(null).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void propagatesEachHttpErrorClassWithoutCredentials() {
        for (int status : new int[]{400, 403, 404, 429, 503}) {
            server.reply("GET", "/api/v1/dashboard/abc-def-ghi", status, "{\"errors\":[\"request failed\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> getter().build().run(runContext()));
            assertEquals(status, error.getStatus());
            assertFalse(error.getMessage().contains(API_KEY));
            assertFalse(error.getMessage().contains(APP_KEY));
        }
    }

    @Test
    void propagatesHttpErrorsForEveryDashboardTask() throws Exception {
        for (int status : new int[]{400, 403, 404, 429, 503}) {
            server.reply("POST", "/api/v1/dashboard", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> creator().build().run(runContext())).getStatus());
            server.reply("PUT", "/api/v1/dashboard/abc-def-ghi", status, "{\"errors\":[\"failed\"]}");
            var update = Update.builder().id(IdUtils.create()).type(Update.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
                .apiUrl(Property.ofValue(server.url())).dashboardId(Property.ofValue("abc-def-ghi")).definition(definition()).build();
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> update.run(runContext())).getStatus());
            server.reply("DELETE", "/api/v1/dashboard/abc-def-ghi", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> deleter().build().run(runContext())).getStatus());
            server.reply("GET", "/api/v1/dashboard", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> lister().build().run(runContext())).getStatus());
            server.reply("GET", "/api/v1/dashboard/abc-def-ghi", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> importer().build().run(runContext())).getStatus());
            var export = Export.builder().id(IdUtils.create()).type(Export.class.getName()).apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
                .apiUrl(Property.ofValue(server.url())).dashboardId(Property.ofValue("abc-def-ghi")).build();
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> export.run(runContext())).getStatus());
        }
    }

    @Test
    void malformedListsAndMissingImportIdsFailBeforeWriting() throws Exception {
        for (String response : java.util.List.of("{}", "{\"dashboards\":[1]}")) {
            server.reply("GET", "/api/v1/dashboard", 200, response);
            assertThrows(IllegalStateException.class, () -> lister().build().run(runContext()));
        }
        server.reply("GET", "/api/v1/dashboard/abc-def-ghi", 404, "{\"errors\":[\"not found\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> importer().build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    private String summaries(int count) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < count; index++) {
            rows.add(Map.of("id", "dashboard-" + index, "title", "Dashboard " + index, "layout_type", "ordered"));
        }
        return JacksonMapper.ofJson().writeValueAsString(Map.of("dashboards", rows));
    }

    @Test
    void pagesWithCountStartAndEncodedFilters() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, summaries(100)).reply("GET", "/api/v1/dashboard", 200, summaries(2));
        var out = lister().shared(Property.ofValue(false)).deleted(Property.ofValue(false)).build().run(runContext());
        assertEquals(102, out.getSize());
        assertFalse(out.getTruncated());
        assertEquals(2, server.requests.size());
        assertTrue(server.requests.get(0).query().contains("filter%5Bshared%5D=false"));
        assertTrue(server.requests.get(1).query().contains("start=100"));
    }

    @Test
    void distinguishesExactBoundFromTruncatedResults() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, summaries(3)).reply("GET", "/api/v1/dashboard", 200, summaries(2));
        assertTrue(lister().maxResults(Property.ofValue(2)).build().run(runContext()).getTruncated());
        assertFalse(lister().maxResults(Property.ofValue(2)).build().run(runContext()).getTruncated());
        for (int limit : new int[]{0, -1, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> lister().maxResults(Property.ofValue(limit)).build().run(runContext()));
        }
        assertEquals(2, server.requests.size());
    }

    @Test
    void storesInventoryAsIonWithoutInlineRows() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, summaries(2));
        var task = lister().store(Property.ofValue(true)).build();
        RunContext context = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(context);
        assertNull(output.getDashboards());
        assertEquals("kestra", output.getUri().getScheme());
        try (var input = context.storage().getFile(output.getUri()); var rows = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(2, rows.readAll().size());
        }
    }

    @Test
    void exportThenImportIsANoOpOnBothRuns() throws Exception {
        server.reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD);
        var export = Export.builder().id(IdUtils.create()).type(Export.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .dashboardId(Property.ofValue("abc-def-ghi")).build();
        RunContext context = TestsUtils.mockRunContext(runContextFactory, export, Map.of());
        var exported = export.run(context);
        try (var input = context.storage().getFile(exported.getUri())) {
            var body = JacksonMapper.ofJson().readValue(input, Map.class);
            assertFalse(body.containsKey("id"));
            assertFalse(body.containsKey("author_handle"));
        }
        var task = importer().definition(null).from(Property.ofValue(exported.getUri().toString())).build();
        assertEquals(Import.Action.UNCHANGED, task.run(context).getAction());
        var second = task.run(context);
        assertEquals(Import.Action.UNCHANGED, second.getAction());
        assertTrue(second.getDiff().isEmpty());
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void appliesNestedDiffOnceAndPreservesOmittedTopLevelFields() throws Exception {
        server.reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD)
            .reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD.replace("Orders runbook", "Updated runbook"))
            .reply("PUT", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD.replace("Orders runbook", "Updated runbook"));
        var body = definition();
        body.remove("tags");
        body.put("widgets", java.util.List.of(Map.of("definition", Map.of("type", "note", "content", "Updated runbook"))));
        var task = importer().definition(body).build();
        var first = task.run(runContext());
        assertEquals(Import.Action.UPDATED, first.getAction());
        assertEquals(1, first.getDiff().size());
        assertEquals("/widgets/0/definition/content", first.getDiff().getFirst().getPath());
        assertEquals("Orders runbook", first.getDiff().getFirst().getBefore());
        assertEquals("Updated runbook", first.getDiff().getFirst().getAfter());
        assertEquals(java.util.List.of("team:platform"), server.requests.get(1).json().get("tags"));
        assertEquals(Import.Action.UNCHANGED, task.run(runContext()).getAction());
        assertEquals(1, server.requests.stream().filter(request -> request.method().equals("PUT")).count());
    }

    @Test
    void createsByTitleAndTeamThenFindsTheSameDashboard() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, "{\"dashboards\":[]}")
            .reply("GET", "/api/v1/dashboard", 200, "{\"dashboards\":[{\"id\":\"abc-def-ghi\",\"title\":\"Orders\"}]}")
            .reply("POST", "/api/v1/dashboard", 200, DASHBOARD)
            .reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD);
        var task = importer().dashboardId(null).managingTag(Property.ofValue("team:platform")).build();
        var first = task.run(runContext());
        assertEquals(Import.Action.CREATED, first.getAction());
        assertEquals("abc-def-ghi", first.getDashboardId());
        assertFalse(first.getDiff().isEmpty());
        assertEquals(Import.Action.UNCHANGED, task.run(runContext()).getAction());
        assertEquals(1, server.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test
    void dryRunNeverCreatesOrUpdates() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, "{\"dashboards\":[]}")
            .reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD);
        var create = importer().dashboardId(null).managingTag(Property.ofValue("team:platform")).dryRun(Property.ofValue(true)).build().run(runContext());
        assertEquals(Import.Action.CREATED, create.getAction());
        assertNull(create.getDashboardId());
        var body = definition();
        body.put("title", "New title");
        var update = importer().definition(body).dryRun(Property.ofValue(true)).build().run(runContext());
        assertEquals(Import.Action.UPDATED, update.getAction());
        assertEquals("/title", update.getDiff().getFirst().getPath());
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void refusesAmbiguousOrIncompleteDiscovery() throws Exception {
        server.reply("GET", "/api/v1/dashboard", 200, "{\"dashboards\":[{\"id\":\"a\",\"title\":\"Orders\"},{\"id\":\"b\",\"title\":\"Orders\"}]}")
            .reply("GET", "/api/v1/dashboard/a", 200, DASHBOARD).reply("GET", "/api/v1/dashboard/b", 200, DASHBOARD);
        var task = importer().dashboardId(null).managingTag(Property.ofValue("team:platform"));
        assertThrows(IllegalStateException.class, () -> task.build().run(runContext()));
        assertThrows(IllegalStateException.class, () -> task.maxResults(Property.ofValue(1)).build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void importValidatesIdentityAndSourceBeforeAnyHttpCall() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> importer().dashboardId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> importer().managingTag(Property.ofValue("team:platform")).build().run(runContext()));
        for (String tag : java.util.List.of("env:prod", "team:missing", "team:", "")) {
            assertThrows(IllegalArgumentException.class, () -> importer().dashboardId(null).managingTag(Property.ofValue(tag)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> importer().definition(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> importer().from(Property.ofValue("kestra:///a.json")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> importer().definition(null).from(Property.ofValue("https://example.com/a.json")).build().run(runContext()));
        var redacted = definition();
        redacted.put("description", Dashboards.REDACTED);
        assertThrows(IllegalArgumentException.class, () -> importer().definition(redacted).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> importer().maxResults(Property.ofValue(0)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void ignoresNumericRepresentationButKeepsMeaningfulNestedIdsAndArrayOrder() {
        var before = Map.<String, Object>of("widgets", java.util.List.of(Map.of("id", 1, "definition", Map.of("id", 7, "value", 1))));
        var same = Map.<String, Object>of("widgets", java.util.List.of(Map.of("id", 2L, "definition", Map.of("id", 7L, "value", 1.0))));
        assertTrue(Dashboards.diff(before, same, false).isEmpty());
        var changed = Map.<String, Object>of("widgets", java.util.List.of(Map.of("definition", Map.of("id", 8, "value", 1))));
        assertEquals("/widgets/0/definition/id", Dashboards.diff(before, changed, false).getFirst().getPath());
        var tabsBefore = new LinkedHashMap<>(before);
        tabsBefore.put("tabs", java.util.List.of(Map.of("id", "tab", "widget_ids", java.util.List.of(1))));
        var tabsAfter = new LinkedHashMap<>(same);
        tabsAfter.put("tabs", tabsBefore.get("tabs"));
        assertFalse(Dashboards.diff(tabsBefore, tabsAfter, false).isEmpty());
        assertFalse(Dashboards.diff(Map.of("tags", java.util.List.of("a", "b")), Map.of("tags", java.util.List.of("b", "a")), false).isEmpty());
    }

    @Test
    void redactsCredentialFieldsUrlsAndDiffsUnlessExplicitlyIncluded() throws Exception {
        var fields = Map.<String, Object>of("api_key", "test-value", "url", "https://example.com/webhook/private", "password", "old");
        var hidden = Dashboards.redact(fields, false);
        assertEquals(Dashboards.REDACTED, hidden.get("api_key"));
        assertEquals(Dashboards.REDACTED, hidden.get("url"));
        assertEquals(fields, Dashboards.redact(fields, true));
        var diff = Dashboards.diff(Map.of("password", "old"), Map.of("password", "new"), false);
        assertEquals(Dashboards.REDACTED, diff.getFirst().getBefore());
        assertEquals(Dashboards.REDACTED, diff.getFirst().getAfter());
        var nested = Dashboards.diff(Map.of("token", Map.of("value", "old")), Map.of("token", Map.of("value", "new")), false);
        assertEquals(Dashboards.REDACTED, nested.getFirst().getBefore());
        assertEquals(Dashboards.REDACTED, nested.getFirst().getAfter());
        assertEquals(Dashboards.REDACTED, Dashboards.redact("https://hooks.slack.com/services/test/value", false));
        assertEquals(Dashboards.REDACTED, Dashboards.redact("https://username:password@example.com/image", false));
        server.reply("GET", "/api/v1/dashboard/abc-def-ghi", 200, DASHBOARD.replace("Orders runbook", "https://example.com/webhook/private"));
        String output = JacksonMapper.ofJson().writeValueAsString(getter().build().run(runContext()));
        assertFalse(output.contains("/webhook/private"));
        assertTrue(JacksonMapper.ofJson().writeValueAsString(getter().includeSecrets(Property.ofValue(true)).build().run(runContext())).contains("/webhook/private"));
    }
}
