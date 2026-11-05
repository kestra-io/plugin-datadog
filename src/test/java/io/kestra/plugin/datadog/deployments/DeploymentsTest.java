package io.kestra.plugin.datadog.deployments;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentsTest extends AbstractDatadogTest {
    private static final String ID = "e9d2f04f-4f4b-494b-86e5-52f03e10c8e9";
    private static final String EVALUATE = "/api/v2/deployments/gates/evaluation";
    private static final String ACCEPTED = "{\"data\":{\"id\":\"" + ID + "\",\"type\":\"deployment_gates_evaluation_response\",\"attributes\":{\"evaluation_id\":\"" + ID + "\"}}}";

    private <B extends AbstractDatadogTask.AbstractDatadogTaskBuilder<?, ?>> B auth(B builder, Class<?> type) {
        builder.id(IdUtils.create());
        builder.type(type.getName());
        builder.apiKey(Property.ofValue(API_KEY));
        builder.appKey(Property.ofValue(APP_KEY));
        builder.apiUrl(Property.ofValue(server.url()));
        return builder;
    }

    private Evaluate.EvaluateBuilder<?, ?> evaluate() {
        return auth(Evaluate.builder(), Evaluate.class).service(Property.ofValue("checkout")).env(Property.ofValue("production"))
            .maxWait(Property.ofValue(Duration.ofSeconds(5))).pollInterval(Property.ofValue(Duration.ofMillis(5)));
    }

    private Record.RecordBuilder<?, ?> record() {
        return auth(Record.builder(), Record.class).appKey(null).service(Property.ofValue("checkout"))
            .startedAt(Property.ofValue(Instant.parse("2026-11-05T10:00:00.123456789Z")))
            .finishedAt(Property.ofValue(Instant.parse("2026-11-05T10:02:00Z")));
    }

    private CreateGate.CreateGateBuilder<?, ?> gate() {
        return auth(CreateGate.builder(), CreateGate.class).service(Property.ofValue("checkout")).env(Property.ofValue("production"));
    }

    private CreateMonitorRule.CreateMonitorRuleBuilder<?, ?> rule() {
        return auth(CreateMonitorRule.builder(), CreateMonitorRule.class).gateId(Property.ofValue(ID)).name(Property.ofValue("Health"))
            .query(Property.ofValue("service:checkout env:production"));
    }

    private String gateResult(String status, boolean dryRun) {
        return "{\"data\":{\"id\":\"" + ID + "\",\"type\":\"deployment_gates_evaluation_result_response\",\"attributes\":{"
            + "\"evaluation_id\":\"" + ID + "\",\"evaluation_url\":\"https://app.datadoghq.com/ci/deployment-gates/evaluations\","
            + "\"gate_id\":\"" + ID + "\",\"gate_status\":\"" + status + "\",\"dry_run\":" + dryRun
            + ",\"rules\":[{\"name\":\"Health\",\"status\":\"" + status + "\",\"reason\":\"Monitor is in ALERT\",\"dry_run\":false}]}}}";
    }

    @Test
    void evaluatesAndPollsUntilPassed() throws Exception {
        server.reply("POST", EVALUATE, 202, ACCEPTED);
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("in_progress", false));
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("pass", false));
        var output = evaluate().identifier(Property.ofValue("pre-deploy")).deploymentVersion(Property.ofValue("v1.2.3"))
            .primaryTag(Property.ofValue("region:us-east-1")).build().run(runContext());
        assertEquals(ID, output.getEvaluationId());
        assertEquals(Evaluate.Status.PASS, output.getStatus());
        assertFalse(output.getTimedOut());
        assertFalse(output.getDryRun());
        assertEquals(Map.of("data", Map.of("type", "deployment_gates_evaluation_request", "attributes", Map.of(
            "service", "checkout", "env", "production", "identifier", "pre-deploy", "version", "v1.2.3", "primary_tag", "region:us-east-1"))), server.requests.getFirst().json());
        assertEquals(3, server.requests.size());
    }

    @Test
    void failedGateHasRuleReasonAndCanBeReportedWithoutThrowing() throws Exception {
        server.reply("POST", EVALUATE, 202, ACCEPTED);
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("fail", false));
        assertTrue(assertThrows(IllegalStateException.class, () -> evaluate().build().run(runContext())).getMessage().contains("Health: Monitor is in ALERT"));
        var output = evaluate().failOnFailure(Property.ofValue(false)).build().run(runContext());
        assertEquals(Evaluate.Status.FAIL, output.getStatus());
        assertEquals("Monitor is in ALERT", output.getRules().getFirst().getReason());
    }

    @Test
    void gateDryRunIsExplicitInOutput() throws Exception {
        server.reply("POST", EVALUATE, 202, ACCEPTED);
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("pass", true));
        assertTrue(evaluate().build().run(runContext()).getDryRun());
    }

    @Test
    void gateTimeoutHonorsBudget() {
        server.reply("POST", EVALUATE, 202, ACCEPTED);
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("in_progress", false));
        var output = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> evaluate().maxWait(Property.ofValue(Duration.ofSeconds(1)))
            .pollInterval(Property.ofValue(Duration.ofMinutes(1))).failOnTimeout(Property.ofValue(false)).build().run(runContext()));
        assertTrue(output.getTimedOut());
        assertEquals(Evaluate.Status.IN_PROGRESS, output.getStatus());
    }

    @Test
    void gateTimeoutFailsByDefaultAndUnknownStatusNeverPasses() {
        server.reply("POST", EVALUATE, 202, ACCEPTED);
        server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("new_status", false));
        assertTrue(assertThrows(IllegalStateException.class, () -> evaluate().maxWait(Property.ofValue(Duration.ofMillis(100)))
            .build().run(runContext())).getMessage().contains("did not finish"));
    }

    @TestFactory
    Stream<DynamicTest> transientPollErrorsRetryButOtherErrorsFail() {
        return IntStream.of(400, 403, 404, 429, 500).mapToObj(status -> DynamicTest.dynamicTest("gate polling HTTP " + status, () -> {
            server.reply("POST", EVALUATE, 202, ACCEPTED);
            server.reply("GET", EVALUATE + "/" + ID, status, "{\"errors\":[\"unavailable\"]}");
            if (status == 429 || status == 500) {
                server.reply("GET", EVALUATE + "/" + ID, 200, gateResult("pass", false));
                assertEquals(Evaluate.Status.PASS, evaluate().build().run(runContext()).getStatus());
            } else {
                assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> evaluate().build().run(runContext())).getStatus());
            }
        }));
    }

    @Test
    void rejectsMissingOrMalformedEvaluationIdentifier() {
        server.reply("POST", EVALUATE, 202, "{\"data\":{\"attributes\":{}}}");
        assertThrows(IllegalArgumentException.class, () -> evaluate().build().run(runContext()));
        server.reply("POST", EVALUATE, 202, "{\"data\":{\"attributes\":{\"evaluation_id\":\"../other\"}}}");
        assertThrows(IllegalArgumentException.class, () -> evaluate().build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("POST")));
    }

    @Test
    void deadlineCancelsBlockedWorkInsteadOfWaitingForItsSleep() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            try (var deadline = new Deadline(Duration.ofMillis(100))) {
                assertThrows(TimeoutException.class, () -> deadline.call(() -> {
                    Thread.sleep(Duration.ofSeconds(5));
                    return true;
                }));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> recordsDoraWithNanosecondTimesAndApiKeyOnly() {
        return IntStream.of(200, 202).mapToObj(status -> DynamicTest.dynamicTest("DORA HTTP " + status, () -> {
            server.requests.clear();
            server.reply("POST", "/api/v2/dora/deployment", status, "{\"data\":{\"id\":\"4242fcdd31586083\",\"type\":\"dora_deployment\"}}");
            var output = record().env(Property.ofValue("production")).deploymentVersion(Property.ofValue("v1.2.3"))
                .deploymentId(Property.ofValue("checkout-release-123"))
                .repositoryUrl(Property.ofValue("https://github.com/example/checkout")).commitSha(Property.ofValue("66adc9350f2cc9b250b69abddab733dd55e1a588"))
                .customTags(Property.ofValue(List.of("team:delivery"))).build().run(runContext());
            assertEquals("4242fcdd31586083", output.getDeploymentId());
            assertEquals(status, output.getHttpStatus());
            var request = server.only();
            assertNull(request.header("DD-APPLICATION-KEY"));
            var data = Deployments.object(request.json().get("data"), "data");
            assertFalse(data.containsKey("type"), "DORA request has attributes without a resource type");
            var attributes = Deployments.object(data.get("attributes"), "attributes");
            var start = Instant.parse("2026-11-05T10:00:00.123456789Z");
            assertEquals(start.getEpochSecond() * 1_000_000_000L + 123456789, attributes.get("started_at"));
            assertEquals("checkout-release-123", attributes.get("id"));
            assertEquals("production", attributes.get("env"));
        }));
    }

    @Test
    void createsGateAndMonitorRuleAndReadsRules() throws Exception {
        var gateAttributes = "\"created_at\":\"2026-11-05T10:00:00Z\",\"created_by\":{\"id\":\"user-1\"},\"dry_run\":false,\"service\":\"checkout\",\"env\":\"production\",\"identifier\":\"default\"";
        server.reply("POST", "/api/v2/deployment_gates", 200, "{\"data\":{\"id\":\"" + ID + "\",\"type\":\"deployment_gate\",\"attributes\":{" + gateAttributes + "}}}");
        assertEquals(ID, gate().build().run(runContext()).getGateId());
        assertEquals("deployment_gate", Deployments.object(server.requests.getFirst().json().get("data"), "data").get("type"));
        var ruleAttributes = "{\"created_at\":\"2026-11-05T10:00:00Z\",\"created_by\":{\"id\":\"user-1\"},\"dry_run\":false,\"gate_id\":\"" + ID + "\",\"name\":\"Health\",\"type\":\"monitor\",\"options\":{\"query\":\"service:checkout env:production\"}}";
        server.reply("POST", "/api/v2/deployment_gates/" + ID + "/rules", 200, "{\"data\":{\"id\":\"rule-1\",\"type\":\"deployment_rule\",\"attributes\":" + ruleAttributes + "}}");
        assertEquals("rule-1", rule().healthySeconds(Property.ofValue(60L)).build().run(runContext()).getRuleId());
        var ruleData = Deployments.object(server.requests.getLast().json().get("data"), "data");
        var options = Deployments.object(Deployments.object(ruleData.get("attributes"), "attributes").get("options"), "options");
        assertEquals(true, options.get("fail_on_no_data"));
        assertEquals(true, options.get("fail_on_no_groups_found"));
        assertEquals(60, options.get("duration"));
        server.reply("GET", "/api/v2/deployment_gates/" + ID + "/rules", 200,
            "{\"data\":{\"id\":\"" + ID + "\",\"type\":\"list_deployment_rules\",\"attributes\":{\"rules\":[" + ruleAttributes + "]}}}");
        var output = auth(GetRules.builder(), GetRules.class).gateId(Property.ofValue(ID)).build().run(runContext());
        assertEquals(1, output.getSize());
        assertEquals("Health", output.getRules().getFirst().get("name"));
    }

    record Endpoint(String name, String method, String path, Callable<?> call) { }

    @TestFactory
    Stream<DynamicTest> taskHttpErrorMatrix() {
        return Stream.of(
            new Endpoint("Evaluate", "POST", EVALUATE, () -> evaluate().build().run(runContext())),
            new Endpoint("Record", "POST", "/api/v2/dora/deployment", () -> record().build().run(runContext())),
            new Endpoint("CreateGate", "POST", "/api/v2/deployment_gates", () -> gate().build().run(runContext())),
            new Endpoint("CreateMonitorRule", "POST", "/api/v2/deployment_gates/" + ID + "/rules", () -> rule().build().run(runContext())),
            new Endpoint("GetRules", "GET", "/api/v2/deployment_gates/" + ID + "/rules", () -> auth(GetRules.builder(), GetRules.class).gateId(Property.ofValue(ID)).build().run(runContext()))
        ).flatMap(endpoint -> IntStream.of(400, 403, 404, 429, 500).mapToObj(status -> DynamicTest.dynamicTest(endpoint.name + " HTTP " + status, () -> {
            server.requests.clear();
            server.reply(endpoint.method, endpoint.path, status, "{\"errors\":[\"rejected\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, endpoint.call::call).getStatus());
            assertTrue(server.requests.stream().allMatch(request -> request.method().equals(endpoint.method) && request.path().equals(endpoint.path)));
            if (status != 429) {
                assertEquals(1, server.requests.size());
            }
        })));
    }

    @TestFactory
    Stream<DynamicTest> validationHappensBeforeHttp() {
        return Stream.<Callable<?>>of(
            () -> evaluate().service(Property.ofValue(" ")).build().run(runContext()),
            () -> evaluate().env(null).build().run(runContext()),
            () -> evaluate().maxWait(Property.ofValue(Duration.ZERO)).build().run(runContext()),
            () -> evaluate().pollInterval(Property.ofValue(Duration.ZERO)).build().run(runContext()),
            () -> record().service(null).build().run(runContext()),
            () -> record().startedAt(null).build().run(runContext()),
            () -> record().finishedAt(null).build().run(runContext()),
            () -> record().finishedAt(Property.ofValue(Instant.EPOCH)).build().run(runContext()),
            () -> record().startedAt(Property.ofValue(Instant.MIN)).build().run(runContext()),
            () -> record().deploymentId(Property.ofValue("short")).build().run(runContext()),
            () -> record().repositoryUrl(Property.ofValue("https://example.com")).build().run(runContext()),
            () -> record().commitSha(Property.ofValue("abc")).build().run(runContext()),
            () -> gate().service(null).build().run(runContext()),
            () -> gate().env(null).build().run(runContext()),
            () -> gate().identifier(Property.ofValue(" ")).build().run(runContext()),
            () -> rule().gateId(Property.ofValue("../x")).build().run(runContext()),
            () -> rule().name(null).build().run(runContext()),
            () -> rule().query(null).build().run(runContext()),
            () -> rule().healthySeconds(Property.ofValue(-1L)).build().run(runContext()),
            () -> rule().warmupSeconds(Property.ofValue(-1L)).build().run(runContext()),
            () -> auth(GetRules.builder(), GetRules.class).build().run(runContext())
        ).map(call -> DynamicTest.dynamicTest("invalid input", () -> {
            assertThrows(IllegalArgumentException.class, call::call);
            assertTrue(server.requests.isEmpty());
        }));
    }
}
