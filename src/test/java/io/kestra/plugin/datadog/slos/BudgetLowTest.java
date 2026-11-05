package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BudgetLowTest extends AbstractDatadogTest {
    private BudgetLow.BudgetLowBuilder<?, ?> trigger() {
        return BudgetLow.builder().id(IdUtils.create()).type(BudgetLow.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .sloId(Property.ofValue("slo-123")).target(Property.ofValue(99.9)).window(Property.ofValue(Duration.ofDays(7)));
    }

    private record Poll(BudgetLow trigger, ConditionContext condition, TriggerContext context) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(condition, context);
        }

        byte[] cursor() throws Exception {
            var key = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
            return (byte[]) condition.getRunContext().namespaceKv(context.getNamespace()).getValue(key).orElseThrow().value();
        }
    }

    private Poll poll(BudgetLow trigger) {
        var mock = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, mock.getKey(), mock.getValue());
    }

    private void reply(String sli) {
        server.reply("GET", "/api/v1/slo/slo-123/history", 200, SlosTest.history(sli));
    }

    @Test
    void firstPollOnlyRecordsCurrentBudgetEvenWhenAlreadyLow() throws Exception {
        reply("99.9");
        var poll = poll(trigger().build());
        assertTrue(poll.run().isEmpty());
        assertTrue(poll.run().isEmpty());
        assertEquals(true, JacksonMapper.ofJson().readValue(poll.cursor(), Map.class).get("below"));
        assertTrue(server.requests.getFirst().query().contains("apply_correction=true"));
    }

    @Test
    void firesOncePerCrossingAndRecoveryRearmsIt() throws Exception {
        reply("99.95");
        reply("99.91");
        reply("99.90");
        reply("99.92");
        reply("99.91");
        var poll = poll(trigger().build());
        assertTrue(poll.run().isEmpty());
        var first = poll.run();
        assertTrue(first.isPresent());
        assertEquals(10.0, ((Number) first.get().getTrigger().getVariables().get("remainingBudget")).doubleValue());
        assertEquals("slo-123", first.get().getTrigger().getVariables().get("sloId"));
        assertTrue(poll.run().isEmpty());
        assertTrue(poll.run().isEmpty(), "equality is recovery");
        assertTrue(poll.run().isPresent());
    }

    @Test
    void rateLimitAndServerFailurePreserveCursorAndRetryCrossing() throws Exception {
        var poll = poll(trigger().build());
        reply("99.95");
        poll.run();
        var cursor = poll.cursor();
        for (var status : new int[]{429, 500, 503}) {
            server.reply("GET", "/api/v1/slo/slo-123/history", status, "{\"errors\":[\"unavailable\"]}");
            assertTrue(poll.run().isEmpty());
            assertArrayEquals(cursor, poll.cursor());
        }
        reply("99.9");
        assertTrue(poll.run().isPresent());
    }

    @Test
    void authenticationAndInvalidDataFailWithoutMovingCursor() throws Exception {
        var poll = poll(trigger().build());
        reply("99.95");
        poll.run();
        var cursor = poll.cursor();
        for (var status : new int[]{400, 403, 404}) {
            server.reply("GET", "/api/v1/slo/slo-123/history", status, "{\"errors\":[\"failed\"]}");
            assertThrows(IllegalStateException.class, poll::run);
            assertArrayEquals(cursor, poll.cursor());
        }
        reply("null");
        assertThrows(IllegalStateException.class, poll::run);
        assertArrayEquals(cursor, poll.cursor());
    }

    @Test
    void initialRateLimitDoesNotInitializeTheCursor() throws Exception {
        var poll = poll(trigger().build());
        server.reply("GET", "/api/v1/slo/slo-123/history", 429, "{\"errors\":[\"rate limit\"]}");
        assertTrue(poll.run().isEmpty());
        reply("99.9");
        assertTrue(poll.run().isEmpty(), "first successful sample is only a baseline");
    }

    @Test
    void validatesTriggerPropertiesBeforePolling() {
        assertThrows(ConstraintViolationException.class, () -> poll(trigger().sloId(null).build()).run());
        assertThrows(ConstraintViolationException.class, () -> poll(trigger().target(null).build()).run());
        assertThrows(IllegalArgumentException.class, () -> poll(trigger().target(Property.ofValue(100.0)).build()).run());
        assertThrows(IllegalArgumentException.class, () -> poll(trigger().minRemainingBudget(Property.ofValue(101.0)).build()).run());
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThrows(IllegalArgumentException.class, () -> poll(trigger().window(Property.ofValue(duration)).build()).run());
        }
        assertThrows(IllegalArgumentException.class, () -> poll(trigger().interval(Duration.ZERO).build()).run());
        assertThrows(IllegalArgumentException.class, () -> poll(trigger().interval(Duration.ofSeconds(-1)).build()).run());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void changedConfigurationStartsANewBaseline() throws Exception {
        var triggerId = IdUtils.create();
        var first = poll(trigger().id(IdUtils.create()).build());
        reply("99.95");
        first.run();
        reply("99.9");
        var changed = trigger().id(IdUtils.create()).minRemainingBudget(Property.ofValue(30.0)).build();
        assertTrue(changed.evaluate(first.condition(), first.context()).isEmpty());
        assertTrue(changed.evaluate(first.condition(), first.context()).isEmpty());
    }
}
