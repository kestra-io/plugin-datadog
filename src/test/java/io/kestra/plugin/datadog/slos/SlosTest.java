package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
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

class SlosTest extends AbstractDatadogTest {
    // SLOHistoryResponse and SLOHistorySLIData from the v1 OpenAPI description.
    static String history(String sli) {
        return "{\"data\":{\"from_ts\":100,\"to_ts\":200,\"type\":\"metric\",\"overall\":{\"sli_value\":" + sli + ",\"span_precision\":3,\"errors\":[]}},\"errors\":[]}";
    }

    static final String SLO = """
        {"id":"slo-123","name":"Orders availability","type":"metric",
         "thresholds":[{"timeframe":"7d","target":99.9}],
         "query":{"numerator":"sum:orders.good{*}.as_count()","denominator":"sum:orders.total{*}.as_count()"}}
        """;

    private Check.CheckBuilder<?, ?> checker() {
        return Check.builder().id(IdUtils.create()).type(Check.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .sloId(Property.ofValue("slo-123")).from(Property.ofValue(100L)).to(Property.ofValue(200L)).target(Property.ofValue(99.9));
    }

    private History.HistoryBuilder<?, ?> historyTask() {
        return History.builder().id(IdUtils.create()).type(History.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .sloId(Property.ofValue("slo-123")).from(Property.ofValue(100L)).to(Property.ofValue(200L));
    }

    private Get.GetBuilder<?, ?> getter() {
        return Get.builder().id(IdUtils.create()).type(Get.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).sloId(Property.ofValue("slo-123"));
    }

    private List.ListBuilder<?, ?> lister() {
        return List.builder().id(IdUtils.create()).type(List.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @Test
    void calculatesBudgetFromOverallSliWithPercentUnits() throws Exception {
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, history("99.95"));
        var output = checker().build().run(runContext());
        assertEquals(50.0, output.getRemainingBudget());
        assertEquals(99.95, output.getSli());
        assertEquals(99.9, output.getTarget());
        assertTrue(output.getPassed());
        assertTrue(output.getCorrectionsApplied());
        assertEquals("from_ts=100&to_ts=200&target=99.9&apply_correction=true", server.only().query());
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
    }

    @Test
    void thresholdEqualityPassesWithoutFloatingPointDrift() throws Exception {
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, history("99.92"));
        var output = checker().minRemainingBudget(Property.ofValue(20.0)).build().run(runContext());
        assertEquals(20.0, output.getRemainingBudget());
        assertTrue(output.getPassed());
    }

    @Test
    void failsOnBreachOrReturnsAnExplicitFlag() throws Exception {
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, history("99.8"));
        assertThrows(IllegalStateException.class, () -> checker().build().run(runContext()));
        var output = checker().failOnBreach(Property.ofValue(false)).applyCorrections(Property.ofValue(false)).build().run(runContext());
        assertFalse(output.getPassed());
        assertEquals(-100.0, output.getRemainingBudget());
        assertFalse(output.getCorrectionsApplied());
        assertTrue(server.requests.get(1).query().contains("apply_correction=false"));
    }

    @Test
    void evaluatesBoundaryBudgetsWithoutClamping() {
        assertEquals(100, Slos.budget(Map.of("overall", Map.of("sli_value", 100)), 99).remaining());
        assertEquals(0, Slos.budget(Map.of("overall", Map.of("sli_value", 99)), 99).remaining());
        assertEquals(-9900, Slos.budget(Map.of("overall", Map.of("sli_value", 0)), 99).remaining());
        assertEquals(50, Slos.budget(Map.of("overall", Map.of("sli_value", 99.95)), 99.9).remaining());
    }

    @Test
    void validatesRequiredIdsWindowsAndTargetsBeforeCalling() {
        assertThrows(IllegalArgumentException.class, () -> checker().sloId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().sloId(Property.ofValue("../other")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().from(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().to(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().from(Property.ofValue(-1L)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().to(Property.ofValue(100L)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().to(Property.ofValue(99L)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> checker().target(null).build().run(runContext()));
        for (double target : new double[]{0, -1, 100, 101}) {
            assertThrows(IllegalArgumentException.class, () -> checker().target(Property.ofValue(target)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> historyTask().target(Property.ofValue(target)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> Slos.target(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Slos.target(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> checker().minRemainingBudget(Property.ofValue(101.0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> Slos.minimum(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Slos.minimum(Double.NEGATIVE_INFINITY));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void failsClosedOnMissingNullOrOutOfRangeSliEvenInFlagMode() {
        for (String sli : java.util.List.of("null", "-1", "101", "\"99.99\"")) {
            server.reply("GET", "/api/v1/slo/slo-123/history", 200, history(sli));
            assertThrows(IllegalStateException.class, () -> checker().failOnBreach(Property.ofValue(false)).build().run(runContext()));
        }
        for (String response : java.util.List.of("{}", "{\"data\":{}}", "{\"data\":{\"overall\":{\"uptime\":100}}}")) {
            server.reply("GET", "/api/v1/slo/slo-123/history", 200, response);
            assertThrows(IllegalStateException.class, () -> checker().build().run(runContext()));
        }
        assertThrows(IllegalStateException.class, () -> Slos.budget(Map.of("overall", Map.of("sli_value", Double.NaN)), 99));
        assertThrows(IllegalStateException.class, () -> Slos.budget(Map.of("overall", Map.of("sli_value", Double.POSITIVE_INFINITY)), 99));
    }

    @Test
    void doesNotUseGroupUptimeOrPrecomputedTimeframeBudgetForTheGate() throws Exception {
        var response = JacksonMapper.ofJson().readValue(history("99.95"), Map.class);
        var data = Slos.object(response.get("data"), "data");
        data.put("groups", java.util.List.of(Map.of("sli_value", 0)));
        var overall = Slos.object(data.get("overall"), "overall");
        overall.put("uptime", 0);
        overall.put("error_budget_remaining", Map.of("7d", -100));
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, JacksonMapper.ofJson().writeValueAsString(response));
        assertEquals(50.0, checker().build().run(runContext()).getRemainingBudget());
    }

    @Test
    void rejectsTopLevelAndOverallErrorsAndReplayData() throws Exception {
        for (String response : java.util.List.of(
            "{\"data\":{},\"errors\":[{\"error\":\"query failed\"}]}",
            "{\"data\":{\"overall\":{\"sli_value\":100,\"errors\":[{\"error_type\":\"query\",\"error_message\":\"failed\"}]}}}",
            "{\"data\":{\"overall\":{\"sli_value\":100,\"preview\":true}}}"
        )) {
            server.reply("GET", "/api/v1/slo/slo-123/history", 200, response);
            assertThrows(IllegalStateException.class, () -> checker().failOnBreach(Property.ofValue(false)).build().run(runContext()));
        }
    }

    @Test
    void propagatesHttpErrorsFromTheBudgetGate() {
        for (int status : new int[]{400, 403, 404, 429, 503}) {
            server.reply("GET", "/api/v1/slo/slo-123/history", status, "{\"errors\":[\"request failed\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> checker().failOnBreach(Property.ofValue(false)).build().run(runContext()));
            assertEquals(status, error.getStatus());
            assertFalse(error.getMessage().contains(API_KEY));
        }
    }

    @Test
    void propagatesHttpErrorsFromEveryReadTask() {
        for (int status : new int[]{400, 403, 404, 429, 503}) {
            server.reply("GET", "/api/v1/slo/slo-123", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> getter().build().run(runContext())).getStatus());
            server.reply("GET", "/api/v1/slo", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> lister().build().run(runContext())).getStatus());
            server.reply("GET", "/api/v1/slo/slo-123/history", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> historyTask().build().run(runContext())).getStatus());
        }
    }

    @Test
    void readsHistoryAndCanStoreIonWithoutInlineData() throws Exception {
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, history("99.95"));
        var inline = historyTask().build().run(runContext());
        assertEquals(99.95, Slos.object(inline.getHistory().get("overall"), "overall").get("sli_value"));
        assertFalse(server.only().query().contains("target="));
        var task = historyTask().fetchType(Property.ofValue(FetchType.STORE)).target(Property.ofValue(99.9)).build();
        var context = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(context);
        assertNull(output.getHistory());
        try (var input = context.storage().getFile(output.getUri())) {
            var rows = new ArrayList<Object>();
            io.kestra.core.serializers.FileSerde.read(input, rows::add);
            assertEquals(1, rows.size());
            assertEquals(99.95, ((Number) ((Map<?, ?>) ((Map<?, ?>) rows.getFirst()).get("overall")).get("sli_value")).doubleValue());
        }
    }

    @Test
    void getsDefinitionWithConfiguredAlerts() throws Exception {
        server.reply("GET", "/api/v1/slo/slo-123", 200, "{\"data\":" + SLO + ",\"errors\":[]}");
        assertEquals("Orders availability", getter().withConfiguredAlertIds(Property.ofValue(true)).build().run(runContext()).getSlo().get("name"));
        assertEquals("with_configured_alert_ids=true", server.only().query());
        assertThrows(IllegalArgumentException.class, () -> getter().sloId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> getter().sloId(Property.ofValue("a/b")).build().run(runContext()));
    }

    private String page(int size) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < size; index++) {
            var row = new LinkedHashMap<String, Object>(JacksonMapper.ofJson().readValue(SLO, Map.class));
            row.put("id", "slo-" + index);
            rows.add(row);
        }
        return JacksonMapper.ofJson().writeValueAsString(Map.of("data", rows, "errors", java.util.List.of()));
    }

    @Test
    void pagesWithOffsetAndFiltersAndDetectsTruncation() throws Exception {
        server.reply("GET", "/api/v1/slo", 200, page(1000)).reply("GET", "/api/v1/slo", 200, page(1));
        var output = lister().query(Property.ofValue("orders")).tagsQuery(Property.ofValue("env:prod"))
            .metricsQuery(Property.ofValue("orders.good")).ids(Property.ofValue("slo-123,slo-456")).deleted(Property.ofValue(false)).build().run(runContext());
        assertEquals(1000, output.getSize());
        assertTrue(output.getTruncated());
        assertTrue(server.requests.get(0).query().contains("tags_query=env%3Aprod"));
        assertTrue(server.requests.get(0).query().contains("metrics_query=orders.good"));
        assertTrue(server.requests.get(0).query().contains("is_deleted=false"));
        assertTrue(server.requests.get(1).query().contains("offset=1000"));
        assertTrue(server.requests.get(1).query().contains("limit=1"));
    }

    @Test
    void exactAndEmptyListsAreNotTruncatedAndCanUseIonStorage() throws Exception {
        server.reply("GET", "/api/v1/slo", 200, page(2)).reply("GET", "/api/v1/slo", 200, page(0));
        var task = lister().maxResults(Property.ofValue(2)).fetchType(Property.ofValue(FetchType.STORE)).build();
        var context = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(context);
        assertEquals(2, output.getSize());
        assertFalse(output.getTruncated());
        assertNull(output.getSlos());
        try (var input = context.storage().getFile(output.getUri()); var rows = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(2, rows.readAll().size());
        }
        assertEquals(0, lister().build().run(runContext()).getSize());
        for (int limit : new int[]{0, -1, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> lister().maxResults(Property.ofValue(limit)).build().run(runContext()));
        }
    }

    @Test
    void rejectsMalformedAndErroredListResponses() {
        for (String response : java.util.List.of("{}", "{\"data\":[1]}", "{\"data\":[],\"errors\":[\"failed\"]}")) {
            server.reply("GET", "/api/v1/slo", 200, response);
            assertThrows(IllegalStateException.class, () -> lister().build().run(runContext()));
        }
    }
}
