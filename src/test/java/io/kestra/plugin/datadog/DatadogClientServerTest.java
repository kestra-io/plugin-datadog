package io.kestra.plugin.datadog;

import io.kestra.core.models.property.Property;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatadogClientServerTest extends AbstractDatadogTest {
    @Test
    void keepsTheTimeDatadogGivesInItsDateHeader() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        try (DatadogClient client = DatadogClient.create(runContext(), Property.ofValue("k"), Property.ofValue("a"), null, Property.ofValue(server.url()), true)) {
            assertTrue(client.serverTime().isEmpty(), "nothing before the first answer");
            client.get("/api/v1/monitor", Map.of());
            Instant time = client.serverTime().orElseThrow();
            assertTrue(Duration.between(time, Instant.now()).abs().getSeconds() < 5, "the mock runs on this clock: " + time);
        }
    }

    @Test
    void oneConnectionPoolServesAllTheRequestsOfAClient() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        try (DatadogClient client = DatadogClient.create(runContext(), Property.ofValue("k"), Property.ofValue("a"), null, Property.ofValue(server.url()), true)) {
            for (int i = 0; i < 3; i++) {
                client.get("/api/v1/monitor", Map.of("page", i));
            }
        }
        assertEquals(3, server.requests.size());
    }
}
