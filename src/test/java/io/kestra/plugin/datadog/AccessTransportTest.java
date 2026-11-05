package io.kestra.plugin.datadog;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.keys.AccessApi;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccessTransportTest extends AbstractDatadogTest {
    @Test
    void organizationCallsRetainTheTransientStatusWithoutSensitiveCauses() throws Exception {
        try (var client = DatadogClient.create(runContext(), Property.ofValue(API_KEY), Property.ofValue(APP_KEY), null, Property.ofValue(server.url()), true)) {
            server.stop();
            var error = assertThrows(DatadogClient.DatadogException.class, () -> AccessApi.request(client, "GET", "/api/v2/api_keys", Map.of(), null));
            assertEquals(-1, error.getStatus());
            assertTrue(error.isTransient());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(API_KEY));
            assertFalse(error.getMessage().contains(APP_KEY));
        }
    }
}
