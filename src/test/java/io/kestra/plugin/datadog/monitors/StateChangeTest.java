package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.contract.OpenApiContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateChangeTest extends AbstractDatadogTest {
    @AfterEach
    void assertContracts() {
        OpenApiContract.assertRequests(server.requests, "v1:ListMonitors");
    }

    private static String monitor(int id, String state, Instant modified) {
        var response = "{\"id\":" + id + ",\"name\":\"m" + id + "\",\"query\":\"avg(last_5m):avg:system.cpu.user{*} > 90\",\"type\":\"query alert\",\"overall_state\":\"" + state + "\",\"overall_state_modified\":\"" + modified + "\"}";
        OpenApiContract.of("v1:ListMonitors").assertResponse(200, "[" + response + "]");
        return response;
    }

    private StateChange.StateChangeBuilder<?, ?> trigger() {
        return StateChange.builder()
            .id(IdUtils.create())
            .type(StateChange.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()))
            .interval(Duration.ofMinutes(1));
    }

    private record Poll(StateChange trigger, ConditionContext conditionContext, TriggerContext triggerContext) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(conditionContext, triggerContext);
        }

        KVStore kv() {
            return conditionContext.getRunContext().namespaceKv(triggerContext.getNamespace());
        }
    }

    private Poll poll(StateChange trigger) {
        var entry = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, entry.getKey(), entry.getValue());
    }

    private void firstPoll(Poll poll) throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        assertTrue(poll.run().isEmpty(), "the first poll starts nothing");
    }

    @Test
    void theFirstPollOnlyRecordsTheDatadogTimeAndReplaysNothing() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", Instant.now()) + "]");
        var poll = poll(trigger().build());

        assertTrue(poll.run().isEmpty(), "monitors that already alert are not replayed");
        assertEquals(1, server.requests.size());

        var key = StatefulTriggerService.defaultKey(poll.triggerContext().getNamespace(), poll.triggerContext().getFlowId(), poll.triggerContext().getTriggerId());
        assertTrue(poll.kv().getValue(key).isPresent(), "the cursor is stored");
        // the same alert is not returned by the next poll either
        assertTrue(poll.run().isEmpty());
    }

    @Test
    void twoMonitorsChangedInTheSameSecondAreBothReturnedEvenWhenTheSecondOneComesLater() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        var second = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", second) + "]");

        assertEquals(List.of(1), ids(poll.run().orElseThrow()));

        // monitor 2 changed in the same second but is only listed now
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", second) + "," + monitor(2, "Alert", second) + "]");
        assertEquals(List.of(2), ids(poll.run().orElseThrow()), "only the monitor that was not returned yet");

        assertTrue(poll.run().isEmpty(), "and nothing is returned twice");
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ids(Execution execution) {
        return ((List<Map<String, Object>>) execution.getTrigger().getVariables().get("monitors")).stream().map(m -> ((Number) m.get("id")).intValue()).toList();
    }

    @Test
    void aNetworkFailureSkipsThePoll() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        server.stop();

        assertTrue(poll.run().isEmpty(), "a connection that is refused does not fail the trigger");
    }

    @Test
    void returnsOnlyTheMonitorsThatChangedAfterTheCursorAndAreInAWatchedState() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        var now = Instant.now();
        server.reply("GET", "/api/v1/monitor", 200, "[" +
            monitor(1, "Alert", now.plusSeconds(10)) + "," +      // new alert
            monitor(2, "Alert", now.minusSeconds(3600)) + "," +   // alerting since before the cursor
            monitor(3, "OK", now.plusSeconds(20)) + "," +         // changed, but not a watched state
            monitor(4, "Warn", now.plusSeconds(30)) + "," +       // warn is not watched by default
            monitor(5, "Alert", now.plusSeconds(5)) +             // second new alert, older than 1
            "]");

        var execution = poll.run();

        assertTrue(execution.isPresent());
        @SuppressWarnings("unchecked")
        var monitors = (List<Map<String, Object>>) ((Map<String, Object>) execution.get().getTrigger().getVariables()).get("monitors");
        assertEquals(List.of(5, 1), monitors.stream().map(m -> ((Number) m.get("id")).intValue()).toList(), "oldest change first");
        assertEquals(2, ((Number) execution.get().getTrigger().getVariables().get("count")).intValue());
        assertEquals(API_KEY, server.requests.get(0).header("DD-API-KEY"));
        assertEquals(APP_KEY, server.requests.get(0).header("DD-APPLICATION-KEY"));
    }

    @Test
    void aMonitorIsReturnedOnce() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        var changed = Instant.now().plusSeconds(10);
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", changed) + "]");

        assertTrue(poll.run().isPresent());
        assertTrue(poll.run().isEmpty(), "the same alert is not returned again by the next poll");
    }

    @Test
    void watchedStatesCanBeChosen() throws Exception {
        var poll = poll(trigger().states(Property.ofValue(List.of(StateChange.State.WARN, StateChange.State.NO_DATA))).build());
        firstPoll(poll);
        var now = Instant.now();
        server.reply("GET", "/api/v1/monitor", 200, "[" +
            monitor(1, "Alert", now.plusSeconds(10)) + "," +
            monitor(2, "Warn", now.plusSeconds(11)) + "," +
            monitor(3, "No Data", now.plusSeconds(12)) + "]");

        var execution = poll.run();

        assertTrue(execution.isPresent());
        assertEquals(2, ((Number) execution.get().getTrigger().getVariables().get("count")).intValue());
    }

    @Test
    void aRateLimitSkipsThePollAndKeepsTheCursor() throws Exception {
        assertSkippedThenRecovers(429, "{\"errors\":[\"Too many requests\"]}");
    }

    @Test
    void anOutageSkipsThePollAndKeepsTheCursor() throws Exception {
        assertSkippedThenRecovers(503, "{\"errors\":[\"down\"]}");
    }

    private void assertSkippedThenRecovers(int status, String body) throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        var changed = Instant.now().plusSeconds(10);
        server.reply("GET", "/api/v1/monitor", status, body);

        assertTrue(poll.run().isEmpty(), "the poll is skipped, not failed");

        // Datadog answers again: the alert that came during the outage is returned
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", changed) + "]");
        assertTrue(poll.run().isPresent(), "the alert missed during the outage is returned once Datadog answers again");
    }

    @Test
    void anAuthenticationErrorFailsTheTrigger() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        server.reply("GET", "/api/v1/monitor", 403, "{\"errors\":[\"Forbidden\"]}");

        var error = assertThrows(IllegalStateException.class, poll::run);

        assertTrue(error.getMessage().contains("403"), error.getMessage());
        assertFalse(error.getMessage().contains(API_KEY));
    }

    @Test
    void anExecutionIsNotStartedWhenNothingChanged() throws Exception {
        var poll = poll(trigger().build());
        firstPoll(poll);
        server.reply("GET", "/api/v1/monitor", 200, "[]");

        assertTrue(poll.run().isEmpty());
    }

    @Test
    void parsesTheDateFormatsDatadogSends() {
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), StateChange.parseInstant("2026-01-01T10:00:00+00:00"));
        assertEquals(Instant.parse("2026-01-01T10:00:00.123456Z"), StateChange.parseInstant("2026-01-01T10:00:00.123456+00:00"));
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), StateChange.parseInstant("2026-01-01T10:00:00"));
        assertEquals(null, StateChange.parseInstant("not a date"));
        assertEquals(null, StateChange.parseInstant(null));
    }
}
