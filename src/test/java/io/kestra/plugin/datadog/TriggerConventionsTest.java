package io.kestra.plugin.datadog;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.Trigger;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TriggerConventionsTest extends AbstractDatadogTest {
    private static final Instant CLOCK = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> TRIGGERS = List.of("monitors.StateChange", "metrics.Threshold", "logs.CountThreshold", "slos.BudgetLow");

    private AbstractTrigger trigger(String name, String url) {
        var properties = new LinkedHashMap<String, Object>();
        properties.putAll(Map.of("id", IdUtils.create(), "type", "io.kestra.plugin.datadog." + name, "apiKey", API_KEY, "appKey", APP_KEY, "apiUrl", url));
        switch (name) {
            case "metrics.Threshold" -> properties.putAll(Map.of("query", "avg:system.cpu.user{*}", "threshold", 80));
            case "logs.CountThreshold" -> properties.put("threshold", 10);
            case "slos.BudgetLow" -> properties.putAll(Map.of("sloId", "0123456789abcdef0123456789abcdef", "target", 99.9));
            default -> { }
        }
        return JacksonMapper.ofJson().convertValue(properties, AbstractTrigger.class);
    }

    private Map.Entry<ConditionContext, Trigger> poll(AbstractTrigger trigger) {
        return TestsUtils.mockTrigger(runContextFactory, trigger);
    }

    private static String response(String name, boolean breached, boolean late) {
        return switch (name) {
            case "metrics.Threshold" -> "{\"data\":{\"type\":\"scalar_response\",\"attributes\":{\"columns\":[{\"type\":\"number\",\"name\":\"a\",\"values\":[" + (breached ? 90 : 70) + "]}]}}}";
            case "logs.CountThreshold" -> "{\"data\":{\"buckets\":[{\"by\":{},\"computes\":{\"c0\":" + (breached ? 20 : 5) + "}}]}}";
            case "slos.BudgetLow" -> "{\"data\":{\"overall\":{\"sli_value\":" + (breached ? "99.0" : "99.99") + ",\"span_precision\":3,\"errors\":[]}},\"errors\":[]}";
            default -> !breached ? "[]" : "[" + monitor(1) + (late ? "," + monitor(2) : "") + "]";
        };
    }

    private static String monitor(int id) {
        return "{\"id\":" + id + ",\"name\":\"orders\",\"type\":\"query alert\",\"query\":\"avg(last_5m):avg:system.cpu.user{*} > 90\",\"overall_state\":\"Alert\",\"overall_state_modified\":\"" + CLOCK + "\"}";
    }

    @TestFactory
    Stream<DynamicTest> networkFailuresSkipEveryTriggerWithoutMovingItsCursor() {
        return TRIGGERS.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            try (var local = new ClockServer(response(name, false, false))) {
                var trigger = trigger(name, local.url());
                var poll = poll(trigger);
                var evaluator = (PollingTriggerInterface) trigger;
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty());
                var kv = poll.getKey().getRunContext().namespaceKv(poll.getValue().getNamespace());
                var key = StatefulTriggerService.defaultKey(poll.getValue().getNamespace(), poll.getValue().getFlowId(), poll.getValue().getTriggerId());
                var before = (byte[]) kv.getValue(key).orElseThrow().value();
                local.close();
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty());
                assertArrayEquals(before, (byte[]) kv.getValue(key).orElseThrow().value());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> serverClockAndSameSecondLateChangesWorkForEveryTrigger() {
        return TRIGGERS.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            try (var local = new ClockServer(response(name, false, false))) {
                var trigger = trigger(name, local.url());
                var poll = poll(trigger);
                var evaluator = (PollingTriggerInterface) trigger;
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty());
                var kv = poll.getKey().getRunContext().namespaceKv(poll.getValue().getNamespace());
                var key = StatefulTriggerService.defaultKey(poll.getValue().getNamespace(), poll.getValue().getFlowId(), poll.getValue().getTriggerId());
                var cursor = JacksonMapper.ofJson().readTree((byte[]) kv.getValue(key).orElseThrow().value());
                assertEquals(CLOCK.toString(), cursor.path(name.equals("monitors.StateChange") ? "modified" : "sampledAt").asText());
                local.body.set(response(name, true, false));
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isPresent(), "A change in the cursor second must fire");
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty(), "A sustained condition must not repeat");
                if (name.equals("monitors.StateChange")) {
                    local.body.set(response(name, true, true));
                    var late = evaluator.evaluate(poll.getKey(), poll.getValue()).orElseThrow();
                    assertEquals(1, ((Number) late.getTrigger().getVariables().get("count")).intValue());
                } else {
                    local.body.set(response(name, false, false));
                    assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty());
                    local.body.set(response(name, true, false));
                    assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isPresent(), "Same-second recovery must rearm");
                }
                assertTrue(evaluator.evaluate(poll.getKey(), poll.getValue()).isEmpty());
            }
        }));
    }

    private static final class ClockServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread worker;
        private final AtomicReference<String> body;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private ClockServer(String response) throws Exception {
            body = new AtomicReference<>(response);
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            worker = Thread.ofPlatform().daemon().start(() -> {
                while (!socket.isClosed()) {
                    try (var connection = socket.accept()) {
                        var input = new BufferedInputStream(connection.getInputStream());
                        var length = 0;
                        var chunked = false;
                        String line;
                        while (!(line = line(input)).isEmpty()) {
                            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                            if (line.toLowerCase().startsWith("transfer-encoding:")) chunked = line.toLowerCase().contains("chunked");
                        }
                        if (chunked) {
                            while ((length = Integer.parseInt(line(input), 16)) != 0) {
                                input.readNBytes(length);
                                line(input);
                            }
                            line(input);
                        } else input.readNBytes(length);
                        var bytes = body.get().getBytes(StandardCharsets.UTF_8);
                        var output = connection.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nDate: Thu, 01 Jan 2026 00:00:00 GMT\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(bytes);
                        output.flush();
                    } catch (Exception e) {
                        if (!socket.isClosed()) failure.set(e);
                    }
                }
            });
        }

        private static String line(BufferedInputStream input) throws Exception {
            var bytes = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1 && value != '\n') if (value != '\r') bytes.write(value);
            if (value == -1) throw new EOFException();
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        private String url() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            socket.close();
            worker.join(2000);
            assertNull(failure.get());
        }
    }
}
