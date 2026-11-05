package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DowntimeManagementTest extends AbstractDatadogTest {
    private static final String ID = "00000000-0000-1234-0000-000000000000";
    private static final String PATH = "/api/v2/downtime/" + ID;
    private static final Map<String, Object> ROW = Map.of("id", ID, "type", "downtime", "attributes", Map.of("status", "active", "scope", "env:prod", "schedule", Map.of("start", "2026-01-01T00:00:00Z", "end", "2026-01-01T01:00:00Z")));

    private Get.GetBuilder<?, ?> get() {
        return Get.builder().id(IdUtils.create()).type(Get.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).downtimeId(Property.ofValue(ID));
    }

    private Update.UpdateBuilder<?, ?> update() {
        return Update.builder().id(IdUtils.create()).type(Update.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).downtimeId(Property.ofValue(ID)).message(Property.ofValue("Maintenance"));
    }

    private List.ListBuilder<?, ?> list() {
        return List.builder().id(IdUtils.create()).type(List.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @Test
    void getReturnsV2Response() throws Exception {
        server.reply("GET", PATH, 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", ROW)));
        var output = get().build().run(runContext());
        assertEquals(ID, output.getDowntimeId());
        assertEquals(ROW, output.getDowntime().get("data"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
    }

    @Test
    void listPagesWithOffsetAndStopsAtLimit() throws Exception {
        var rows = IntStream.range(0, 100).mapToObj(i -> ROW).toList();
        server.reply("GET", "/api/v2/downtime", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", rows)));
        server.reply("GET", "/api/v2/downtime", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", java.util.List.of(ROW))));
        var output = list().currentOnly(Property.ofValue(true)).build().run(runContext());
        assertEquals(101, output.getSize());
        assertEquals(2, server.requests.size());
        String first = URLDecoder.decode(server.requests.getFirst().query(), StandardCharsets.UTF_8);
        String second = URLDecoder.decode(server.requests.getLast().query(), StandardCharsets.UTF_8);
        assertTrue(first.contains("current_only=true"));
        assertTrue(first.contains("page[offset]=0"));
        assertTrue(second.contains("page[offset]=100"));
        server.reply("GET", "/api/v2/downtime", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", rows)));
        assertEquals(2, list().maxResults(Property.ofValue(2)).build().run(runContext()).getSize());
    }

    @Test
    void listStoresIon() throws Exception {
        var context = runContext();
        server.reply("GET", "/api/v2/downtime", 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", java.util.List.of(ROW))));
        var output = list().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertNull(output.getDowntimes());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals(ROW, JacksonMapper.ofIon().readValue(input, Map.class));
        }
    }

    @Test
    void updateSendsRequiredEnvelopeAndFiniteSchedule() throws Exception {
        server.reply("PATCH", PATH, 200, JacksonMapper.ofJson().writeValueAsString(Map.of("data", ROW)));
        var output = update().scope(Property.ofValue("env:prod")).displayTimezone(Property.ofValue("UTC"))
            .start(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z"))).end(Property.ofValue(Instant.parse("2026-01-01T01:00:00Z")))
            .muteFirstRecoveryNotification(Property.ofValue(true)).build().run(runContext());
        assertTrue(output.getUpdated());
        var data = (Map<?, ?>) server.only().json().get("data");
        assertEquals(ID, data.get("id"));
        assertEquals("downtime", data.get("type"));
        var attrs = (Map<?, ?>) data.get("attributes");
        assertEquals(Map.of("start", "2026-01-01T00:00:00Z", "end", "2026-01-01T01:00:00Z"), attrs.get("schedule"));
        assertEquals(true, attrs.get("mute_first_recovery_notification"));
        assertEquals("env:prod", attrs.get("scope"));
        assertEquals(ROW, output.getDowntime().get("data"));
    }

    @Test
    void updateIgnoresOnly404WhenRequested() throws Exception {
        server.reply("PATCH", PATH, 404, "{\"errors\":[\"Downtime not found\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> update().build().run(runContext()));
        assertFalse(update().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getUpdated());
        server.reply("PATCH", PATH, 403, "{\"errors\":[\"Forbidden\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> update().ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void validatesEveryLocalRuleBeforeRequests() {
        for (String id : java.util.Arrays.asList(null, "", " ", ".", "..")) {
            assertThrows(IllegalArgumentException.class, () -> get().downtimeId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> update().downtimeId(id == null ? null : Property.ofValue(id)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> update().message(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> update().scope(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> update().start(Property.ofValue(Instant.EPOCH)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> update().start(Property.ofValue(Instant.EPOCH)).end(Property.ofValue(Instant.EPOCH)).build().run(runContext()));
        assertThrows(java.time.DateTimeException.class, () -> update().displayTimezone(Property.ofValue("invalid")).build().run(runContext()));
        for (int limit : new int[]{0, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> list().maxResults(Property.ofValue(limit)).build().run(runContext()));
        }
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void encodesIdsAsOnePathSegment() throws Exception {
        server.reply("GET", "/api/v2/downtime/a/b?x", 200, "{\"data\":{\"id\":\"a/b?x\",\"type\":\"downtime\"}}");
        get().downtimeId(Property.ofValue("a/b?x")).build().run(runContext());
        assertNull(server.only().query());
    }

    @Test
    void allDowntimeTasksPropagateErrorClasses() {
        record Case(String method, String path, RunnableTask<?> task) { }
        for (int status : new int[]{400, 403, 404, 429, 500}) {
            var cases = java.util.List.of(new Case("GET", PATH, get().build()), new Case("GET", "/api/v2/downtime", list().build()), new Case("PATCH", PATH, update().build()),
                new Case("POST", "/api/v2/downtime", Create.builder().id(IdUtils.create()).type(Create.class.getName()).apiKey(Property.ofValue(API_KEY))
                    .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).scope(Property.ofValue("env:prod")).monitorId(Property.ofValue(123L)).duration(Property.ofValue(java.time.Duration.ofMinutes(30))).build()),
                new Case("DELETE", PATH, Cancel.builder().id(IdUtils.create()).type(Cancel.class.getName()).apiKey(Property.ofValue(API_KEY))
                    .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).downtimeId(Property.ofValue(ID)).build()));
            for (var c : cases) {
                server.reply(c.method(), c.path(), status, "{\"errors\":[\"Request failed\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> c.task().run(runContext()));
                assertEquals(status, error.getStatus());
            }
        }
    }

    @Test
    void rejectsUnexpectedResponses() {
        assertThrows(IllegalStateException.class, () -> get().build().run(runContext()));
        assertThrows(IllegalStateException.class, () -> update().build().run(runContext()));
        assertThrows(IllegalStateException.class, () -> list().build().run(runContext()));
    }
}
