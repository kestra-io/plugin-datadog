package io.kestra.plugin.datadog;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.e2e.ScenarioTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.AnnotatedParameterizedType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLimitsTest extends AbstractDatadogTest {
    private static final Set<String> LIMITS = Set.of("maxResults", "maxMonitors", "maxItems", "maxTests", "maxUsers", "maxMembers", "pageSize", "rowLimit", "maxPolls");

    @Inject
    ModelValidator validator;

    @TestFactory
    Stream<DynamicTest> resultLimitsHaveContainerConstraints() throws Exception {
        return SafetyInvariantsTest.components().stream().flatMap(type -> SafetyInvariantsTest.fields(type).stream()
            .filter(field -> LIMITS.contains(field.getName())).map(field -> DynamicTest.dynamicTest(type.getName() + "." + field.getName(), () -> {
                var argument = ((AnnotatedParameterizedType) field.getAnnotatedType()).getAnnotatedActualTypeArguments()[0];
                assertNotNull(argument.getAnnotation(Min.class));
                assertNotNull(argument.getAnnotation(Max.class));
                assertEquals(1, argument.getAnnotation(Min.class).value());
                assertTrue(argument.getAnnotation(Max.class).value() <= 10000);
                assertNull(field.getAnnotation(Min.class), "Constraints belong to the Property value");
            })));
    }

    @TestFactory
    Stream<DynamicTest> zeroIsRejectedWhenTheFlowIsValidated() throws Exception {
        var scenarios = ScenarioTest.fixtures();
        return SafetyInvariantsTest.components().stream().flatMap(type -> SafetyInvariantsTest.fields(type).stream()
            .filter(field -> LIMITS.contains(field.getName())).map(field -> DynamicTest.dynamicTest(type.getName() + "." + field.getName(), () -> {
                var name = type.getName().substring("io.kestra.plugin.datadog.".length());
                var config = new LinkedHashMap<String, Object>();
                if (scenarios.has(name)) config.putAll(JacksonMapper.ofJson().convertValue(scenarios.path(name).path("properties"), Map.class));
                config.putAll(Map.of("id", IdUtils.create(), "type", type.getName(), "apiKey", API_KEY, "appKey", APP_KEY));
                config.put(field.getName(), 0);
                var section = name.equals("monitors.StateChange") ? "triggers" : "tasks";
                var flow = JacksonMapper.ofJson().convertValue(Map.of("id", IdUtils.create(), "namespace", "tests.datadog", section, List.of(config)), Flow.class);
                var error = assertThrows(ConstraintViolationException.class, () -> validator.validate(flow));
                assertTrue(error.getConstraintViolations().stream().anyMatch(v -> v.getPropertyPath().toString().contains(field.getName())), error.toString());
                assertTrue(server.requests.isEmpty());
            })));
    }
}
