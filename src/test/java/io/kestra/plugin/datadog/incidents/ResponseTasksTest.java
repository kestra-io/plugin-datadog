package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Request fixtures derived from the referenced v2 operation request and response schemas. */
class ResponseTasksTest extends AbstractDatadogTest {
    static final String ID = "00000000-0000-0000-0000-000000000001";
    static final String OTHER = "00000000-0000-0000-0000-000000000002";
    static final String INCIDENT = """
        {"data":{"id":"00000000-0000-0000-0000-000000000001","type":"incidents","attributes":{"title":"Orders unavailable","customer_impacted":false}}}
        """;
    static final String CASE = """
        {"data":{"id":"00000000-0000-0000-0000-000000000001","type":"case","attributes":{"title":"Follow up","priority":"P2","status_name":"Open"}}}
        """;
    static final String PAGE = """
        {"data":{"id":"00000000-0000-0000-0000-000000000001","type":"pages"}}
        """;
    static final String STATUS_PAGE = """
        {"data":{"id":"00000000-0000-0000-0000-000000000001","type":"status_pages","attributes":{"name":"Orders","domain_prefix":"orders","enabled":true}}}
        """;
    static final String DEGRADATION = """
        {"data":{"id":"00000000-0000-0000-0000-000000000001","type":"degradations","attributes":{"title":"Orders unavailable","status":"investigating"}}}
        """;

    record Contract(String task, String operation, String method, String path, Map<String, Object> properties,
                    java.util.List<String> required, Map<String, Object> body, int status, String response, String query) {
    }

    static Map<String, Object> body(String type, String id, Map<String, Object> attributes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        if (id != null) data.put("id", id);
        if (attributes != null) data.put("attributes", attributes);
        return Map.of("data", data);
    }

    static java.util.List<Contract> contracts() {
        var c = new ArrayList<Contract>();
        String incidents = "/api/v2/incidents";
        c.add(new Contract("incidents.Create", "CreateIncident", "POST", incidents,
            Map.of("title", "Orders unavailable", "customerImpacted", false, "dryRun", false),
            java.util.List.of("title", "customerImpacted"), body("incidents", null, Map.of("title", "Orders unavailable", "customer_impacted", false)), 201, INCIDENT, null));
        c.add(new Contract("incidents.Get", "GetIncident", "GET", incidents + "/" + ID, Map.of("incidentId", ID),
            java.util.List.of("incidentId"), null, 200, INCIDENT, null));
        c.add(new Contract("incidents.Update", "UpdateIncident", "PATCH", incidents + "/" + ID,
            Map.of("incidentId", ID, "severity", "SEV_2"), java.util.List.of("incidentId"),
            body("incidents", ID, Map.of("fields", Map.of("severity", Map.of("type", "dropdown", "value", "SEV-2")))), 200, INCIDENT, null));
        c.add(new Contract("incidents.Delete", "DeleteIncident", "DELETE", incidents + "/" + ID, Map.of("incidentId", ID),
            java.util.List.of("incidentId"), null, 204, "", null));
        c.add(new Contract("incidents.List", "ListIncidents", "GET", incidents, Map.of(), java.util.List.of(), null, 200,
            "{\"data\":[" + jsonData(INCIDENT) + "]}", "page[size]=50&page[offset]=0"));
        c.add(new Contract("incidents.Search", "SearchIncidents", "GET", incidents + "/search", Map.of("query", "state:active", "sort", "CREATED_DESC"),
            java.util.List.of("query"), null, 200,
            "{\"data\":{\"type\":\"incidents_search_results\",\"attributes\":{\"incidents\":[" + INCIDENT + "],\"facets\":{},\"total\":1}}}",
            "query=state:active&sort=-created&page[size]=50&page[offset]=0"));
        String pages = "/api/v2/on-call/pages";
        c.add(new Contract("oncall.Page", "CreateOnCallPage", "POST", pages,
            Map.of("title", "Orders unavailable", "targetType", "TEAM_HANDLE", "target", "orders", "urgency", "HIGH", "tags", java.util.List.of("env:prod"), "dryRun", false),
            java.util.List.of("title", "targetType", "target", "urgency"),
            body("pages", null, Map.of("title", "Orders unavailable", "target", Map.of("type", "team_handle", "identifier", "orders"), "urgency", "high", "tags", java.util.List.of("env:prod"))), 200, PAGE, null));
        for (String action : java.util.List.of("Acknowledge", "Resolve", "Escalate")) {
            c.add(new Contract("oncall." + action, action + "OnCallPage", "POST", pages + "/" + ID + "/" + action.toLowerCase(java.util.Locale.ROOT),
                Map.of("pageId", ID, "dryRun", false), java.util.List.of("pageId"), null, 202, "", null));
        }
        c.add(new Contract("oncall.WhoIsOnCall", "GetTeamOnCallUsers", "GET", "/api/v2/on-call/teams/" + ID + "/on-call",
            Map.of("teamId", ID), java.util.List.of("teamId"), null, 200,
            "{\"data\":{\"id\":\"" + ID + "\",\"type\":\"team_oncall_responders\",\"relationships\":{\"responders\":{\"data\":[{\"id\":\"" + OTHER + "\",\"type\":\"users\"}]}}}}",
            "include=responders,escalations.responders"));
        String cases = "/api/v2/cases";
        Map<String, Object> creation = body("case", null, Map.of("title", "Follow up", "type_id", OTHER, "description", "Review remediation", "priority", "P2", "status_name", "Open"));
        data(creation).put("relationships", Map.of("project", Map.of("data", Map.of("type", "project", "id", ID))));
        c.add(new Contract("cases.Create", "CreateCase", "POST", cases,
            Map.of("title", "Follow up", "caseTypeId", OTHER, "projectId", ID, "caseDescription", "Review remediation", "priority", "P2", "statusName", "Open"),
            java.util.List.of("title", "caseTypeId", "projectId"), creation, 201, CASE, null));
        c.add(new Contract("cases.Get", "GetCase", "GET", cases + "/" + ID, Map.of("caseId", ID), java.util.List.of("caseId"), null, 200, CASE, null));
        c.add(new Contract("cases.Search", "SearchCases", "GET", cases, Map.of("filter", "status:OPEN", "sortField", "CREATED_AT", "ascending", true),
            java.util.List.of(), null, 200, "{\"data\":[" + jsonData(CASE) + "]}", "filter=status:OPEN&sort[field]=created_at&sort[asc]=true&page[size]=50&page[number]=0"));
        c.add(new Contract("cases.Assign", "AssignCase", "POST", cases + "/" + ID + "/assign", Map.of("caseId", ID, "assigneeId", OTHER),
            java.util.List.of("caseId", "assigneeId"), body("case", null, Map.of("assignee_id", OTHER)), 200, CASE, null));
        c.add(new Contract("cases.SetStatus", "UpdateStatus", "POST", cases + "/" + ID + "/status", Map.of("caseId", ID, "statusName", "Closed"),
            java.util.List.of("caseId", "statusName"), body("case", null, Map.of("status_name", "Closed")), 200, CASE, null));
        c.add(new Contract("cases.SetPriority", "UpdatePriority", "POST", cases + "/" + ID + "/priority", Map.of("caseId", ID, "priority", "P1"),
            java.util.List.of("caseId", "priority"), body("case", null, Map.of("priority", "P1")), 200, CASE, null));
        c.add(new Contract("cases.Comment", "CommentCase", "POST", cases + "/" + ID + "/comment", Map.of("caseId", ID, "comment", "Remediated"),
            java.util.List.of("caseId", "comment"), body("case", null, Map.of("comment", "Remediated")), 200,
            "{\"data\":[{\"id\":\"" + ID + "\",\"type\":\"timeline_cell\",\"attributes\":{\"type\":\"COMMENT\",\"cell_content\":{\"message\":\"Remediated\"},\"created_at\":\"2024-01-01T00:00:00Z\"}}]}", null));
        String status = "/api/v2/statuspages";
        c.add(new Contract("statuspages.List", "ListStatusPages", "GET", status, Map.of("name", "Orders", "domainPrefix", "orders"),
            java.util.List.of(), null, 200, "{\"data\":[" + jsonData(STATUS_PAGE) + "]}", "filter[name]=Orders&filter[domain_prefix]=orders&page[limit]=50&page[offset]=0"));
        c.add(new Contract("statuspages.Get", "GetStatusPage", "GET", status + "/" + ID, Map.of("pageId", ID), java.util.List.of("pageId"), null, 200, STATUS_PAGE, null));
        c.add(new Contract("statuspages.CreateDegradation", "CreateDegradation", "POST", status + "/" + ID + "/degradations",
            Map.of("pageId", ID, "title", "Orders unavailable", "status", "INVESTIGATING", "message", "Investigating", "components", Map.of(OTHER, "DEGRADED"), "dryRun", false),
            java.util.List.of("pageId", "title", "status", "components"), body("degradations", null, Map.of("title", "Orders unavailable", "status", "investigating", "description", "Investigating", "components_affected", java.util.List.of(Map.of("id", OTHER, "status", "degraded")))),
            201, DEGRADATION, "notify_subscribers=false"));
        c.add(new Contract("statuspages.UpdateDegradation", "UpdateDegradation", "PATCH", status + "/" + ID + "/degradations/" + OTHER,
            Map.of("pageId", ID, "degradationId", OTHER, "status", "RESOLVED", "message", "Recovered", "dryRun", false),
            java.util.List.of("pageId", "degradationId"), body("degradations", OTHER, Map.of("status", "resolved", "description", "Recovered")), 200, DEGRADATION, "notify_subscribers=false"));
        return c;
    }

    static String jsonData(String body) {
        try {
            return JacksonMapper.ofJson().writeValueAsString(JacksonMapper.ofJson().readValue(body, Map.class).get("data"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    RunnableTask<?> task(String name, Map<String, Object> properties) {
        Map<String, Object> config = new LinkedHashMap<>(properties);
        config.put("id", IdUtils.create());
        config.put("type", "io.kestra.plugin.datadog." + name);
        config.put("apiKey", API_KEY);
        config.put("appKey", APP_KEY);
        config.put("apiUrl", server.url());
        return (RunnableTask<?>) JacksonMapper.ofJson().convertValue(config, Task.class);
    }

    @TestFactory
    Stream<DynamicTest> happyPaths() {
        return contracts().stream().map(c -> DynamicTest.dynamicTest(c.task + " " + c.operation, () -> {
            server.requests.clear();
            server.reply(c.method, c.path, c.status, c.response);
            Object result = task(c.task, c.properties).run(runContext());
            var request = server.only();
            assertEquals(c.method, request.method());
            assertEquals(c.path, request.path());
            assertEquals(c.query, request.query() == null ? null : java.net.URLDecoder.decode(request.query(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(API_KEY, request.header("DD-API-KEY"));
            assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
            if (c.body == null) assertTrue(request.body().isEmpty());
            else assertEquals(c.body, request.json());
            if (result instanceof ResponseOutput output) {
                assertEquals(c.status, output.getStatusCode());
                assertFalse(output.isDryRun());
                if (c.status != 204 && c.status != 202 && !c.task.equals("cases.Comment")) assertEquals(ID, output.getId());
            } else {
                assertEquals(1, ((CollectionOutput) result).getCount());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> httpErrorsForEveryTask() {
        return contracts().stream().flatMap(c -> Stream.of(400, 403, 404, 429, 503).map(status -> DynamicTest.dynamicTest(c.task + " HTTP " + status, () -> {
            server.requests.clear();
            server.reply(c.method, c.path, status, "{\"errors\":[\"contract failure\"]}");
            var exception = assertThrows(DatadogClient.DatadogException.class, () -> task(c.task, c.properties).run(runContext()));
            assertEquals(status, exception.getStatus());
            assertTrue(exception.getMessage().contains("contract failure"));
            assertFalse(exception.getMessage().contains(API_KEY));
            assertFalse(exception.getMessage().contains(APP_KEY));
            // The shared Kestra HTTP client retries 429 and server errors.
            assertFalse(server.requests.isEmpty());
            assertTrue(server.requests.stream().allMatch(request -> c.path.equals(request.path()) && c.method.equals(request.method())));
        })));
    }

    @TestFactory
    Stream<DynamicTest> missingRequiredValuesSendNothing() {
        return contracts().stream().flatMap(c -> c.required.stream().map(property -> DynamicTest.dynamicTest(c.task + " requires " + property, () -> {
            server.requests.clear();
            Map<String, Object> values = new LinkedHashMap<>(c.properties);
            values.remove(property);
            assertThrows(IllegalArgumentException.class, () -> task(c.task, values).run(runContext()));
            assertTrue(server.requests.isEmpty());
        })));
    }

    @TestFactory
    Stream<DynamicTest> blankStringsSendNothing() {
        return contracts().stream().flatMap(c -> c.required.stream().filter(p -> c.properties.get(p) instanceof String)
            .map(property -> DynamicTest.dynamicTest(c.task + " rejects blank " + property, () -> {
                server.requests.clear();
                Map<String, Object> values = new LinkedHashMap<>(c.properties);
                values.put(property, " ");
                assertThrows(Exception.class, () -> task(c.task, values).run(runContext()));
                assertTrue(server.requests.isEmpty());
            })));
    }

    @Test
    void allExamplesParseAsFlows() throws Exception {
        for (Contract c : contracts()) {
            Class<?> type = Class.forName("io.kestra.plugin.datadog." + c.task);
            for (var example : type.getAnnotation(Plugin.class).examples()) {
                assertTrue(example.full());
                Flow flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                assertEquals(type, flow.getTasks().getFirst().getClass());
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> dryRunsSendNothingAndMatchLiveRequests() {
        return contracts().stream().filter(c -> c.properties.containsKey("dryRun")).map(c -> DynamicTest.dynamicTest(c.task + " dry-run guard", () -> {
            server.requests.clear();
            Map<String, Object> props = new LinkedHashMap<>(c.properties);
            props.remove("dryRun");
            ResponseOutput byDefault = (ResponseOutput) task(c.task, props).run(runContext());
            props.put("dryRun", true);
            ResponseOutput explicit = (ResponseOutput) task(c.task, props).run(runContext());
            assertTrue(server.requests.isEmpty(), "Dry run must send zero requests");
            assertTrue(byDefault.isDryRun());
            assertNull(byDefault.getStatusCode());
            assertEquals(byDefault.getRequest(), explicit.getRequest());
            assertEquals(c.method, explicit.getRequest().get("method"));
            assertEquals(server.url() + c.path, explicit.getRequest().get("url"));
            assertEquals(c.body, explicit.getRequest().get("body"));
            assertFalse(explicit.getRequest().toString().contains(API_KEY));
            server.reply(c.method, c.path, c.status, c.response);
            task(c.task, c.properties).run(runContext());
            if (c.body != null) assertEquals(explicit.getRequest().get("body"), server.only().json());
        }));
    }

    @Test
    void patchOmitsUnsetFieldsAndPreservesFalse() throws Exception {
        server.reply("PATCH", "/api/v2/incidents/" + ID, 200, INCIDENT);
        task("incidents.Update", Map.of("incidentId", ID, "title", "Recovered")).run(runContext());
        assertEquals(body("incidents", ID, Map.of("title", "Recovered")), server.only().json());
        server.requests.clear();
        task("incidents.Update", Map.of("incidentId", ID, "customerImpacted", false)).run(runContext());
        assertEquals(body("incidents", ID, Map.of("customer_impacted", false)), server.only().json());
        server.requests.clear();
        task("incidents.Update", Map.of("incidentId", ID, "commanderUserId", OTHER)).run(runContext());
        Map<String, Object> expected = body("incidents", ID, null);
        data(expected).put("relationships", Map.of("commander_user", Map.of("data", Map.of("type", "users", "id", OTHER))));
        assertEquals(expected, server.only().json());
    }

    @Test
    void incidentCommonFieldsAndCommanderRenderExactly() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("title", "{{ 'Orders' }}", "customerImpacted", true,
            "commanderUserId", OTHER, "severity", "SEV_1", "isTest", true, "customerImpactScope", "Checkout"));
        properties.put("fields", Map.of("services", Map.of("type", "multiselect", "value", java.util.List.of("orders"))));
        ResponseOutput output = (ResponseOutput) task("incidents.Create", properties).run(runContext());
        Map<String, Object> expected = body("incidents", null, Map.of("title", "Orders", "customer_impacted", true,
            "is_test", true, "customer_impact_scope", "Checkout", "fields", Map.of("severity", Map.of("type", "dropdown", "value", "SEV-1"),
                "services", Map.of("type", "multiselect", "value", java.util.List.of("orders")))));
        data(expected).put("relationships", Map.of("commander_user", Map.of("data", Map.of("type", "users", "id", OTHER))));
        assertEquals(expected, output.getRequest().get("body"));
        assertTrue(server.requests.isEmpty());
        server.reply("PATCH", "/api/v2/incidents/" + ID, 200, INCIDENT);
        task("incidents.Update", Map.of("incidentId", ID, "state", "RESOLVED")).run(runContext());
        assertEquals(body("incidents", ID, Map.of("fields", Map.of("state", Map.of("type", "dropdown", "value", "resolved")))), server.only().json());
    }

    @TestFactory
    Stream<DynamicTest> invalidIncidentFields() {
        return Stream.<Object>of("bad", Map.of("type", "unknown", "value", "bad"), Map.of("type", "dropdown", "value", java.util.List.of("bad")),
            Map.of("type", "multiselect", "value", "bad"), Map.of("type", "multiselect", "value", java.util.List.of(1)))
            .flatMap(value -> Stream.of("incidents.Create", "incidents.Update").map(name -> DynamicTest.dynamicTest(name + " invalid field " + value, () -> {
                server.requests.clear();
                Map<String, Object> props = new LinkedHashMap<>(Map.of("fields", Map.of("custom", value)));
                if (name.endsWith("Create")) props.putAll(Map.of("title", "Orders", "customerImpacted", true));
                else props.put("incidentId", ID);
                assertThrows(IllegalArgumentException.class, () -> task(name, props).run(runContext()));
                assertTrue(server.requests.isEmpty());
            })));
    }

    @Test
    void emptyPatchAndConflictingFieldsFailBeforeHttp() {
        assertThrows(IllegalArgumentException.class, () -> task("incidents.Update", Map.of("incidentId", ID)).run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task("incidents.Update", Map.of("incidentId", ID, "severity", "SEV_1",
            "fields", Map.of("severity", Map.of("type", "dropdown", "value", "SEV-2")))).run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task("incidents.Update", Map.of("incidentId", ID, "state", "ACTIVE",
            "fields", Map.of("state", Map.of("type", "dropdown", "value", "resolved")))).run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task("statuspages.UpdateDegradation", Map.of("pageId", ID, "degradationId", OTHER)).run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @TestFactory
    Stream<DynamicTest> pathIdentifiersCannotChangeTheEndpoint() {
        return contracts().stream().flatMap(c -> c.required.stream().filter(p -> p.endsWith("Id"))
            .flatMap(property -> Stream.of("../other", "id?query=x", "id/child", "id#fragment", "id%2fchild").map(value -> DynamicTest.dynamicTest(c.task + " rejects " + property + "=" + value, () -> {
                server.requests.clear();
                Map<String, Object> props = new LinkedHashMap<>(c.properties);
                props.put(property, value);
                assertThrows(IllegalArgumentException.class, () -> task(c.task, props).run(runContext()));
                assertTrue(server.requests.isEmpty());
            }))));
    }

    @TestFactory
    Stream<DynamicTest> missingObjectsAreIgnoredOnlyWhenRequested() {
        return contracts().stream().filter(c -> c.task.equals("incidents.Delete") || java.util.List.of("oncall.Acknowledge", "oncall.Resolve", "oncall.Escalate").contains(c.task))
            .map(c -> DynamicTest.dynamicTest(c.task + " ignoreNotFound", () -> {
                server.requests.clear();
                Map<String, Object> props = new LinkedHashMap<>(c.properties);
                props.put("ignoreNotFound", true);
                server.reply(c.method, c.path, 404, "{\"errors\":[\"missing\"]}");
                assertEquals(404, ((ResponseOutput) task(c.task, props).run(runContext())).getStatusCode());
                server.reply(c.method, c.path, 403, "{\"errors\":[\"denied\"]}");
                assertThrows(DatadogClient.DatadogException.class, () -> task(c.task, props).run(runContext()));
            }));
    }

    @Test
    void structuredResponseCredentialsAreRedactedWithExplicitOptIn() throws Exception {
        String response = """
            {"data":{"id":"one","type":"incidents","attributes":{"title":"safe","api_key":"api-value","nested":[{"password":"pass-value","webhook_url":"hook-value","applicationKey":"app-value","access_token":"token-value"}]}}}
            """;
        server.reply("GET", "/api/v2/incidents/" + ID, 200, response);
        var output = (ResponseOutput) task("incidents.Get", Map.of("incidentId", ID)).run(runContext());
        String safe = output.getBody().toString();
        for (String value : java.util.List.of("api-value", "pass-value", "hook-value", "app-value", "token-value")) assertFalse(safe.contains(value));
        assertTrue(safe.contains("[REDACTED]"));
        assertTrue(safe.contains("safe"));
        var raw = (ResponseOutput) task("incidents.Get", Map.of("incidentId", ID, "includeSecrets", true)).run(runContext());
        assertTrue(raw.getBody().toString().contains("hook-value"));
    }

    @TestFactory
    Stream<DynamicTest> collectionLimitsAreValidated() {
        return Stream.of("incidents.List", "incidents.Search", "cases.Search", "statuspages.List").flatMap(name ->
            Stream.of(Map.entry("pageSize", 0), Map.entry("pageSize", 101), Map.entry("maxResults", 0), Map.entry("maxResults", 10001))
                .map(entry -> DynamicTest.dynamicTest(name + " invalid " + entry, () -> {
                    server.requests.clear();
                    Map<String, Object> props = new LinkedHashMap<>(Map.of(entry.getKey(), entry.getValue()));
                    if (name.equals("incidents.Search")) props.put("query", "state:active");
                    assertThrows(IllegalArgumentException.class, () -> task(name, props).run(runContext()));
                    assertTrue(server.requests.isEmpty());
                })));
    }

    @TestFactory
    Stream<DynamicTest> listsPageAndStoreIon() {
        return Stream.of("incidents.List", "incidents.Search", "cases.Search", "statuspages.List").flatMap(name -> Stream.of(false, true).map(store -> DynamicTest.dynamicTest(name + " paging store=" + store, () -> {
            server.requests.clear();
            Contract contract = contracts().stream().filter(c -> c.task.equals(name)).findFirst().orElseThrow();
            // A full first page is followed by a short second page.
            String first = contract.response;
            Map<String, Object> props = new LinkedHashMap<>(contract.properties);
            props.putAll(Map.of("pageSize", 1, "maxResults", 3, "store", store));
            server.reply("GET", contract.path, 200, first);
            server.reply("GET", contract.path, 200, first.replace(ID, OTHER));
            server.reply("GET", contract.path, 200, name.equals("incidents.Search")
                ? "{\"data\":{\"type\":\"incidents_search_results\",\"attributes\":{\"incidents\":[],\"facets\":{},\"total\":2}}}" : "{\"data\":[]}");
            CollectionOutput result = (CollectionOutput) task(name, props).run(runContext());
            assertEquals(2, result.getCount());
            assertFalse(result.isLimitReached());
            assertEquals(3, server.requests.size());
            for (int i = 0; i < 3; i++) {
                String query = java.net.URLDecoder.decode(server.requests.get(i).query(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(query.contains((name.equals("cases.Search") ? "page[number]=" : "page[offset]=") + i));
            }
            if (store) {
                assertNull(result.getItems());
                assertNotNull(result.getUri());
                try (var input = runContext().storage().getFile(result.getUri())) {
                    java.util.List<Object> rows = new ArrayList<>();
                    io.kestra.core.serializers.FileSerde.read(input, rows::add);
                    assertEquals(2, rows.size());
                    assertTrue(rows.get(1).toString().contains(OTHER));
                }
            } else {
                assertNull(result.getUri());
                assertEquals(2, result.getItems().size());
            }
        })));
    }

    @Test
    void numberedPagesKeepSizeWhenTheLimitCutsAPage() throws Exception {
        String pair = "{\"data\":[" + jsonData(CASE) + "," + jsonData(CASE).replace(ID, OTHER) + "]}";
        server.reply("GET", "/api/v2/cases", 200, pair);
        CollectionOutput result = (CollectionOutput) task("cases.Search", Map.of("pageSize", 2, "maxResults", 3)).run(runContext());
        assertEquals(3, result.getCount());
        assertTrue(result.isLimitReached());
        assertEquals(2, server.requests.size());
        assertEquals("page[size]=2&page[number]=1", java.net.URLDecoder.decode(server.requests.get(1).query(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void explicitPaginationIsFollowedAndStalledOffsetsFail() throws Exception {
        String row = jsonData(STATUS_PAGE);
        server.reply("GET", "/api/v2/statuspages", 200, "{\"data\":[" + row + "],\"meta\":{\"page\":{\"next_offset\":7}}}");
        server.reply("GET", "/api/v2/statuspages", 200, "{\"data\":[" + row.replace(ID, OTHER) + "],\"meta\":{\"page\":{\"next_offset\":null}}}");
        CollectionOutput output = (CollectionOutput) task("statuspages.List", Map.of()).run(runContext());
        assertEquals(2, output.getCount());
        assertTrue(java.net.URLDecoder.decode(server.requests.get(1).query(), java.nio.charset.StandardCharsets.UTF_8).contains("page[offset]=7"));
        server.reply("GET", "/api/v2/statuspages", 200, "{\"data\":[" + row + "],\"meta\":{\"page\":{\"next_offset\":0}}}");
        assertThrows(IllegalStateException.class, () -> task("statuspages.List", Map.of()).run(runContext()));
    }

    @Test
    void malformedListResponsesFail() {
        for (String response : java.util.List.of("{}", "{\"data\":[1]}")) {
            server.reply("GET", "/api/v2/incidents", 200, response);
            assertThrows(IllegalStateException.class, () -> task("incidents.List", Map.of()).run(runContext()));
        }
    }

    @TestFactory
    Stream<DynamicTest> incidentListsFollowPaginationMetadata() {
        return Stream.of("incidents.List", "incidents.Search").map(name -> DynamicTest.dynamicTest(name + " next_offset", () -> {
            server.requests.clear();
            Contract c = contracts().stream().filter(contract -> contract.task.equals(name)).findFirst().orElseThrow();
            @SuppressWarnings("unchecked") Map<String, Object> first = JacksonMapper.ofJson().readValue(c.response, Map.class);
            first.put("meta", Map.of("pagination", Map.of("next_offset", 50, "offset", 0, "size", 50)));
            server.reply("GET", c.path, 200, JacksonMapper.ofJson().writeValueAsString(first));
            server.reply("GET", c.path, 200, name.equals("incidents.Search")
                ? "{\"data\":{\"type\":\"incidents_search_results\",\"attributes\":{\"incidents\":[],\"facets\":{},\"total\":1}}}" : "{\"data\":[]}");
            assertEquals(1, ((CollectionOutput) task(name, c.properties).run(runContext())).getCount());
            assertEquals(2, server.requests.size());
            assertTrue(java.net.URLDecoder.decode(server.requests.get(1).query(), java.nio.charset.StandardCharsets.UTF_8).contains("page[offset]=50"));
        }));
    }

    @Test
    void degradationRejectsInvalidComponentAndIdempotencyIds() {
        Map<String, Object> props = new LinkedHashMap<>(Map.of("pageId", ID, "title", "Orders", "status", "INVESTIGATING", "components", Map.of("invalid", "DEGRADED")));
        assertThrows(IllegalArgumentException.class, () -> task("statuspages.CreateDegradation", props).run(runContext()));
        props.put("components", Map.of(OTHER, "DEGRADED"));
        props.put("idempotencyKey", "invalid");
        assertThrows(IllegalArgumentException.class, () -> task("statuspages.CreateDegradation", props).run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void degradationForwardsIdempotencyKeyAndNotificationOptIn() throws Exception {
        ResponseOutput output = (ResponseOutput) task("statuspages.UpdateDegradation", Map.of("pageId", ID, "degradationId", OTHER,
            "status", "RESOLVED", "components", Map.of(OTHER, "OPERATIONAL"), "notifySubscribers", true, "idempotencyKey", ID)).run(runContext());
        assertEquals(Map.of("notify_subscribers", true), output.getRequest().get("query"));
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) output.getRequest().get("body");
        assertEquals(Map.of("idempotency_key", ID), body.get("meta"));
        assertEquals(Map.of("status", "resolved", "components_affected", java.util.List.of(Map.of("id", OTHER, "status", "operational"))), data(body).get("attributes"));
        assertTrue(server.requests.isEmpty());
    }
}
