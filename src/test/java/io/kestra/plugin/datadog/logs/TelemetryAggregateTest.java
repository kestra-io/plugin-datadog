package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryAggregateTest extends AbstractDatadogTest {
    private AbstractAggregate.AbstractAggregateBuilder<?, ?> builder(String area) {
        AbstractAggregate.AbstractAggregateBuilder<?, ?> builder = switch (area) {
            case "logs" -> Aggregate.builder();
            case "spans" -> io.kestra.plugin.datadog.spans.Aggregate.builder();
            default -> io.kestra.plugin.datadog.rum.Aggregate.builder();
        };
        return builder.id(IdUtils.create()).type(builder.build().getClass().getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    static String path(String area) {
        return "/api/v2/" + area + "/analytics/aggregate";
    }

    static String answer(String area, String value) {
        // Logs/RUM return data.buckets. Spans return bucket resources in data[].
        return area.equals("spans")
            ? "{\"data\":[{\"id\":\"bucket-1\",\"type\":\"bucket\",\"attributes\":{\"by\":{},\"computes\":{\"c0\":" + value + "}}}],\"meta\":{\"status\":\"done\"}}"
            : "{\"data\":{\"buckets\":[{\"by\":{},\"computes\":{\"c0\":" + value + "}}]},\"meta\":{\"status\":\"done\"}}";
    }

    private AbstractAggregate.Output run(AbstractAggregate.AbstractAggregateBuilder<?, ?> builder) throws Exception {
        return (AbstractAggregate.Output) ((RunnableTask<?>) builder.build()).run(runContext());
    }

    @TestFactory
    Stream<DynamicTest> typedRequestsAndExtraBodyForEveryAggregate() {
        return Stream.of("logs", "spans", "rum").map(area -> DynamicTest.dynamicTest(area + " typed aggregate", () -> {
            server.requests.clear();
            server.reply("POST", path(area), 200, answer(area, "12"));
            Map<String, Object> extra = Map.of("filter", Map.of("query", "service:overridden"), "options", Map.of("timezone", "Europe/Paris"));
            var output = run(builder(area).query(Property.ofValue("service:original"))
                .compute(Property.ofValue(List.of(AbstractAggregate.Compute.builder().aggregation(AbstractAggregate.Aggregation.PC95)
                    .metric("@duration").computeType(AbstractAggregate.ComputeType.TIMESERIES).interval("1m").build())))
                .groupBy(Property.ofValue(List.of(AbstractAggregate.GroupBy.builder().facet("service").limit(5).build())))
                .timezone(Property.ofValue("UTC"))
                .extraBody(Property.ofValue(area.equals("spans") ? Map.of("data", Map.of("attributes", extra)) : extra)));
            assertEquals(1, output.getSize());
            assertEquals(12, ((Map<?, ?>) output.getBuckets().getFirst().get("computes")).get("c0"));
            Map<?, ?> body = server.only().json();
            assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
            if (area.equals("spans")) {
                assertEquals("aggregate_request", ((Map<?, ?>) body.get("data")).get("type"));
                body = (Map<?, ?>) ((Map<?, ?>) body.get("data")).get("attributes");
            }
            assertEquals(List.of(Map.of("aggregation", "pc95", "metric", "@duration", "type", "timeseries", "interval", "1m")), body.get("compute"));
            assertEquals(List.of(Map.of("facet", "service", "limit", 5)), body.get("group_by"));
            assertEquals(Map.of("timezone", "Europe/Paris"), body.get("options"));
            assertEquals(Map.of("query", "service:overridden", "from", "now-15m", "to", "now"), body.get("filter"));
        }));
    }

    @TestFactory
    Stream<DynamicTest> validationForEveryAggregate() {
        return Stream.of("logs", "spans", "rum").map(area -> DynamicTest.dynamicTest(area + " validation", () -> {
            int before = server.requests.size();
            for (int cap : List.of(0, 10001)) {
                assertThrows(IllegalArgumentException.class, () -> run(builder(area).maxResults(Property.ofValue(cap))));
            }
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).from(Property.ofValue("yesterday"))));
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).to(Property.ofValue("later"))));
            for (AbstractAggregate.Compute compute : List.of(
                AbstractAggregate.Compute.builder().build(),
                AbstractAggregate.Compute.builder().aggregation(AbstractAggregate.Aggregation.AVG).build(),
                AbstractAggregate.Compute.builder().aggregation(AbstractAggregate.Aggregation.COUNT).interval("1m").build(),
                AbstractAggregate.Compute.builder().aggregation(AbstractAggregate.Aggregation.COUNT).computeType(AbstractAggregate.ComputeType.TIMESERIES).interval("0s").build())) {
                assertThrows(IllegalArgumentException.class, () -> run(builder(area).compute(Property.ofValue(List.of(compute)))));
            }
            for (AbstractAggregate.GroupBy group : List.of(AbstractAggregate.GroupBy.builder().build(), AbstractAggregate.GroupBy.builder().facet("service").limit(0).build())) {
                assertThrows(IllegalArgumentException.class, () -> run(builder(area).groupBy(Property.ofValue(List.of(group)))));
            }
            Map<String, Object> extra = Map.of("filter", Map.of("to", "invalid"));
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).extraBody(Property.ofValue(area.equals("spans") ? Map.of("data", Map.of("attributes", extra)) : extra))));
            assertEquals(before, server.requests.size());
        }));
    }

    @TestFactory
    Stream<DynamicTest> errorsForEveryAggregate() {
        return Stream.of("logs", "spans", "rum").flatMap(area -> Stream.of(400, 403, 404, 429, 503).map(status -> DynamicTest.dynamicTest(area + " HTTP " + status, () -> {
            server.reply("POST", path(area), status, "{\"errors\":[\"request rejected\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> run(builder(area))).getStatus());
        })));
    }

    @Test
    void pagesLogsAndRumAndExportsBucketsAsIon() throws Exception {
        for (String area : List.of("logs", "rum")) {
            server.requests.clear();
            server.reply("POST", path(area), 200, answer(area, "1").replace("\"status\":\"done\"", "\"status\":\"done\",\"page\":{\"after\":\"next\"}"));
            server.reply("POST", path(area), 200, answer(area, "2"));
            var context = runContext();
            var task = (RunnableTask<?>) builder(area).pageCursor(Property.ofValue("start")).fetchType(Property.ofValue(FetchType.STORE)).build();
            var output = (AbstractAggregate.Output) task.run(context);
            assertEquals(2, output.getSize());
            assertNull(output.getBuckets());
            assertNull(output.getNextCursor());
            assertEquals(Map.of("cursor", "start"), server.requests.getFirst().json().get("page"));
            assertEquals(Map.of("cursor", "next"), server.requests.get(1).json().get("page"));
            List<Object> buckets = new ArrayList<>();
            try (var input = context.storage().getFile(output.getUri())) {
                FileSerde.read(input, buckets::add);
            }
            assertEquals(2, buckets.size());
        }
    }

    @Test
    void aggregateCapsAndCursorGuard() throws Exception {
        for (String area : List.of("logs", "rum")) {
            server.requests.clear();
            server.reply("POST", path(area), 200, answer(area, "1").replace("\"status\":\"done\"", "\"status\":\"done\",\"page\":{\"after\":\"next\"}"));
            var output = run(builder(area).maxResults(Property.ofValue(1)));
            assertEquals(1, output.getSize());
            assertEquals("next", output.getNextCursor());
            assertEquals(1, server.requests.size());
            assertThrows(IllegalStateException.class, () -> run(builder(area)));
        }
        assertThrows(IllegalArgumentException.class, () -> run(builder("spans").pageCursor(Property.ofValue("cursor"))));
    }

    @Test
    void indexesAndExtraArraysUseWireNamesAndLastWriteWins() throws Exception {
        server.reply("POST", path("logs"), 200, answer("logs", "1"));
        Aggregate.builder().id(IdUtils.create()).type(Aggregate.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .indexes(Property.ofValue(List.of("main")))
            .extraBody(Property.ofValue(Map.of("compute", List.of(Map.of("aggregation", "cardinality", "metric", "@user.id")), "filter", Map.of("storage_tier", "flex"))))
            .build().run(runContext());
        assertEquals(List.of(Map.of("aggregation", "cardinality", "metric", "@user.id")), server.only().json().get("compute"));
        assertEquals(Map.of("query", "*", "from", "now-15m", "to", "now", "indexes", List.of("main"), "storage_tier", "flex"), server.only().json().get("filter"));
    }

    @Test
    void storesSpanBucketsAndRedactsGroupValuesUnlessRequested() throws Exception {
        String answer = answer("spans", "1").replace("\"by\":{}", "\"by\":{\"api_key\":\"fixture-key\"}");
        server.reply("POST", path("spans"), 200, answer);
        var context = runContext();
        var output = (AbstractAggregate.Output) ((RunnableTask<?>) builder("spans").fetchType(Property.ofValue(FetchType.STORE)).build()).run(context);
        List<Object> rows = new ArrayList<>();
        try (var input = context.storage().getFile(output.getUri())) {
            FileSerde.read(input, rows::add);
        }
        assertEquals(1, rows.size());
        assertFalse(rows.toString().contains("fixture-key"));
        assertTrue(run(builder("spans").includeSecrets(Property.ofValue(true))).getBuckets().toString().contains("fixture-key"));
    }
}
