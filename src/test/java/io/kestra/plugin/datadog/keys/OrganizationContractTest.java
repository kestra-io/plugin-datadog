package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.catalog.Delete;
import io.kestra.plugin.datadog.catalog.UpsertServiceDefinition;
import io.kestra.plugin.datadog.security.SignalsSearch;
import io.kestra.plugin.datadog.security.TriageSignal;
import io.kestra.plugin.datadog.teams.RemoveMember;
import io.kestra.plugin.datadog.teams.SyncMembers;
import io.kestra.plugin.datadog.users.Disable;
import io.kestra.plugin.datadog.users.Get;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationContractTest extends OrganizationTestSupport {
    private record Case(String method, String path, RunnableTask<?> task) {
    }

    private List<Case> cases() {
        return List.of(
            new Case("POST", "/api/v2/api_keys", connection(Create.builder()).name(Property.ofValue("new")).build()),
            new Case("GET", "/api/v2/api_keys/id", connection(Rotate.builder()).oldKeyId(Property.ofValue("id")).name(Property.ofValue("new")).build()),
            new Case("DELETE", "/api/v2/api_keys/id", connection(Revoke.builder()).keyId(Property.ofValue("id")).build()),
            new Case("GET", "/api/v2/api_keys", connection(ListApiKeys.builder()).build()),
            new Case("GET", "/api/v2/application_keys", connection(ListApplicationKeys.builder()).build()),
            new Case("GET", "/api/v2/users", connection(SyncMembers.builder()).teamId(Property.ofValue("id")).handles(Property.ofValue(List.of())).build()),
            new Case("GET", "/api/v2/team/id/memberships", connection(RemoveMember.builder()).teamId(Property.ofValue("id")).userId(Property.ofValue("user")).build()),
            new Case("GET", "/api/v2/team", connection(io.kestra.plugin.datadog.teams.List.builder()).build()),
            new Case("GET", "/api/v2/users", connection(io.kestra.plugin.datadog.users.List.builder()).build()),
            new Case("GET", "/api/v2/users/id", connection(Get.builder()).userId(Property.ofValue("id")).build()),
            new Case("DELETE", "/api/v2/users/id", connection(Disable.builder()).userId(Property.ofValue("id")).build()),
            new Case("POST", "/api/v2/security_monitoring/signals/search", connection(SignalsSearch.builder()).from(Property.ofValue("2026-01-01T00:00:00Z")).to(Property.ofValue("2026-01-02T00:00:00Z")).build()),
            new Case("PATCH", "/api/v2/security_monitoring/signals/id/update", connection(TriageSignal.builder()).signalId(Property.ofValue("id")).state(Property.ofValue(TriageSignal.State.OPEN)).build()),
            new Case("POST", "/api/v2/services/definitions", connection(UpsertServiceDefinition.builder()).definition(Property.ofValue(Map.of("schema-version", "v2.2", "dd-service", "checkout", "team", "new"))).build()),
            new Case("DELETE", "/api/v2/services/definitions/checkout", connection(Delete.builder()).serviceName(Property.ofValue("checkout")).build())
        );
    }

    @TestFactory
    Stream<DynamicTest> everyTaskPropagatesErrorClassesWithoutResponseCredentials() {
        return cases().stream().flatMap(test -> Stream.of(400, 403, 404, 429, 500).map(status -> DynamicTest.dynamicTest(test.task().getClass().getName() + " HTTP " + status, () -> {
            server.reply("GET", "/api/v2/services/definitions/checkout", 200, json(Map.of("data", resource("checkout", "service-definition", Map.of("schema", Map.of("schema-version", "v2.2", "dd-service", "checkout", "team", "old"))))));
            server.reply(test.method(), test.path(), status, json(Map.of("errors", List.of(VALUE))));
            var error = assertThrows(DatadogClient.DatadogException.class, () -> test.task().run(runContext()));
            assertEquals(status, error.getStatus());
            assertFalse(error.toString().contains(VALUE));
            assertNull(error.getCause());
        })));
    }

    @Test
    void examplesParseAsTypedKestraFlows() throws Exception {
        for (var test : cases()) {
            Class<?> type = test.task().getClass();
            var plugin = type.getAnnotation(Plugin.class);
            assertNotNull(plugin, type.getName());
            assertTrue(plugin.examples().length > 0);
            for (var example : plugin.examples()) {
                assertTrue(example.full());
                var flow = JacksonMapper.ofYaml().readValue(String.join("\n", example.code()), Flow.class);
                assertFalse(flow.getTasks().isEmpty());
                assertEquals(type, flow.getTasks().getFirst().getClass());
                assertNotNull(flow.getId());
                assertNotNull(flow.getNamespace());
            }
        }
    }
}
