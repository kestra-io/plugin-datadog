package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ThresholdTest extends AbstractDatadogTest {
    private Threshold.ThresholdBuilder<?, ?> trigger() {
        return Threshold.builder().id(IdUtils.create()).type(Threshold.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .query(Property.ofValue("avg:system.cpu.user{*}")).threshold(Property.ofValue(new BigDecimal("80")));
    }

    private record Poll(Threshold trigger, ConditionContext condition, TriggerContext context) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(condition, context);
        }

        byte[] state() throws Exception {
            return (byte[]) condition.getRunContext().namespaceKv(context.getNamespace()).getValue(
                StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId())).orElseThrow().value();
        }
    }

    private Poll poll(Threshold trigger) {
        var mocked = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, mocked.getKey(), mocked.getValue());
    }

    private void reply(String values) {
        server.reply("POST", FormulaQueriesTest.SCALAR, 200, FormulaQueriesTest.scalar(values));
    }

    @Test
    void firstPollRecordsExistingBreachWithoutExecution() throws Exception {
        var poll = poll(trigger().build());
        reply("90");
        assertTrue(poll.run().isEmpty());
        assertTrue(poll.state().length > 0);
        assertTrue(poll.run().isEmpty());
    }

    @Test
    void crossingFiresOnceAndRecoveryRearms() throws Exception {
        var poll = poll(trigger().build());
        reply("70");
        assertTrue(poll.run().isEmpty());
        reply("90");
        var execution = poll.run().orElseThrow();
        assertEquals(90, ((Number) execution.getTrigger().getVariables().get("value")).intValue());
        assertTrue(poll.run().isEmpty(), "a sustained breach does not repeat");
        reply("80");
        assertTrue(poll.run().isEmpty(), "equality recovers for GT");
        reply("81");
        assertTrue(poll.run().isPresent(), "recovery re-arms the next crossing");
        assertTrue(poll.run().isEmpty());
    }

    @Test
    void noDataPreservesArmedAndBreachedStates() throws Exception {
        var poll = poll(trigger().build());
        reply("null");
        assertTrue(poll.run().isEmpty());
        reply("70");
        assertTrue(poll.run().isEmpty());
        var armed = poll.state();
        reply("null");
        assertTrue(poll.run().isEmpty());
        assertArrayEquals(armed, poll.state());
        reply("90");
        assertTrue(poll.run().isPresent());
        var breached = poll.state();
        reply("null");
        assertTrue(poll.run().isEmpty());
        assertArrayEquals(breached, poll.state());
        reply("95");
        assertTrue(poll.run().isEmpty());
    }

    @TestFactory
    Stream<DynamicTest> httpErrorsKeepStateAndTransientErrorsSkip() {
        return Stream.of(400, 403, 404, 429, 503).map(status -> DynamicTest.dynamicTest("HTTP " + status, () -> {
            var poll = poll(trigger().build());
            reply("70");
            poll.run();
            var before = poll.state();
            server.reply("POST", FormulaQueriesTest.SCALAR, status, "{\"errors\":[\"request failed\"]}");
            if (status == 429 || status >= 500) {
                assertTrue(poll.run().isEmpty());
            } else {
                assertEquals(status, assertThrows(DatadogClient.DatadogException.class, poll::run).getStatus());
            }
            assertArrayEquals(before, poll.state());
            reply("90");
            assertTrue(poll.run().isPresent());
        }));
    }

    @Test
    void executionCreationFailureKeepsTheCrossingArmed() throws Exception {
        var poll = poll(trigger().build());
        reply("70");
        poll.run();
        var before = poll.state();
        reply("90");
        var broken = ConditionContext.builder().runContext(poll.condition().getRunContext()).build();
        assertThrows(Exception.class, () -> poll.trigger().evaluate(broken, poll.context()));
        assertArrayEquals(before, poll.state());
        assertTrue(poll.run().isPresent());
    }

    @Test
    void aChangedThresholdStartsANewBaseline() throws Exception {
        var original = trigger().build();
        var poll = poll(original);
        reply("70");
        poll.run();
        var changed = trigger().id(IdUtils.create()).threshold(Property.ofValue(new BigDecimal("60"))).build();
        assertTrue(changed.evaluate(poll.condition(), poll.context()).isEmpty());
        assertTrue(changed.evaluate(poll.condition(), poll.context()).isEmpty());
    }

    @Test
    void validatesQueryThresholdAndDurationsBeforeCalling() {
        for (var invalid : List.of(trigger().query(Property.ofValue(" ")).build(),
            trigger().lookback(Property.ofValue(Duration.ZERO)).build(), trigger().interval(Duration.ZERO).build())) {
            assertThrows(IllegalArgumentException.class, () -> poll(invalid).run());
            assertTrue(server.requests.isEmpty());
        }
        assertThrows(ConstraintViolationException.class, () -> poll(trigger().threshold(null).build()).run());
        assertTrue(server.requests.isEmpty());
    }
}
