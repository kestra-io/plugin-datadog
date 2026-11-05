package io.kestra.plugin.datadog.usage;

import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.metrics.Check.OnNoData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UsageTest extends AbstractDatadogTest {
    private static final String PATH = "/api/v2/usage/hourly_usage";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private Hourly.HourlyBuilder<?, ?> hourly() {
        return Hourly.builder().id(IdUtils.create()).type(Hourly.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).from(Property.ofValue(FROM))
            .to(Property.ofValue(FROM.plusSeconds(7200))).productFamilies(Property.ofValue(List.of(ProductFamily.INFRA_HOSTS)));
    }

    private Check.CheckBuilder<?, ?> check() {
        return Check.builder().id(IdUtils.create()).type(Check.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).from(Property.ofValue(FROM))
            .to(Property.ofValue(FROM.plusSeconds(7200))).productFamilies(Property.ofValue(List.of(ProductFamily.INFRA_HOSTS)))
            .usageType(Property.ofValue("infra_host_count")).budget(Property.ofValue(new BigDecimal("100")));
    }

    private static String row(String id, String value, String org) {
        return "{\"id\":\"" + id + "\",\"type\":\"usage_timeseries\",\"attributes\":{\"timestamp\":\"2026-01-01T00:00:00Z\",\"product_family\":\"infra_hosts\",\"public_id\":\"" + org + "\",\"measurements\":[{\"usage_type\":\"infra_host_count\",\"value\":" + value + "}]}}";
    }

    private void reply(String rows, String cursor) {
        server.reply("GET", PATH, 200, "{\"data\":[" + rows + "],\"meta\":{\"pagination\":{\"next_record_id\":" + (cursor == null ? "null" : "\"" + cursor + "\"") + "}}}");
    }

    @Test
    void hourlyFollowsOpaqueCursorAndKeepsLargeIntegerCounts() throws Exception {
        reply(row("one", "9007199254740993", "parent"), "next +/=&");
        reply(row("two", "null", "parent"), null);
        var output = hourly().pageSize(Property.ofValue(1)).build().run(runContext());
        assertEquals(2L, output.getCount());
        assertFalse(output.getTruncated());
        assertNull(output.getNextRecordId());
        Map<?, ?> attributes = (Map<?, ?>) output.getRows().getFirst().get("attributes");
        Object count = ((Map<?, ?>) ((List<?>) attributes.get("measurements")).getFirst()).get("value");
        assertEquals(9007199254740993L, count);
        String first = URLDecoder.decode(server.requests.getFirst().query(), StandardCharsets.UTF_8);
        String second = URLDecoder.decode(server.requests.get(1).query(), StandardCharsets.UTF_8);
        assertTrue(first.contains("filter[timestamp][start]=2026-01-01T00"));
        assertTrue(first.contains("filter[timestamp][end]=2026-01-01T02"));
        assertTrue(first.contains("filter[product_families]=infra_hosts"));
        assertTrue(first.contains("filter[include_descendants]=false"));
        assertTrue(second.contains("page[next_record_id]=next +/=&"));
        assertEquals(API_KEY, server.requests.getFirst().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.requests.getFirst().header("DD-APPLICATION-KEY"));
    }

    @Test
    void resultCapReturnsAResumeCursorAndStoreOmitsRows() throws Exception {
        reply(row("one", "20", "parent"), "next");
        var context = runContext();
        var output = hourly().maxResults(Property.ofValue(1)).store(Property.ofValue(true)).build().run(context);
        assertNull(output.getRows());
        assertTrue(output.getTruncated());
        assertEquals("next", output.getNextRecordId());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("one", JacksonMapper.ofIon().readValue(input, Map.class).get("id"));
        }
        reply(row("two", "30", "parent"), null);
        assertEquals(1L, hourly().nextRecordId(Property.ofValue(output.getNextRecordId())).build().run(runContext()).getCount());
    }

    @Test
    void repeatedCursorAndEmptyPagesWithCursorFail() {
        reply(row("one", "20", "parent"), "same");
        assertThrows(IllegalStateException.class, () -> hourly().pageSize(Property.ofValue(1)).build().run(runContext()));
        reply("", "next");
        assertThrows(IllegalStateException.class, () -> hourly().build().run(runContext()));
    }

    @Test
    void emptyTerminalPageReturnsZeroRows() throws Exception {
        reply("", null);
        assertEquals(0L, hourly().build().run(runContext()).getCount());
    }

    @Test
    void checkUsesPeakByDefaultAndCanSumWithoutRounding() throws Exception {
        reply(row("one", "90", "parent") + "," + row("two", "100", "parent"), null);
        var peak = check().build().run(runContext());
        assertEquals(new BigDecimal("100"), peak.getValue());
        assertFalse(peak.getBreached());
        assertFalse(peak.getNoData());
        assertEquals(2L, peak.getSamples());
        assertThrows(IllegalStateException.class, () -> check().aggregation(Property.ofValue(Check.Aggregation.SUM)).build().run(runContext()));
        var sum = check().aggregation(Property.ofValue(Check.Aggregation.SUM)).failOnBreach(Property.ofValue(false)).build().run(runContext());
        assertEquals(new BigDecimal("190"), sum.getValue());
        assertTrue(sum.getBreached());
        reply(row("one", "9007199254740993", "parent") + "," + row("two", "1", "parent"), null);
        assertEquals(new BigDecimal("9007199254740994"), check().aggregation(Property.ofValue(Check.Aggregation.SUM)).failOnBreach(Property.ofValue(false)).build().run(runContext()).getValue());
    }

    @Test
    void checkFollowsAllPagesAndCanSelectAnOrganization() throws Exception {
        reply(row("one", "500", "other"), "next");
        reply(row("two", "20", "wanted"), null);
        var output = check().orgId(Property.ofValue("wanted")).build().run(runContext());
        assertEquals(new BigDecimal("20"), output.getValue());
        assertEquals(2, server.requests.size());
    }

    @Test
    void partialDataCannotPassTheBudgetGate() {
        reply(row("one", "1", "parent"), "more");
        assertThrows(IllegalStateException.class, () -> check().maxResults(Property.ofValue(1)).failOnBreach(Property.ofValue(false))
            .onNoData(Property.ofValue(OnNoData.PASS)).build().run(runContext()));
    }

    @Test
    void nullOrMissingMeasurementNeverBecomesZero() throws Exception {
        reply(row("one", "10", "parent") + "," + row("two", "null", "parent"), null);
        assertThrows(IllegalStateException.class, () -> check().build().run(runContext()));
        var unknown = check().onNoData(Property.ofValue(OnNoData.NO_DATA)).build().run(runContext());
        assertTrue(unknown.getNoData());
        assertNull(unknown.getValue());
        assertNull(unknown.getBreached());
        var pass = check().onNoData(Property.ofValue(OnNoData.PASS)).build().run(runContext());
        assertFalse(pass.getBreached());
        reply(row("one", "10", "parent"), null);
        assertThrows(IllegalStateException.class, () -> check().usageType(Property.ofValue("unknown")).build().run(runContext()));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidPropertiesBeforeRequests() {
        return Stream.<Map.Entry<String, org.junit.jupiter.api.function.Executable>>of(
            Map.entry("missing start", () -> hourly().from(null).build().run(runContext())),
            Map.entry("partial hour start", () -> hourly().from(Property.ofValue(FROM.plusSeconds(1))).build().run(runContext())),
            Map.entry("partial hour end", () -> hourly().to(Property.ofValue(FROM.plusSeconds(1))).build().run(runContext())),
            Map.entry("reversed window", () -> hourly().to(Property.ofValue(FROM)).build().run(runContext())),
            Map.entry("missing families", () -> hourly().productFamilies(null).build().run(runContext())),
            Map.entry("empty families", () -> hourly().productFamilies(Property.ofValue(List.of())).build().run(runContext())),
            Map.entry("zero page", () -> hourly().pageSize(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized page", () -> hourly().pageSize(Property.ofValue(501)).build().run(runContext())),
            Map.entry("zero max", () -> hourly().maxResults(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized max", () -> hourly().maxResults(Property.ofValue(100001)).build().run(runContext())),
            Map.entry("missing measurement", () -> check().usageType(null).build().run(runContext())),
            Map.entry("blank measurement", () -> check().usageType(Property.ofValue(" ")).build().run(runContext())),
            Map.entry("missing budget", () -> check().budget(null).build().run(runContext())),
            Map.entry("negative budget", () -> check().budget(Property.ofValue(BigDecimal.ONE.negate())).build().run(runContext()))
        ).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertThrows(IllegalArgumentException.class, entry.getValue());
            assertTrue(server.requests.isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyUsageTaskPropagatesHttpErrors() {
        return Stream.of(400, 403, 404, 429, 503).flatMap(status -> Stream.of("hourly", "check").map(task -> DynamicTest.dynamicTest(task + " HTTP " + status, () -> {
            server.reply("GET", PATH, status, "{\"errors\":[\"failed\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> {
                if (task.equals("hourly")) {
                    hourly().build().run(runContext());
                } else {
                    check().build().run(runContext());
                }
            });
            assertEquals(status, error.getStatus());
        })));
    }
}
