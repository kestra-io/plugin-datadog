package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryCountTest extends AbstractDatadogTest {
    private AbstractCount.AbstractCountBuilder<?, ?> builder(String area) {
        AbstractCount.AbstractCountBuilder<?, ?> builder = area.equals("logs") ? Count.builder() : io.kestra.plugin.datadog.spans.Count.builder();
        return builder.id(IdUtils.create()).type(builder.build().getClass().getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).threshold(Property.ofValue(10L));
    }

    private AbstractCount.Output run(AbstractCount.AbstractCountBuilder<?, ?> builder) throws Exception {
        return (AbstractCount.Output) ((RunnableTask<?>) builder.build()).run(runContext());
    }

    @TestFactory
    Stream<DynamicTest> countBoundariesAndFailOnBreach() {
        return Stream.of("logs", "spans").map(area -> DynamicTest.dynamicTest(area + " threshold comparison", () -> {
            for (var count : List.of(9L, 10L, 11L)) {
                server.requests.clear();
                server.reply("POST", TelemetryAggregateTest.path(area), 200, TelemetryAggregateTest.answer(area, String.valueOf(count)));
                var output = run(builder(area).query(Property.ofValue("status:error")));
                assertEquals(count, output.getCount());
                assertEquals(count == 11L, output.getBreached());
                Map<?, ?> body = server.only().json();
                assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
                if (area.equals("spans")) {
                    assertEquals("aggregate_request", ((Map<?, ?>) body.get("data")).get("type"));
                    body = (Map<?, ?>) ((Map<?, ?>) body.get("data")).get("attributes");
                }
                assertEquals(List.of(Map.of("aggregation", "count", "type", "total")), body.get("compute"));
                assertEquals(Map.of("query", "status:error", "from", "now-15m", "to", "now"), body.get("filter"));
            }
            assertThrows(IllegalStateException.class, () -> run(builder(area).failOnBreach(Property.ofValue(true))));
            server.reply("POST", TelemetryAggregateTest.path(area), 200, TelemetryAggregateTest.answer(area, "10"));
            assertFalse(run(builder(area).failOnBreach(Property.ofValue(true))).getBreached());
        }));
    }

    @TestFactory
    Stream<DynamicTest> validatesEveryCount() {
        return Stream.of("logs", "spans").map(area -> DynamicTest.dynamicTest(area + " count validation", () -> {
            var before = server.requests.size();
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).threshold(null)));
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).threshold(Property.ofValue(-1L))));
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).from(Property.ofValue("invalid"))));
            assertThrows(IllegalArgumentException.class, () -> run(builder(area).to(Property.ofValue("invalid"))));
            assertEquals(before, server.requests.size());
        }));
    }

    @TestFactory
    Stream<DynamicTest> errorsForEveryCount() {
        return Stream.of("logs", "spans").flatMap(area -> Stream.of(400, 403, 404, 429, 503).map(status -> DynamicTest.dynamicTest(area + " HTTP " + status, () -> {
            server.reply("POST", TelemetryAggregateTest.path(area), status, "{\"errors\":[\"request rejected\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> run(builder(area))).getStatus());
        })));
    }

    @Test
    void emptyCountsAreZeroAndBadCountsNeverPassTheGate() throws Exception {
        for (var area : List.of("logs", "spans")) {
            var path = TelemetryAggregateTest.path(area);
            server.reply("POST", path, 200, area.equals("spans") ? "{\"data\":[]}" : "{\"data\":{\"buckets\":[]}}");
            var output = run(builder(area).threshold(Property.ofValue(0L)));
            assertEquals(0L, output.getCount());
            assertFalse(output.getBreached());
            server.reply("POST", path, 200, TelemetryAggregateTest.answer(area, "\"12\""));
            assertEquals(12L, run(builder(area)).getCount());
            for (var value : List.of("-1", "1.5", "9223372036854775808", "null", "\"unknown\"")) {
                server.reply("POST", path, 200, TelemetryAggregateTest.answer(area, value));
                assertThrows(IllegalStateException.class, () -> run(builder(area)));
            }
            server.reply("POST", path, 200, TelemetryAggregateTest.answer(area, "12").replace("done", "timeout"));
            assertThrows(IllegalStateException.class, () -> run(builder(area)));
            server.reply("POST", path, 200, "{}");
            assertThrows(IllegalStateException.class, () -> run(builder(area)));
            server.reply("POST", path, 200, TelemetryAggregateTest.answer(area, "12").replace("\"status\":\"done\"", "\"status\":\"done\",\"page\":{\"after\":\"next\"}"));
            assertThrows(IllegalStateException.class, () -> run(builder(area)));
        }
    }

    @Test
    void countSupportsLogIndexes() throws Exception {
        server.reply("POST", TelemetryAggregateTest.path("logs"), 200, TelemetryAggregateTest.answer("logs", "2"));
        Count.builder().id(IdUtils.create()).type(Count.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).threshold(Property.ofValue(5L))
            .indexes(Property.ofValue(List.of("main"))).build().run(runContext());
        assertEquals(List.of("main"), ((Map<?, ?>) server.only().json().get("filter")).get("indexes"));
    }
}
