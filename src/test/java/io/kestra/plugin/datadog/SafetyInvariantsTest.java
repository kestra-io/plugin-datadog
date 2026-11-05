package io.kestra.plugin.datadog;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.e2e.ScenarioTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Safety contracts apply to every plugin component in the default test suite. */
class SafetyInvariantsTest extends AbstractDatadogTest {
    private static final String PREFIX = "io.kestra.plugin.datadog.";
    private static final Set<String> PLANNERS = Set.of(
        "catalog.UpsertServiceDefinition", "dashboards.Import", "monitors.Apply", "teams.SyncMembers");

    static List<Class<?>> components() throws Exception {
        Path source = Path.of("src/main/java/io/kestra/plugin/datadog");
        List<Class<?>> classes = new ArrayList<>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java")).toList()) {
                String name = source.relativize(path).toString().replace('/', '.').replace(".java", "");
                Class<?> type = Class.forName(PREFIX + name);
                if (type.isAnnotationPresent(Plugin.class) && !Modifier.isAbstract(type.getModifiers())) classes.add(type);
            }
        }
        assertFalse(classes.isEmpty(), "Reflection inventory must not be empty");
        return classes;
    }

    static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        while (type != null && type.getName().startsWith(PREFIX)) {
            Arrays.stream(type.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).forEach(fields::add);
            type = type.getSuperclass();
        }
        return fields;
    }

    @TestFactory
    Stream<DynamicTest> noPluginPropertyShadowsTaskType() throws Exception {
        return components().stream().map(type -> DynamicTest.dynamicTest(type.getName(), () ->
            assertTrue(fields(type).stream().noneMatch(f -> f.getName().equals("type")), "Plugin declares reserved type property")));
    }

    @TestFactory
    Stream<DynamicTest> destructiveTasksIdentifySingleObjects() throws Exception {
        return components().stream().filter(type -> type.getSimpleName().matches("^(Delete|Revoke|Disable|Remove|Cancel|Purge).*"))
            .map(type -> DynamicTest.dynamicTest(type.getName(), () -> {
                List<Field> fields = fields(type);
                assertTrue(fields.stream().anyMatch(f -> f.getName().endsWith("Id") || Set.of("serviceName", "hostName").contains(f.getName())), "Missing explicit single-object identifier");
                assertTrue(fields.stream().noneMatch(f -> f.getGenericType().getTypeName().contains("java.util.List") || f.getName().matches("(?i).*(query|filter|ids)$")), "Destructive task exposes bulk selection");
            }));
    }

    private RunnableTask<?> scenarioTask(JsonNode scenario, Map<String, Object> overrides) throws Exception {
        Map<String, Object> config = new LinkedHashMap<>(JacksonMapper.ofJson().convertValue(scenario.path("properties"), Map.class));
        config.putAll(overrides);
        config.putAll(Map.of("id", IdUtils.create(), "type", PREFIX + scenario.path("task").asText(), "apiKey", API_KEY, "appKey", APP_KEY, "apiUrl", server.url()));
        for (JsonNode reply : scenario.path("replies")) {
            server.reply(reply.path("method").asText(), reply.path("path").asText(), reply.path("status").asInt(),
                reply.path("body").isNull() ? "" : reply.path("body").toString());
        }
        return (RunnableTask<?>) JacksonMapper.ofJson().convertValue(config, Task.class);
    }

    @TestFactory
    Stream<DynamicTest> everyDryRunIsAuditedForZeroRequests() throws Exception {
        JsonNode scenarios = ScenarioTest.fixtures();
        return components().stream().filter(type -> fields(type).stream().anyMatch(f -> f.getName().equals("dryRun")))
            .map(type -> DynamicTest.dynamicTest(type.getName(), () -> {
                String name = type.getName().substring(PREFIX.length());
                JsonNode scenario = scenarios.path(name);
                assertFalse(scenario.isMissingNode(), "Missing executable dry-run fixture");
                server.requests.clear();
                scenarioTask(scenario, Map.of("dryRun", true)).run(runContext());
                assertEquals(0, server.requests.size(), name + " dryRun sends requests");
            }));
    }

    @TestFactory
    Stream<DynamicTest> plansReadWithoutSendingWriteMethods() throws Exception {
        JsonNode scenarios = ScenarioTest.fixtures();
        return PLANNERS.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            server.requests.clear();
            var task = scenarioTask(scenarios.path(name), Map.of("plan", true));
            assertTrue(fields(task.getClass()).stream().anyMatch(f -> f.getName().equals("plan")));
            assertTrue(fields(task.getClass()).stream().noneMatch(f -> f.getName().equals("dryRun")));
            JsonNode output = JacksonMapper.ofJson().valueToTree(task.run(runContext()));
            assertTrue(output.path("plan").asBoolean());
            assertFalse(server.requests.isEmpty(), "A plan must read the current state");
            assertTrue(server.requests.stream().allMatch(r -> r.method().equals("GET")), "Planning must send only GET requests");
        }));
    }

    @TestFactory
    Stream<DynamicTest> persistedGateEvaluationModeHasAnExplicitName() throws Exception {
        JsonNode scenario = ScenarioTest.fixtures().path("deployments.CreateGate");
        return Stream.of(false, true).map(mode -> DynamicTest.dynamicTest("evaluationDryRun=" + mode, () -> {
            server.requests.clear();
            var task = scenarioTask(scenario, Map.of("evaluationDryRun", mode));
            assertTrue(fields(task.getClass()).stream().noneMatch(f -> f.getName().equals("dryRun")));
            task.run(runContext());
            assertEquals("POST", server.only().method());
            assertEquals(mode, JacksonMapper.ofJson().valueToTree(server.only().json()).at("/data/attributes/dry_run").asBoolean());
        }));
    }

    @Test
    void secretBearingNamedOutputFieldsHaveExecutableRedactionCoverage() throws Exception {
        Set<String> covered = Set.of("io.kestra.plugin.datadog.keys.KeyOutput#key");
        Set<Class<?>> outputs = new java.util.HashSet<>();
        for (Class<?> type : components()) {
            Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("run") && !m.isBridge()).forEach(m -> outputs.add(m.getReturnType()));
        }
        for (Class<?> output : outputs) {
            for (Field field : fields(output)) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.matches(".*(key|token|password|secret|webhook).*" ) && !name.endsWith("id") && !name.equals("keykind")) {
                    assertTrue(covered.contains(output.getName() + "#" + field.getName()), "Missing redaction test: " + output.getName() + "#" + field.getName());
                }
            }
        }
        JsonNode scenario = ScenarioTest.fixtures().path("keys.Create");
        Object safe = scenarioTask(scenario, Map.of()).run(runContext());
        assertFalse(JacksonMapper.ofJson().writeValueAsString(safe).contains("fixture-credential-value"));
        Object revealed = scenarioTask(scenario, Map.of("includeSecrets", true)).run(runContext());
        assertTrue(JacksonMapper.ofJson().writeValueAsString(revealed).contains("fixture-credential-value"));
    }

    @TestFactory
    Stream<DynamicTest> numericIdArraysPreserveSmallAndLargeIntegers() throws Exception {
        JsonNode scenarios = ScenarioTest.fixtures();
        return Stream.of("monitors.CanDelete", "slos.Create", "slos.Update").flatMap(name ->
            Stream.of(List.of(12345), List.of(12345, 9007199254740993L, Long.MAX_VALUE)).map(ids -> DynamicTest.dynamicTest(name + ids, () -> {
                server.requests.clear();
                Map<String, Object> overrides = new LinkedHashMap<>();
                overrides.put("monitorIds", ids);
                if (name.startsWith("slos.")) {
                    overrides.put("sloType", "MONITOR");
                    overrides.put("numerator", null);
                    overrides.put("denominator", null);
                }
                scenarioTask(scenarios.path(name), overrides).run(runContext());
                if (name.equals("monitors.CanDelete")) {
                    assertEquals("monitor_ids=" + ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                        java.net.URLDecoder.decode(server.only().query(), java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    JsonNode actual = JacksonMapper.ofJson().valueToTree(server.only().json()).path("monitor_ids");
                    assertEquals(ids.size(), actual.size());
                    for (int i = 0; i < ids.size(); i++) assertEquals(ids.get(i).longValue(), actual.get(i).longValue());
                }
            })));
    }

    @TestFactory
    Stream<DynamicTest> invalidNumericIdsFailBeforeRequests() throws Exception {
        JsonNode scenarios = ScenarioTest.fixtures();
        return Stream.of("monitors.CanDelete", "slos.Update").flatMap(name ->
            Stream.of(List.of(0), List.of(-1), List.of(1.5), List.of(new java.math.BigInteger("9223372036854775808")),
                java.util.Collections.singletonList(null)).map(ids -> DynamicTest.dynamicTest(name + ids, () -> {
                server.requests.clear();
                var task = scenarioTask(scenarios.path(name), Map.of("monitorIds", ids));
                assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
                assertTrue(server.requests.isEmpty());
            })));
    }
}
