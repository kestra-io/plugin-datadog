package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest extends AbstractDatadogTest {
    private Configuration.ConfigurationBuilder<?, ?> task() {
        return Configuration.builder().id(IdUtils.create()).type(Configuration.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).resource(Property.ofValue(Configuration.Resource.NOTIFICATION_RULES));
    }

    @Test
    void readsEachConfigurationResourceAsListAndById() throws Exception {
        var resources = Map.of(Configuration.Resource.NOTIFICATION_RULES, "notification_rule", Configuration.Resource.POLICIES, "policy", Configuration.Resource.TEMPLATES, "template");
        for (var resource : resources.entrySet()) {
            String type = switch (resource.getKey()) {
                case NOTIFICATION_RULES -> "monitor-notification-rule";
                case POLICIES -> "monitor-config-policy";
                case TEMPLATES -> "monitor-user-template";
            };
            var row = Map.of("id", "example", "type", type, "attributes", Map.of());
            String path = "/api/v2/monitor/" + resource.getValue();
            server.reply("GET", path, 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", java.util.List.of(row))));
            server.reply("GET", path + "/example", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", row)));
            assertEquals(java.util.List.of(row), task().resource(Property.ofValue(resource.getKey())).build().run(runContext()).getData());
            assertEquals(java.util.List.of(row), task().resource(Property.ofValue(resource.getKey())).resourceId(Property.ofValue("example")).build().run(runContext()).getData());
            if (resource.getKey() != Configuration.Resource.NOTIFICATION_RULES) {
                assertNull(server.requests.get(server.requests.size() - 2).query());
            }
            assertNull(server.requests.getLast().query());
        }
    }

    @Test
    void notificationRulesPageAndStore() throws Exception {
        var context = runContext();
        var row = Map.of("id", "rule", "type", "monitor-notification-rule", "attributes", Map.of("name", "Route alerts", "recipients", java.util.List.of("@team-platform")));
        server.reply("GET", "/api/v2/monitor/notification_rule", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", IntStream.range(0, 100).mapToObj(i -> row).toList())));
        server.reply("GET", "/api/v2/monitor/notification_rule", 200, "{\"data\":[]}");
        var output = task().store(Property.ofValue(true)).build().run(context);
        assertEquals(100, output.getSize());
        assertNull(output.getData());
        assertTrue(server.requests.getLast().query().contains("page=1"));
        try (var input = context.storage().getFile(output.getUri()); var iterator = JacksonMapper.ofIon().readerFor(Map.class).readValues(input)) {
            assertEquals(100, iterator.readAll().size());
        }
    }

    @Test
    void rejectsTruncationAndInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> task().resource(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().resourceId(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().resourceId(Property.ofValue("..")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().maxResults(Property.ofValue(0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().maxResults(Property.ofValue(10001)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
        server.reply("GET", "/api/v2/monitor/policy", 200, "{\"data\":[{\"id\":\"one\"},{\"id\":\"two\"}]}");
        assertThrows(IllegalStateException.class, () -> task().resource(Property.ofValue(Configuration.Resource.POLICIES)).maxResults(Property.ofValue(1)).build().run(runContext()));
    }

    @Test
    void propagatesErrorClassesForAllOperations() {
        var resources = Map.of(Configuration.Resource.NOTIFICATION_RULES, "notification_rule", Configuration.Resource.POLICIES, "policy", Configuration.Resource.TEMPLATES, "template");
        for (int status : new int[]{400, 403, 404, 429, 500}) {
            for (var resource : resources.entrySet()) {
                for (String id : java.util.Arrays.asList(null, "example")) {
                    String path = "/api/v2/monitor/" + resource.getValue() + (id == null ? "" : "/" + id);
                    server.reply("GET", path, status, "{\"errors\":[\"Request failed\"]}");
                    var error = assertThrows(DatadogClient.DatadogException.class, () -> task().resource(Property.ofValue(resource.getKey())).resourceId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
                    assertEquals(status, error.getStatus());
                }
            }
        }
    }
}
