package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AreaExamplesTest extends AbstractDatadogTest {
    @TestFactory
    Stream<DynamicTest> everyAreaExampleParsesAsAFlow() {
        return Stream.of(QueryScalar.class, QueryTimeseries.class, Check.class, Threshold.class,
            io.kestra.plugin.datadog.hosts.List.class, io.kestra.plugin.datadog.hosts.Totals.class,
            io.kestra.plugin.datadog.hosts.Mute.class, io.kestra.plugin.datadog.hosts.Unmute.class,
            io.kestra.plugin.datadog.usage.Hourly.class, io.kestra.plugin.datadog.usage.Check.class,
            io.kestra.plugin.datadog.cost.Estimated.class, io.kestra.plugin.datadog.cost.Historical.class,
            io.kestra.plugin.datadog.ddsql.Query.class).map(type -> DynamicTest.dynamicTest(type.getName(), () -> {
                var examples = type.getAnnotation(Plugin.class).examples();
                assertTrue(examples.length > 0);
                for (var example : examples) {
                    assertTrue(example.full());
                    Flow flow = YamlParser.parse(String.join("\n", example.code()), Flow.class);
                    assertNotNull(flow.getId());
                    assertNotNull(flow.getNamespace());
                    assertFalse(flow.getTasks().isEmpty());
                }
            }));
    }
}
