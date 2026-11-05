package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CountThresholdTest extends AbstractDatadogTest {
    private CountThreshold.CountThresholdBuilder<?, ?> builder() {
        return CountThreshold.builder().id(IdUtils.create()).type(CountThreshold.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .query(Property.ofValue("status:error")).threshold(Property.ofValue(10L));
    }

    private record Poll(CountThreshold trigger, ConditionContext condition, TriggerContext context) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(condition, context);
        }

        boolean armed() throws Exception {
            String key = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
            Object value = condition.getRunContext().namespaceKv(context.getNamespace()).getValue(key).orElseThrow().value();
            Map<?, ?> state = value instanceof byte[] bytes ? JacksonMapper.ofJson().readValue(bytes, Map.class)
                : value instanceof String text ? JacksonMapper.ofJson().readValue(text, Map.class) : (Map<?, ?>) value;
            return (Boolean) state.get("armed");
        }
    }

    private Poll poll(CountThreshold trigger) {
        var entry = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, entry.getKey(), entry.getValue());
    }

    private void count(long count) {
        server.reply("POST", TelemetryAggregateTest.path("logs"), 200, TelemetryAggregateTest.answer("logs", String.valueOf(count)));
    }

    @Test
    void firstSuccessfulPollRecordsStateAndDoesNotReplayExistingBreach() throws Exception {
        count(11);
        Poll poll = poll(builder().window(Property.ofValue(Duration.ofMinutes(2))).indexes(Property.ofValue(List.of("main"))).build());
        assertTrue(poll.run().isEmpty());
        assertFalse(poll.armed());
        assertTrue(poll.run().isEmpty());
        Map<?, ?> filter = (Map<?, ?>) server.requests.getFirst().json().get("filter");
        assertEquals(Map.of("from", "now-120s", "to", "now", "query", "status:error", "indexes", List.of("main")), filter);
    }

    @Test
    void firesOnceAndRearmsOnlyAfterDroppingBelowThreshold() throws Exception {
        Poll poll = poll(builder().build());
        count(9);
        assertTrue(poll.run().isEmpty());
        assertTrue(poll.armed());
        count(10);
        assertTrue(poll.run().isEmpty(), "equality does not breach");
        count(11);
        var execution = poll.run().orElseThrow();
        assertEquals(11L, ((Number) execution.getTrigger().getVariables().get("count")).longValue());
        assertEquals(10L, ((Number) execution.getTrigger().getVariables().get("threshold")).longValue());
        assertFalse(poll.armed());
        assertTrue(poll.run().isEmpty(), "a sustained breach does not fire twice");
        count(10);
        assertTrue(poll.run().isEmpty());
        assertFalse(poll.armed(), "equality does not re-arm");
        count(12);
        assertTrue(poll.run().isEmpty(), "returning above after equality stays disarmed");
        count(9);
        assertTrue(poll.run().isEmpty());
        assertTrue(poll.armed());
        count(11);
        assertTrue(poll.run().isPresent(), "a drop below re-arms the next crossing");
    }

    @Test
    void transientFailuresPreserveArmedStateAndRetryNextPoll() throws Exception {
        Poll poll = poll(builder().build());
        count(0);
        poll.run();
        for (int status : List.of(429, 500, 503)) {
            server.reply("POST", TelemetryAggregateTest.path("logs"), status, "{\"errors\":[\"unavailable\"]}");
            assertTrue(poll.run().isEmpty());
            assertTrue(poll.armed());
        }
        count(11);
        assertTrue(poll.run().isPresent());
    }

    @Test
    void clientErrorsAndIncompleteResponsesFailWithoutMovingState() throws Exception {
        Poll poll = poll(builder().build());
        count(0);
        poll.run();
        for (int status : List.of(400, 403, 404)) {
            server.reply("POST", TelemetryAggregateTest.path("logs"), status, "{\"errors\":[\"request rejected\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, poll::run).getStatus());
            assertTrue(poll.armed());
        }
        server.reply("POST", TelemetryAggregateTest.path("logs"), 200, TelemetryAggregateTest.answer("logs", "11").replace("done", "timeout"));
        assertThrows(IllegalStateException.class, poll::run);
        assertTrue(poll.armed());
        count(11);
        assertTrue(poll.run().isPresent());
    }

    @Test
    void firstPollOutageDoesNotCreateAFalseBaseline() throws Exception {
        Poll poll = poll(builder().build());
        server.reply("POST", TelemetryAggregateTest.path("logs"), 429, "{\"errors\":[\"rate limit\"]}");
        assertTrue(poll.run().isEmpty());
        count(11);
        assertTrue(poll.run().isEmpty(), "first successful sample is baseline only");
        assertFalse(poll.armed());
    }

    @Test
    void validatesThresholdWindowAndIntervalBeforeCallingDatadog() {
        assertThrows(jakarta.validation.ConstraintViolationException.class, () -> poll(builder().threshold(null).build()).run());
        for (long boundary : List.of(-1L, 0L)) {
            assertThrows(IllegalArgumentException.class, () -> poll(builder().threshold(Property.ofValue(boundary)).build()).run());
        }
        for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1))) {
            assertThrows(IllegalArgumentException.class, () -> poll(builder().window(Property.ofValue(duration)).build()).run());
        }
        for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThrows(IllegalArgumentException.class, () -> poll(builder().interval(duration).build()).run());
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void executionCreationFailureKeepsTheCrossingArmedForRetry() throws Exception {
        Poll poll = poll(builder().build());
        count(9);
        poll.run();
        count(11);
        // Missing flow makes execution creation fail after the aggregate request succeeds.
        ConditionContext broken = ConditionContext.builder().runContext(poll.condition().getRunContext()).build();
        assertThrows(NullPointerException.class, () -> poll.trigger().evaluate(broken, poll.context()));
        assertTrue(poll.armed());
        assertTrue(poll.run().isPresent(), "retry still emits the crossing after execution creation failed");
    }
}
