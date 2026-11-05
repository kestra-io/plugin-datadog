package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.e2e.ScenarioTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FetchConventionsTest extends AbstractDatadogTest {
    private static final Set<String> PAYLOADS = Set.of("rows", "items", "events", "monitors", "downtimes", "definitions", "dashboards", "slos", "tests", "results", "buckets", "costs", "hosts", "columns", "operations", "data", "body", "history", "series", "rules", "locations");

    @TestFactory
    Stream<DynamicTest> everyFetchTaskHonorsAllFourModes() throws Exception {
        var scenarios = ScenarioTest.fixtures();
        return SafetyInvariantsTest.components().stream()
            .filter(type -> SafetyInvariantsTest.fields(type).stream().anyMatch(field -> field.getName().equals("fetchType")))
            .flatMap(type -> Stream.of(FetchType.values()).map(mode -> DynamicTest.dynamicTest(type.getName() + ":" + mode, () -> {
                var name = type.getName().substring("io.kestra.plugin.datadog.".length());
                var scenario = scenarios.path(name);
                assertFalse(scenario.isMissingNode(), "Every fetch task needs a fixture");
                var config = new LinkedHashMap<String, Object>(JacksonMapper.ofJson().convertValue(scenario.path("properties"), Map.class));
                config.putAll(Map.of("id", IdUtils.create(), "type", type.getName(), "fetchType", mode.name()));
                if (!name.equals("api.Operations")) config.putAll(Map.of("apiKey", API_KEY, "appKey", APP_KEY, "apiUrl", server.url()));
                if (mode != FetchType.STORE) config.remove("format");
                for (var reply : scenario.path("replies")) server.reply(reply.path("method").asText(), reply.path("path").asText(), reply.path("status").asInt(), reply.path("body").toString());
                var task = (RunnableTask<?>) JacksonMapper.ofJson().convertValue(config, Task.class);
                var result = JacksonMapper.ofJson().valueToTree(task.run(runContext()));
                if (mode == FetchType.STORE) assertTrue(result.path("uri").asText().startsWith("kestra://"));
                else assertTrue(result.path("uri").isMissingNode() || result.path("uri").isNull());
                if (mode != FetchType.FETCH_ONE) assertTrue(result.path("row").isMissingNode() || result.path("row").isNull());
                if (mode != FetchType.FETCH) {
                    for (var field : PAYLOADS) assertTrue(result.path(field).isMissingNode() || result.path(field).isNull(), field + " leaked with " + mode);
                }
                if (mode == FetchType.FETCH_ONE && !Set.of("monitors.Configuration", "deployments.GetRules").contains(name)) {
                    assertFalse(result.path("row").isMissingNode() || result.path("row").isNull(), "Fixture has a first row");
                }
            })));
    }
}
