package io.kestra.plugin.datadog.hosts;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.contract.OpenApiContract;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HostsTest extends AbstractDatadogTest {
    private Mute.MuteBuilder<?, ?> mute() {
        return Mute.builder().id(IdUtils.create()).type(Mute.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).hostName(Property.ofValue("worker-01"))
            .duration(Property.ofValue(Duration.ofMinutes(30)));
    }

    private Unmute.UnmuteBuilder<?, ?> unmute() {
        return Unmute.builder().id(IdUtils.create()).type(Unmute.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).hostName(Property.ofValue("worker-01"));
    }

    private List.ListBuilder<?, ?> list() {
        return List.builder().id(IdUtils.create()).type(List.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    private Totals.TotalsBuilder<?, ?> totals() {
        return Totals.builder().id(IdUtils.create()).type(Totals.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @Test
    void muteRequiresAFutureEndAndDoesNotOverrideByDefault() throws Exception {
        var stop = Instant.parse("2099-01-01T00:00:00Z");
        server.reply("POST", "/api/v1/host/worker-01/mute", 200, "{\"action\":\"Muted\",\"hostname\":\"worker-01\",\"end\":4070908800,\"message\":\"maintenance\"}");
        var output = mute().duration(null).end(Property.ofValue(stop)).message(Property.ofValue("maintenance")).build().run(runContext());
        assertEquals(stop.getEpochSecond(), ((Number) server.only().json().get("end")).longValue());
        assertEquals(false, server.only().json().get("override"));
        assertEquals("maintenance", server.only().json().get("message"));
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        assertTrue(output.getFound());
        assertEquals("Muted", output.getAction());
        assertEquals(stop.getEpochSecond(), output.getEnd());
        OpenApiContract.of("v1:MuteHost").assertRequest(server.only());
        OpenApiContract.of("v1:MuteHost").assertResponse(200,
            "{\"action\":\"Muted\",\"hostname\":\"worker-01\",\"end\":4070908800,\"message\":\"maintenance\"}");
    }

    @Test
    void durationUsesNowAndOverrideIsExplicit() throws Exception {
        var before = Instant.now().getEpochSecond();
        mute().override(Property.ofValue(true)).build().run(runContext());
        var end = ((Number) server.only().json().get("end")).longValue();
        assertTrue(end >= before + 1800 && end <= Instant.now().getEpochSecond() + 1800);
        assertEquals(true, server.only().json().get("override"));
    }

    @Test
    void unmuteSendsNoJsonBodyAndEncodesOneHostSegment() throws Exception {
        server.reply("POST", "/api/v1/host/worker west/unmute", 200, "{\"action\":\"Unmuted\",\"hostname\":\"worker west\"}");
        var output = unmute().hostName(Property.ofValue("worker west")).build().run(runContext());
        assertEquals("", server.only().body());
        assertEquals("Unmuted", output.getAction());
        assertTrue(output.getFound());
    }

    @Test
    void onlyExplicitIgnoreNotFoundSuppresses404() throws Exception {
        server.reply("POST", "/api/v1/host/worker-01/mute", 404, "{\"errors\":[\"missing\"]}");
        server.reply("POST", "/api/v1/host/worker-01/unmute", 404, "{\"errors\":[\"missing\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> mute().build().run(runContext()));
        assertThrows(DatadogClient.DatadogException.class, () -> unmute().build().run(runContext()));
        assertFalse(mute().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getFound());
        assertFalse(unmute().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getFound());
    }

    @Test
    void listsOffsetPagesWithFiltersAndTruncation() throws Exception {
        server.reply("GET", "/api/v1/hosts", 200, "{\"host_list\":[{\"host_name\":\"worker-01\",\"is_muted\":false}],\"total_matching\":3,\"total_returned\":1}");
        server.reply("GET", "/api/v1/hosts", 200, "{\"host_list\":[{\"host_name\":\"worker-02\",\"is_muted\":true}],\"total_matching\":3,\"total_returned\":1}");
        var output = list().pageSize(Property.ofValue(1)).maxResults(Property.ofValue(2)).filter(Property.ofValue("env:prod"))
            .sortField(Property.ofValue("host_name")).sortDirection(Property.ofValue(List.SortDirection.DESC))
            .includeHostsMetadata(Property.ofValue(true)).includeMutedHostsData(Property.ofValue(true))
            .from(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z"))).build().run(runContext());
        assertEquals(2L, output.getCount());
        assertEquals(3L, output.getTotalMatching());
        assertTrue(output.getTruncated());
        assertEquals("worker-02", output.getHosts().get(1).get("host_name"));
        assertTrue(server.requests.get(0).query().contains("start=0"));
        assertTrue(server.requests.get(1).query().contains("start=1"));
        var query = URLDecoder.decode(server.requests.getFirst().query(), StandardCharsets.UTF_8);
        assertTrue(query.contains("filter=env:prod"));
        assertTrue(query.contains("sort_dir=desc"));
        assertTrue(query.contains("sort_field=host_name"));
        assertTrue(query.contains("include_hosts_metadata=true"));
        assertTrue(query.contains("include_muted_hosts_data=true"));
        assertTrue(query.contains("from=1767225600"));
    }

    @Test
    void hostTotalsAreLongAndUseEpochSeconds() throws Exception {
        server.reply("GET", "/api/v1/hosts/totals", 200, "{\"total_active\":3000000000,\"total_up\":4000000000}");
        var output = totals().from(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z"))).build().run(runContext());
        assertEquals(3000000000L, output.getTotalActive());
        assertEquals(4000000000L, output.getTotalUp());
        assertEquals("from=1767225600", server.only().query());
    }

    @Test
    void inventoryCanBeStoredWithoutInlineHosts() throws Exception {
        server.reply("GET", "/api/v1/hosts", 200, "{\"host_list\":[{\"host_name\":\"worker-01\"}],\"total_matching\":1,\"total_returned\":1}");
        var context = runContext();
        var output = list().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertNull(output.getHosts());
        assertFalse(output.getTruncated());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("worker-01", JacksonMapper.ofIon().readValue(input, Map.class).get("host_name"));
        }
    }

    @Test
    void emptyInventoryStopsPaging() throws Exception {
        server.reply("GET", "/api/v1/hosts", 200, "{\"host_list\":[],\"total_matching\":0,\"total_returned\":0}");
        assertEquals(0L, list().build().run(runContext()).getCount());
        assertEquals(1, server.requests.size());
    }

    @TestFactory
    Stream<DynamicTest> malformedInventoryResponsesFail() {
        return Stream.of("{}", "{\"host_list\":[],\"total_matching\":1}", "{\"host_list\":[1]}",
            "{\"host_list\":[{},{}]}").map(body -> DynamicTest.dynamicTest(body, () -> {
                server.reply("GET", "/api/v1/hosts", 200, body);
                assertThrows(IllegalStateException.class, () -> list().pageSize(Property.ofValue(1)).build().run(runContext()));
            }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidPropertiesBeforeRequests() {
        return Stream.<Map.Entry<String, Executable>>of(
            Map.entry("mute without end", () -> mute().duration(null).build().run(runContext())),
            Map.entry("mute with both bounds", () -> mute().end(Property.ofValue(Instant.now().plusSeconds(300))).build().run(runContext())),
            Map.entry("zero duration", () -> mute().duration(Property.ofValue(Duration.ZERO)).build().run(runContext())),
            Map.entry("negative duration", () -> mute().duration(Property.ofValue(Duration.ofSeconds(-1))).build().run(runContext())),
            Map.entry("past end", () -> mute().duration(null).end(Property.ofValue(Instant.EPOCH)).build().run(runContext())),
            Map.entry("blank mute host", () -> mute().hostName(Property.ofValue(" ")).build().run(runContext())),
            Map.entry("missing unmute host", () -> unmute().hostName(null).build().run(runContext())),
            Map.entry("wildcard host", () -> unmute().hostName(Property.ofValue("worker-*")).build().run(runContext())),
            Map.entry("zero page size", () -> list().pageSize(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized page", () -> list().pageSize(Property.ofValue(1001)).build().run(runContext())),
            Map.entry("zero max", () -> list().maxResults(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized max", () -> list().maxResults(Property.ofValue(100001)).build().run(runContext()))
        ).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertThrows(IllegalArgumentException.class, entry.getValue());
            assertTrue(server.requests.isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyHostTaskPropagatesHttpErrorClasses() {
        return Stream.of(400, 403, 404, 429, 503).flatMap(status -> Stream.of("mute", "unmute", "list", "totals").map(task ->
            DynamicTest.dynamicTest(task + " HTTP " + status, () -> {
                var path = switch (task) {
                    case "mute", "unmute" -> "/api/v1/host/worker-01/" + task;
                    case "list" -> "/api/v1/hosts";
                    default -> "/api/v1/hosts/totals";
                };
                server.reply(task.equals("mute") || task.equals("unmute") ? "POST" : "GET", path, status, "{\"errors\":[\"failed\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> {
                    switch (task) {
                        case "mute" -> mute().build().run(runContext());
                        case "unmute" -> unmute().build().run(runContext());
                        case "list" -> list().build().run(runContext());
                        default -> totals().build().run(runContext());
                    }
                });
                assertEquals(status, error.getStatus());
            })));
    }
}
