package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FormulaQueriesTest extends AbstractDatadogTest {
    static final String SCALAR = "/api/v2/query/scalar";
    static final String TIMESERIES = "/api/v2/query/timeseries";
    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant TO = FROM.plusSeconds(300);

    // DataScalarColumn: type=number and nullable double values.
    static String scalar(String values) {
        return "{\"data\":{\"type\":\"scalar_response\",\"attributes\":{\"columns\":[{\"type\":\"number\",\"name\":\"a\",\"values\":[" + values + "]}]}}}";
    }

    private QueryScalar.QueryScalarBuilder<?, ?> scalarTask() {
        return QueryScalar.builder().id(IdUtils.create()).type(QueryScalar.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .queries(Property.ofValue(Map.of("a", "avg:system.cpu.user{*}"))).from(Property.ofValue(FROM)).to(Property.ofValue(TO));
    }

    private QueryTimeseries.QueryTimeseriesBuilder<?, ?> timeseriesTask() {
        return QueryTimeseries.builder().id(IdUtils.create()).type(QueryTimeseries.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .queries(Property.ofValue(Map.of("a", "avg:system.cpu.user{*}"))).from(Property.ofValue(FROM)).to(Property.ofValue(TO));
    }

    private Check.CheckBuilder<?, ?> checkTask() {
        return Check.builder().id(IdUtils.create()).type(Check.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .queries(Property.ofValue(Map.of("a", "avg:system.cpu.user{*}"))).from(Property.ofValue(FROM)).to(Property.ofValue(TO))
            .threshold(Property.ofValue(new BigDecimal("80")));
    }

    @Test
    void sendsNamedQueriesFormulasEnumsAndMillisecondWindow() throws Exception {
        server.reply("POST", SCALAR, 200, scalar("42.5,null"));
        var output = scalarTask().queries(Property.ofValue(Map.of("a", "sum:cost{*}", "b", "sum:units{*}")))
            .formulas(Property.ofValue(List.of("a/b"))).dataSource(Property.ofValue(DataSource.CLOUD_COST))
            .aggregator(Property.ofValue(Aggregator.SUM)).build().run(runContext());
        Map<?, ?> data = (Map<?, ?>) server.only().json().get("data");
        Map<?, ?> attributes = (Map<?, ?>) data.get("attributes");
        assertEquals("scalar_request", data.get("type"));
        assertEquals(FROM.toEpochMilli(), ((Number) attributes.get("from")).longValue());
        assertEquals(TO.toEpochMilli(), ((Number) attributes.get("to")).longValue());
        assertEquals(List.of(Map.of("formula", "a/b")), attributes.get("formulas"));
        List<?> queries = (List<?>) attributes.get("queries");
        assertEquals(2, queries.size());
        queries.forEach(q -> {
            assertEquals("cloud_cost", ((Map<?, ?>) q).get("data_source"));
            assertEquals("sum", ((Map<?, ?>) q).get("aggregator"));
        });
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        assertEquals(1, output.getColumnCount());
        assertNull(((List<?>) output.getColumns().getFirst().get("values")).get(1));
    }

    @Test
    void timeseriesOmitsScalarAggregatorAndPreservesNullPoints() throws Exception {
        server.reply("POST", TIMESERIES, 200, """
            {"data":{"type":"timeseries_response","attributes":{"series":[{"query_index":0,"group_tags":["env:prod"]}],"times":[1767225600000,1767225660000],"values":[[42.5,null]]}}}
            """);
        var output = timeseriesTask().queryInterval(Property.ofValue(60000L)).build().run(runContext());
        Map<?, ?> data = (Map<?, ?>) server.only().json().get("data");
        Map<?, ?> attributes = (Map<?, ?>) data.get("attributes");
        assertEquals("timeseries_request", data.get("type"));
        assertEquals(60000, ((Number) attributes.get("interval")).intValue());
        assertFalse(((Map<?, ?>) ((List<?>) attributes.get("queries")).getFirst()).containsKey("aggregator"));
        assertEquals(1, output.getSeriesCount());
        assertNull(((List<?>) ((List<?>) output.getData().get("values")).getFirst()).get(1));
    }

    @Test
    void storesColumnsAsIonAndOmitsInlineData() throws Exception {
        server.reply("POST", SCALAR, 200, scalar("42.5"));
        var context = runContext();
        var output = scalarTask().store(Property.ofValue(true)).build().run(context);
        assertNull(output.getColumns());
        assertNotNull(output.getUri());
        try (var input = context.storage().getFile(output.getUri())) {
            Map<?, ?> column = JacksonMapper.ofIon().readValue(input, Map.class);
            assertEquals("number", column.get("type"));
            assertEquals("a", column.get("name"));
        }
    }

    @Test
    void storesTimeseriesAsOneIonRecord() throws Exception {
        server.reply("POST", TIMESERIES, 200, "{\"data\":{\"attributes\":{\"series\":[],\"times\":[],\"values\":[]}}}");
        var context = runContext();
        var output = timeseriesTask().store(Property.ofValue(true)).build().run(context);
        assertNull(output.getData());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(List.of(), JacksonMapper.ofIon().readValue(input, Map.class).get("values"));
        }
    }

    @Test
    void checksThresholdAndCanReportWithoutFailing() throws Exception {
        server.reply("POST", SCALAR, 200, scalar("80"));
        var equal = checkTask().build().run(runContext());
        assertEquals(new BigDecimal("80"), equal.getValue());
        assertFalse(equal.getBreached());
        assertFalse(equal.getNoData());
        assertThrows(IllegalStateException.class, () -> checkTask().comparison(Property.ofValue(Comparison.GTE)).build().run(runContext()));
        var breach = checkTask().comparison(Property.ofValue(Comparison.GTE)).failOnBreach(Property.ofValue(false)).build().run(runContext());
        assertTrue(breach.getBreached());
    }

    @Test
    void nullFailsByDefaultAndPoliciesHaveDistinctOutputs() throws Exception {
        server.reply("POST", SCALAR, 200, scalar("null"));
        assertThrows(IllegalStateException.class, () -> checkTask().failOnBreach(Property.ofValue(false)).build().run(runContext()));
        var pass = checkTask().onNoData(Property.ofValue(Check.OnNoData.PASS)).build().run(runContext());
        assertNull(pass.getValue());
        assertTrue(pass.getNoData());
        assertFalse(pass.getBreached());
        var unknown = checkTask().onNoData(Property.ofValue(Check.OnNoData.NO_DATA)).build().run(runContext());
        assertTrue(unknown.getNoData());
        assertNull(unknown.getBreached());
    }

    @Test
    void emptyScalarAndEmptyColumnsAreNoData() throws Exception {
        for (String response : List.of(scalar(""), "{\"data\":{\"attributes\":{\"columns\":[]}}}")) {
            server.reply("POST", SCALAR, 200, response);
            assertTrue(checkTask().onNoData(Property.ofValue(Check.OnNoData.NO_DATA)).build().run(runContext()).getNoData());
        }
    }

    @Test
    void groupedOrMultipleColumnAnswersCannotSilentlyPass() {
        for (String response : List.of(scalar("10,100"),
            "{\"data\":{\"attributes\":{\"columns\":[{\"type\":\"number\",\"values\":[1]},{\"type\":\"number\",\"values\":[2]}]}}}")) {
            server.reply("POST", SCALAR, 200, response);
            assertThrows(IllegalStateException.class, () -> checkTask().onNoData(Property.ofValue(Check.OnNoData.PASS)).build().run(runContext()));
        }
    }

    @Test
    void malformedResponsesFailInsteadOfBecomingNoData() {
        for (String response : List.of("{}", "{\"data\":{\"attributes\":{}}}", scalar("\"80\""), "{\"errors\":\"invalid query\"}")) {
            server.reply("POST", SCALAR, 200, response);
            assertThrows(IllegalStateException.class, () -> checkTask().onNoData(Property.ofValue(Check.OnNoData.PASS)).build().run(runContext()));
        }
        server.reply("POST", TIMESERIES, 200, "{\"data\":{\"attributes\":{}}}");
        assertThrows(IllegalStateException.class, () -> timeseriesTask().build().run(runContext()));
    }

    @TestFactory
    Stream<DynamicTest> validatesBeforeAnyRequest() {
        return Stream.<Map.Entry<String, org.junit.jupiter.api.function.Executable>>of(
            Map.entry("missing queries", () -> scalarTask().queries(null).build().run(runContext())),
            Map.entry("empty queries", () -> scalarTask().queries(Property.ofValue(Map.of())).build().run(runContext())),
            Map.entry("blank query", () -> scalarTask().queries(Property.ofValue(Map.of("a", " "))).build().run(runContext())),
            Map.entry("blank name", () -> scalarTask().queries(Property.ofValue(Map.of(" ", "a"))).build().run(runContext())),
            Map.entry("blank formula", () -> scalarTask().formulas(Property.ofValue(List.of(" "))).build().run(runContext())),
            Map.entry("reversed window", () -> scalarTask().from(Property.ofValue(TO)).build().run(runContext())),
            Map.entry("negative lookback", () -> scalarTask().lookback(Property.ofValue(Duration.ofSeconds(-1))).build().run(runContext())),
            Map.entry("zero lookback", () -> scalarTask().lookback(Property.ofValue(Duration.ZERO)).build().run(runContext())),
            Map.entry("zero interval", () -> timeseriesTask().queryInterval(Property.ofValue(0L)).build().run(runContext())),
            Map.entry("missing threshold", () -> checkTask().threshold(null).build().run(runContext())),
            Map.entry("missing application key", () -> scalarTask().appKey(null).build().run(runContext()))
        ).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertThrows(IllegalArgumentException.class, entry.getValue());
            assertTrue(server.requests.isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> propagatesHttpErrorsForEveryTask() {
        return Stream.of(400, 403, 404, 429, 503).flatMap(status -> Stream.of("scalar", "timeseries", "check").map(task ->
            DynamicTest.dynamicTest(task + " HTTP " + status, () -> {
                server.reply("POST", task.equals("timeseries") ? TIMESERIES : SCALAR, status, "{\"errors\":[\"request failed\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> {
                    switch (task) {
                        case "scalar" -> scalarTask().build().run(runContext());
                        case "timeseries" -> timeseriesTask().build().run(runContext());
                        default -> checkTask().build().run(runContext());
                    }
                });
                assertEquals(status, error.getStatus());
                assertFalse(error.getMessage().contains(API_KEY));
                assertFalse(error.getMessage().contains(APP_KEY));
            })));
    }

    @Test
    void everyComparisonUsesNumericOrderingIncludingEquality() {
        BigDecimal threshold = new BigDecimal("80.00");
        boolean[][] expected = {{false, false, true}, {false, true, true}, {true, false, false}, {true, true, false}, {false, true, false}, {true, false, true}};
        for (Comparison comparison : Comparison.values()) {
            for (int i = 0; i < 3; i++) {
                assertEquals(expected[comparison.ordinal()][i], comparison.breached(BigDecimal.valueOf(79 + i), threshold), comparison + " at " + i);
            }
        }
    }
}
