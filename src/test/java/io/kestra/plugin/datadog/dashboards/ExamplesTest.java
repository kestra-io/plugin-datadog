package io.kestra.plugin.datadog.dashboards;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.slos.BudgetLow;
import io.kestra.plugin.datadog.slos.Check;
import io.kestra.plugin.datadog.slos.Create;
import io.kestra.plugin.datadog.slos.Delete;
import io.kestra.plugin.datadog.slos.Get;
import io.kestra.plugin.datadog.slos.History;
import io.kestra.plugin.datadog.slos.List;
import io.kestra.plugin.datadog.slos.Update;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExamplesTest extends AbstractDatadogTest {
    @Test
    void everyDashboardAndSloExampleParsesAsAFullFlow() {
        for (var task : java.util.List.of(
            Get.class, List.class, Create.class, Update.class, Delete.class, Export.class, Import.class,
            Get.class, List.class,
            Create.class, Update.class,
            Delete.class, History.class,
            Check.class, BudgetLow.class
        )) {
            var examples = task.getAnnotation(Plugin.class).examples();
            assertTrue(examples.length > 0, task.getName());
            for (var example : examples) {
                assertTrue(example.full(), task.getName());
                Flow flow = assertDoesNotThrow(() -> YamlParser.parse(String.join("\n", example.code()), Flow.class), task.getName());
                assertNotNull(flow.getId());
                assertNotNull(flow.getNamespace());
                assertFalse(flow.getTasks().isEmpty());
            }
        }
    }
}
