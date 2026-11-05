package io.kestra.plugin.datadog.users;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.OrganizationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UsersTest extends OrganizationTestSupport {
    @Test
    void listPagesAndGetRedactsUnlessOptedIn() throws Exception {
        var user = resource("u1", "users", Map.of("handle", "user@example.com", "disabled", false, "password", VALUE));
        server.reply("GET", "/api/v2/users", 200, page(List.of(user), 2));
        server.reply("GET", "/api/v2/users", 200, page(List.of(resource("u2", "users", Map.of("handle", "service", "service_account", true))), 2));
        var list = connection(io.kestra.plugin.datadog.users.List.builder()).filter(Property.ofValue("user")).pageSize(Property.ofValue(1)).build().run(runContext());
        assertEquals(2, list.getSize());
        noCredential(list);
        assertTrue(server.requests.get(0).query().contains("filter=user"));
        assertTrue(server.requests.get(1).query().contains("page%5Bnumber%5D=1"));
        server.reply("GET", "/api/v2/users/u1", 200, json(Map.of("data", user)));
        noCredential(connection(Get.builder()).userId(Property.ofValue("u1")).build().run(runContext()));
        assertTrue(json(connection(Get.builder()).userId(Property.ofValue("u1")).includeSecrets(Property.ofValue(true)).build().run(runContext())).contains(VALUE));
        server.reply("GET", "/api/v2/users", 200, page(List.of(user), 1));
        assertTrue(json(connection(io.kestra.plugin.datadog.users.List.builder()).includeSecrets(Property.ofValue(true)).build().run(runContext())).contains(VALUE));
    }

    @Test
    void singleUserDisableAndMissingUserHandling() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> connection(Get.builder()).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(Disable.builder()).userId(Property.ofValue(" ")).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
        server.reply("DELETE", "/api/v2/users/user-id", 204, "");
        assertTrue(connection(Disable.builder()).userId(Property.ofValue("user-id")).build().run(runContext()).getDisabled());
        assertEquals("/api/v2/users/user-id", server.only().path());
        server.reply("DELETE", "/api/v2/users/user-id", 404, "{}");
        assertFalse(connection(Disable.builder()).userId(Property.ofValue("user-id")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDisabled());
        server.reply("DELETE", "/api/v2/users/user-id", 403, "{}");
        assertThrows(DatadogClient.DatadogException.class, () -> connection(Disable.builder()).userId(Property.ofValue("user-id")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }
}
