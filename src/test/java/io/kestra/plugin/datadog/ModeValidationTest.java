package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.e2e.ScenarioTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModeValidationTest extends AbstractDatadogTest {
    record Case(String task, String field, Map<String, Object> properties) { }

    @TestFactory
    Stream<DynamicTest> modeSpecificPropertiesAreRejectedByNameBeforeHttp() throws Exception {
        var scenarios = ScenarioTest.fixtures();
        return Stream.of(
            new Case("metrics.Query", "lookback", Map.of("from", "2026-01-01T00:00:00Z", "lookback", "PT1H")),
            new Case("metrics.QueryScalar", "lookback", Map.of("from", "2026-01-01T00:00:00Z", "lookback", "PT1H")),
            new Case("metrics.QueryTimeseries", "lookback", Map.of("from", "2026-01-01T00:00:00Z", "lookback", "PT1H")),
            new Case("ddsql.Query", "lookback", Map.of("from", "2026-01-01T00:00:00Z", "lookback", "PT1H")),
            new Case("monitors.Apply", "validateFirst", Map.of("plan", true, "validateFirst", true)),
            new Case("monitors.Configuration", "maxResults", Map.of("resourceId", "fixture", "maxResults", 1)),
            new Case("monitors.Export", "format", Map.of("fetchType", "NONE", "format", "YAML")),
            new Case("api.Call", "maxItems", Map.of("paginate", false, "maxItems", 1))
        ).map(test -> DynamicTest.dynamicTest(test.task() + ":" + test.field(), () -> {
            server.requests.clear();
            var config = new LinkedHashMap<String, Object>(JacksonMapper.ofJson().convertValue(scenarios.path(test.task()).path("properties"), Map.class));
            config.putAll(test.properties());
            config.putAll(Map.of("id", IdUtils.create(), "type", "io.kestra.plugin.datadog." + test.task(), "apiKey", API_KEY, "appKey", APP_KEY, "apiUrl", server.url()));
            var task = (RunnableTask<?>) JacksonMapper.ofJson().convertValue(config, Task.class);
            var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
            assertTrue(error.getMessage().contains(test.field()), error.getMessage());
            assertTrue(server.requests.isEmpty());
        }));
    }
}
