package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RunTest extends AbstractDatadogTest {
    private static final String TRIGGER = "/api/v1/synthetics/tests/trigger/ci";
    private static final String BATCH = "/api/v1/synthetics/ci/batch/batch-1";

    private Run.RunBuilder<?, ?> task() {
        return Run.builder().id(IdUtils.create()).type(Run.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .testIds(Property.ofValue(List.of("abc-def-ghi"))).maxWait(Property.ofValue(Duration.ofSeconds(5)))
            .pollInterval(Property.ofValue(Duration.ofMillis(5)));
    }

    private void trigger() {
        // SyntheticsTriggerCITestsResponse: batch_id and result identifiers, not execution state.
        server.reply("POST", TRIGGER, 200, """
            {"batch_id":"batch-1","results":[{"public_id":"abc-def-ghi","result_id":"result-1","location":1}],"triggered_check_ids":["abc-def-ghi"]}
            """);
    }

    private String batch(String status, String resultStatus, String rule) {
        return "{\"data\":{\"status\":\"" + status + "\",\"results\":[{\"test_public_id\":\"abc-def-ghi\","
            + "\"test_name\":\"Smoke test\",\"test_type\":\"api\",\"result_id\":\"result-1\",\"location\":\"aws:eu-west-3\","
            + "\"status\":\"" + resultStatus + "\",\"execution_rule\":\"" + rule + "\"}]}}";
    }

    private void details() {
        server.reply("GET", "/api/v1/synthetics/tests/abc-def-ghi/results/result-1", 200, """
            {"check":{"config":{}},"status":0,"result":{"eventType":"finished","failure":{"code":"INCORRECT_ASSERTION","message":"Expected response contained private request content"}}}
            """);
    }

    @Test
    void allPass() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, batch("passed", "passed", "blocking"));
        var output = task().overrides(Property.ofValue(Map.of("startUrl", "https://staging.example.com"))).build().run(runContext());
        assertEquals("batch-1", output.getBatchId());
        assertEquals(Run.Status.PASSED, output.getStatus());
        assertTrue(output.getPassed());
        assertFalse(output.getTimedOut());
        assertNull(output.getResults().getFirst().getFailureCode());
        assertEquals("aws:eu-west-3", output.getResults().getFirst().getLocation());
        assertEquals(Map.of("tests", List.of(Map.of("public_id", "abc-def-ghi", "startUrl", "https://staging.example.com"))), server.requests.getFirst().json());
        assertEquals(API_KEY, server.requests.getFirst().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.requests.getFirst().header("DD-APPLICATION-KEY"));
    }

    @Test
    void oneBlockingFailureFailsWithTestLocationAndCode() {
        trigger();
        server.reply("GET", BATCH, 200, batch("failed", "failed", "blocking"));
        details();
        var error = assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
        assertTrue(error.getMessage().contains("abc-def-ghi at aws:eu-west-3: INCORRECT_ASSERTION"));
        assertFalse(error.getMessage().contains("private request content"));
    }

    @Test
    void oneFailureAmongPassingTestsFailsTheGate() throws Exception {
        trigger();
        var failed = JacksonMapper.ofJson().readValue(batch("failed", "failed", "blocking"), Map.class);
        var passed = JacksonMapper.ofJson().readValue(batch("passed", "passed", "blocking"), Map.class);
        var data = Synthetics.object(failed.get("data"), "data");
        var passing = Synthetics.rows(Synthetics.object(passed.get("data"), "data").get("results"), "results").getFirst();
        data.put("results", List.of(passing, Synthetics.rows(data.get("results"), "results").getFirst()));
        server.reply("GET", BATCH, 200, JacksonMapper.ofJson().writeValueAsString(failed));
        details();
        assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
    }

    @Test
    void browserFailureUsesBrowserResultPath() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, batch("failed", "failed", "blocking").replace("\"api\"", "\"browser\""));
        server.reply("GET", "/api/v1/synthetics/tests/browser/abc-def-ghi/results/result-1", 200,
            "{\"result\":{\"passed\":false,\"failure\":{\"code\":\"ASSERTION_FAILURE\",\"message\":\"Element missing\"}}}");
        var output = task().failOnFailure(Property.ofValue(false)).build().run(runContext());
        assertEquals("ASSERTION_FAILURE", output.getResults().getFirst().getFailureCode());
    }

    @Test
    void failedResultCannotBeOverriddenByPassedBatch() {
        trigger();
        server.reply("GET", BATCH, 200, batch("passed", "failed", "blocking"));
        details();
        assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
    }

    @Test
    void nonblockingFailureIsReportedWithoutFailingTheTask() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, batch("passed", "failed", "non_blocking"));
        details();
        var result = task().build().run(runContext());
        assertTrue(result.getPassed());
        assertEquals(Run.Status.FAILED, result.getResults().getFirst().getStatus());
        assertEquals("[REDACTED]", result.getResults().getFirst().getFailureMessage());
    }

    @Test
    void includeSecretsAndFailOnFailureAreExplicitOptIns() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, batch("failed", "failed", "blocking"));
        details();
        var result = task().failOnFailure(Property.ofValue(false)).includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertFalse(result.getPassed());
        assertEquals("Expected response contained private request content", result.getResults().getFirst().getFailureMessage());
    }

    @Test
    void skippedTestsRemainSkipped() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, batch("skipped", "skipped", "skipped"));
        var output = task().build().run(runContext());
        assertEquals(Run.Status.SKIPPED, output.getStatus());
        assertFalse(output.getPassed());
        assertFalse(output.getTimedOut());
        assertEquals(Run.ExecutionRule.SKIPPED, output.getResults().getFirst().getExecutionRule());
    }

    @Test
    void skippedBatchCanHaveNoScheduledResults() throws Exception {
        trigger();
        server.reply("GET", BATCH, 200, "{\"data\":{\"status\":\"skipped\",\"results\":[]}}");
        assertFalse(task().build().run(runContext()).getTimedOut());
    }

    @Test
    void deadlineCapsLongPollSleepToTheSecond() {
        trigger();
        // The spec has no enum value for progress. A missing status is unfinished.
        server.reply("GET", BATCH, 200, "{\"data\":{\"results\":[]}}");
        long start = System.nanoTime();
        var output = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> task()
            .maxWait(Property.ofValue(Duration.ofSeconds(1))).pollInterval(Property.ofValue(Duration.ofMinutes(1)))
            .failOnTimeout(Property.ofValue(false)).build().run(runContext()));
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed >= Duration.ofMillis(900).toNanos());
        assertTrue(output.getTimedOut());
        assertFalse(output.getPassed());
        assertEquals(2, server.requests.size(), "no new poll after the deadline");
    }

    @Test
    void unfinishedBatchFailsByDefault() {
        trigger();
        server.reply("GET", BATCH, 200, "{\"data\":{\"results\":[]}}");
        var error = assertThrows(IllegalStateException.class, () -> task().maxWait(Property.ofValue(Duration.ofMillis(100))).build().run(runContext()));
        assertTrue(error.getMessage().contains("did not finish"));
    }

    @TestFactory
    Stream<DynamicTest> incompleteStatesNeverPass() {
        return Stream.of("{\"data\":{\"status\":\"passed\",\"results\":[]}}", batch("in_progress", "passed", "blocking"), batch("passed", "unknown", "blocking"))
            .map(body -> DynamicTest.dynamicTest(body, () -> {
                trigger();
                server.reply("GET", BATCH, 200, body);
                var output = task().maxWait(Property.ofValue(Duration.ofMillis(100))).failOnTimeout(Property.ofValue(false)).build().run(runContext());
                assertTrue(output.getTimedOut());
                assertFalse(output.getPassed());
            }));
    }

    @TestFactory
    Stream<DynamicTest> triggerHttpErrorsAreSurfacedWithoutStartingPolling() {
        return IntStream.of(400, 403, 404, 429, 500).mapToObj(status -> DynamicTest.dynamicTest("trigger HTTP " + status, () -> {
            server.requests.clear();
            server.reply("POST", TRIGGER, status, "{\"errors\":[\"rejected\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> task().build().run(runContext()));
            assertEquals(status, error.getStatus());
            assertTrue(server.requests.stream().allMatch(request -> request.method().equals("POST") && request.path().equals(TRIGGER)));
            if (status != 429) {
                assertEquals(1, server.requests.size());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> permanentPollingErrorsFail() {
        return IntStream.of(400, 403, 404).mapToObj(status -> DynamicTest.dynamicTest("batch HTTP " + status, () -> {
            trigger();
            server.reply("GET", BATCH, status, "{\"errors\":[\"batch unavailable\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> task().build().run(runContext()));
            assertEquals(status, error.getStatus());
        }));
    }

    @TestFactory
    Stream<DynamicTest> transientPollingErrorsRecoverWithinTheBudget() {
        return IntStream.of(429, 500).mapToObj(status -> DynamicTest.dynamicTest("retry batch HTTP " + status, () -> {
            trigger();
            server.reply("GET", BATCH, status, "{\"errors\":[\"try again\"]}");
            server.reply("GET", BATCH, 200, batch("passed", "passed", "blocking"));
            assertTrue(task().build().run(runContext()).getPassed());
        }));
    }

    @Test
    void continuousRateLimitHonorsDeadline() throws Exception {
        trigger();
        server.reply("GET", BATCH, 429, "{\"errors\":[\"rate limited\"]}");
        var output = task().maxWait(Property.ofValue(Duration.ofMillis(100))).failOnTimeout(Property.ofValue(false)).build().run(runContext());
        assertTrue(output.getTimedOut());
    }

    @Test
    void failureDetailsUnavailableStillFailsTheGate() {
        trigger();
        server.reply("GET", BATCH, 200, batch("failed", "failed", "blocking"));
        server.reply("GET", "/api/v1/synthetics/tests/abc-def-ghi/results/result-1", 404, "{\"errors\":[\"missing\"]}");
        assertTrue(assertThrows(IllegalStateException.class, () -> task().build().run(runContext())).getMessage().contains("TEST_FAILED"));
    }

    @Test
    void resolvesSearchPagesBeforePostingIds() throws Exception {
        var rows = IntStream.range(0, 100).mapToObj(i -> Map.of("public_id", "test-" + i)).toList();
        server.reply("GET", "/api/v1/synthetics/tests/search", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("tests", rows)));
        server.reply("GET", "/api/v1/synthetics/tests/search", 200, "{\"tests\":[{\"public_id\":\"last-test\"}]}");
        trigger();
        server.reply("GET", BATCH, 200, batch("passed", "passed", "blocking"));
        var output = task().testIds(null).searchQuery(Property.ofValue("tag:env:prod")).maxTests(Property.ofValue(200)).build().run(runContext());
        assertTrue(output.getPassed());
        assertTrue(server.requests.get(1).query().contains("start=100"));
        var tests = (List<?>) server.requests.get(2).json().get("tests");
        assertEquals(101, tests.size());
        assertEquals(Map.of("public_id", "last-test"), tests.getLast());
        assertFalse(server.requests.get(2).body().contains("searchQuery"));
    }

    @Test
    void rejectsEmptyOrCappedSearchBeforeTriggering() {
        server.reply("GET", "/api/v1/synthetics/tests/search", 200, "{\"tests\":[]}");
        assertThrows(IllegalArgumentException.class, () -> task().testIds(null).searchQuery(Property.ofValue("tag:empty")).build().run(runContext()));
        server.reply("GET", "/api/v1/synthetics/tests/search", 200, "{\"tests\":[{\"public_id\":\"a\"}]}");
        assertThrows(IllegalArgumentException.class, () -> task().testIds(null).searchQuery(Property.ofValue("tag:all")).maxTests(Property.ofValue(1)).build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @TestFactory
    Stream<DynamicTest> validatesSelectionBeforeHttp() {
        return Stream.<java.util.function.Supplier<Run>>of(
            () -> task().testIds(null).build(),
            () -> task().searchQuery(Property.ofValue("tag:prod")).build(),
            () -> task().testIds(Property.ofValue(List.of("bad/id"))).build(),
            () -> task().maxTests(Property.ofValue(0)).build(),
            () -> task().maxTests(Property.ofValue(10001)).build(),
            () -> task().maxTests(Property.ofValue(1)).testIds(Property.ofValue(List.of("a", "b"))).build(),
            () -> task().overrides(Property.ofValue(Map.of("public_id", "other"))).build(),
            () -> task().maxWait(Property.ofValue(Duration.ZERO)).build(),
            () -> task().maxWait(Property.ofValue(Duration.ofDays(2))).build(),
            () -> task().pollInterval(Property.ofValue(Duration.ZERO)).build(),
            () -> task().pollInterval(Property.ofValue(Duration.ofDays(2))).build()
        ).map(build -> DynamicTest.dynamicTest("invalid selection or budget", () -> {
            assertThrows(IllegalArgumentException.class, () -> build.get().run(runContext()));
            assertTrue(server.requests.isEmpty());
        }));
    }
}
