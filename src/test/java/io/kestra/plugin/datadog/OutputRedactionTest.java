package io.kestra.plugin.datadog;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.contract.OpenApiContract;
import io.kestra.plugin.datadog.logs.Search;
import io.kestra.plugin.datadog.monitors.List;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OutputRedactionTest extends AbstractDatadogTest {
    private static final String WEBHOOK = "https://hooks.example.test/fixture-only-credential";

    @Test
    void sensitiveOutputRequiresExplicitOptIn() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[{\"id\":1,\"message\":\"" + WEBHOOK + "\"}]");
        var monitor = List.builder()
            .id(IdUtils.create()).type(List.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertEquals(WEBHOOK, monitor.getMonitors().getFirst().get("message"));
        server.reply("POST", "/api/v2/logs/events/search", 200,
            "{\"data\":[{\"id\":\"log-1\",\"type\":\"log\",\"attributes\":{\"message\":\"" + WEBHOOK + "\"}}]}");
        var log = Search.builder()
            .id(IdUtils.create()).type(Search.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertEquals(WEBHOOK, log.getLogs().getFirst().get("message"));
    }

    @Test
    void monitorMessagesAreRedactedBeforeEveryFetchMode() throws Exception {
        var body = "[{\"id\":1,\"name\":\"orders\",\"type\":\"query alert\",\"query\":\"avg(last_5m):avg:system.cpu.user{*} > 90\",\"message\":\"Notify " + WEBHOOK + "\"}]";
        OpenApiContract.of("v1:ListMonitors").assertResponse(200, body);
        server.reply("GET", "/api/v1/monitor", 200, body);
        for (var mode : FetchType.values()) {
            var context = runContext();
            var output = List.builder()
                .id(IdUtils.create()).type(List.class.getName())
                .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
                .fetchType(Property.ofValue(mode)).build().run(context);
            assertEquals(1, output.getSize());
            var rows = new ArrayList<Object>();
            if (output.getMonitors() != null) rows.addAll(output.getMonitors());
            if (output.getRow() != null) rows.add(output.getRow());
            if (output.getUri() != null) {
                try (var input = context.storage().getFile(output.getUri())) {
                    FileSerde.read(input, rows::add);
                }
            }
            assertFalse(rows.toString().contains(WEBHOOK), mode.toString());
            if (mode != FetchType.NONE) assertEquals("Notify [REDACTED_URL]", ((Map<?, ?>) rows.getFirst()).get("message"));
        }
        OpenApiContract.assertRequests(server.requests, "v1:ListMonitors");
    }

    @Test
    void logAttributesAndMessagesAreRedactedBeforeEveryFetchMode() throws Exception {
        var body = "{\"data\":[{\"id\":\"log-1\",\"type\":\"log\",\"attributes\":{\"message\":\"Notify " + WEBHOOK + "\",\"attributes\":{\"api_key\":\"fixture-only-credential\"}}}]}";
        OpenApiContract.of("v2:ListLogs").assertResponse(200, body);
        server.reply("POST", "/api/v2/logs/events/search", 200, body);
        for (var mode : FetchType.values()) {
            var context = runContext();
            var output = Search.builder()
                .id(IdUtils.create()).type(Search.class.getName())
                .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
                .fetchType(Property.ofValue(mode)).build().run(context);
            assertEquals(1, output.getSize());
            var rows = new ArrayList<Object>();
            if (output.getLogs() != null) rows.addAll(output.getLogs());
            if (output.getRow() != null) rows.add(output.getRow());
            if (output.getUri() != null) {
                try (var input = context.storage().getFile(output.getUri())) {
                    FileSerde.read(input, rows::add);
                }
            }
            assertFalse(rows.toString().contains("fixture-only-credential"), mode.toString());
            if (mode != FetchType.NONE) assertEquals("Notify [REDACTED_URL]", ((Map<?, ?>) rows.getFirst()).get("message"));
        }
        OpenApiContract.assertRequests(server.requests, "v2:ListLogs");
    }
}
