package io.kestra.plugin.datadog.e2e;

import io.kestra.core.serializers.JacksonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExampleFlowTest {
    @Test
    void e2eFlowsParseAndCoverAllBaseComponents() throws Exception {
        var components = new HashSet<String>();
        try (var files = Files.list(Path.of("e2e/flows"))) {
            for (var path : files.filter(file -> file.toString().endsWith(".yaml")).toList()) {
                var flow = JacksonMapper.ofYaml().readTree(Files.readString(path));
                assertFalse(flow.path("id").asText().isBlank());
                assertEquals("hq.datadog.e2e", flow.path("namespace").asText());
                assertTrue(flow.path("tasks").isArray());
                for (var section : new String[]{"tasks", "triggers"}) {
                    for (var task : flow.path(section)) {
                        var type = task.path("type").asText();
                        if (type.startsWith("io.kestra.plugin.datadog.")) { components.add(type.substring("io.kestra.plugin.datadog.".length())); }
                    }
                }
            }
        }
        assertTrue(components.containsAll(Set.of("events.Create", "metrics.Submit", "metrics.Query", "monitors.List", "monitors.StateChange",
            "downtimes.Create", "downtimes.Cancel", "logs.Send", "logs.Search", "api.Call", "api.Operations")));
    }
}
