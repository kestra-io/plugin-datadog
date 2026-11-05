package io.kestra.plugin.datadog.cost;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CostTest extends AbstractDatadogTest {
    private static final String COSTS = """
        {"data":[{"id":"cost-1","type":"cost_by_org","attributes":{"public_id":"org-1","org_name":"Production","date":"2026-01-01T00:00:00Z","region":"us","total_cost":123456789012345.6789,"charges":[{"charge_type":"on_demand","product_name":"infra_host","cost":0.1234567890123456789}]}}]}
        """;

    private Estimated.EstimatedBuilder<?, ?> estimated() {
        return Estimated.builder().id(IdUtils.create()).type(Estimated.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).startMonth(Property.ofValue("2026-01"));
    }

    private Historical.HistoricalBuilder<?, ?> historical() {
        return Historical.builder().id(IdUtils.create()).type(Historical.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).startMonth(Property.ofValue("2026-01"));
    }

    @Test
    void estimatedUsesMonthlyParametersAndPreservesDecimalText() throws Exception {
        server.reply("GET", "/api/v2/usage/estimated_cost", 200, COSTS);
        var output = estimated().endMonth(Property.ofValue("2026-02")).view(Property.ofValue(AbstractCostTask.View.SUB_ORG))
            .includeConnectedAccounts(Property.ofValue(true)).build().run(runContext());
        String query = java.net.URLDecoder.decode(server.only().query(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(query.contains("start_month=2026-01-01T00:00:00Z&"));
        assertTrue(query.contains("end_month=2026-02-01T00:00:00Z&"));
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests, "v2:GetEstimatedCostByOrg");
        assertTrue(server.only().query().contains("view=sub-org"));
        assertTrue(server.only().query().contains("include_connected_accounts=true"));
        assertEquals(API_KEY, server.only().header("DD-API-KEY"));
        assertEquals(APP_KEY, server.only().header("DD-APPLICATION-KEY"));
        assertEquals(1L, output.getCount());
        var row = output.getCosts().getFirst();
        assertEquals("org-1", row.getOrgId());
        assertEquals(new BigDecimal("123456789012345.6789"), row.getTotalCost());
        assertEquals(new BigDecimal("0.1234567890123456789"), row.getCharges().getFirst().get("cost"));
    }

    @Test
    void historicalCanWriteIonWithoutInlineCosts() throws Exception {
        server.reply("GET", "/api/v2/usage/historical_cost", 200, COSTS);
        var context = runContext();
        var output = historical().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertNull(output.getCosts());
        assertTrue(server.only().query().contains("view=summary"));
        assertTrue(server.only().query().contains("include_connected_accounts=false"));
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests, "v2:GetHistoricalCostByOrg");
        try (var input = context.storage().getFile(output.getUri())) {
            Map<?, ?> row = JacksonMapper.ofIon().readValue(input, Map.class);
            assertEquals("org-1", row.get("orgId"));
            assertNotNull(row.get("totalCost"));
        }
    }

    @Test
    void emptyCostsAreAnEmptyResult() throws Exception {
        server.reply("GET", "/api/v2/usage/historical_cost", 200, "{\"data\":[]}");
        assertEquals(0L, historical().build().run(runContext()).getCount());
    }

    @TestFactory
    Stream<DynamicTest> validatesMonthsBeforeAnyCall() {
        return Stream.<Map.Entry<String, org.junit.jupiter.api.function.Executable>>of(
            Map.entry("estimated missing month", () -> estimated().startMonth(null).build().run(runContext())),
            Map.entry("historical missing month", () -> historical().startMonth(null).build().run(runContext())),
            Map.entry("invalid month format", () -> estimated().startMonth(Property.ofValue("2026-01-01")).build().run(runContext())),
            Map.entry("invalid month number", () -> historical().startMonth(Property.ofValue("2026-13")).build().run(runContext())),
            Map.entry("invalid end format", () -> historical().endMonth(Property.ofValue("2026-1")).build().run(runContext())),
            Map.entry("reversed months", () -> estimated().endMonth(Property.ofValue("2025-12")).build().run(runContext()))
        ).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertThrows(IllegalArgumentException.class, entry.getValue());
            assertTrue(server.requests.isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> bothCostTasksPropagateHttpErrorClasses() {
        return Stream.of(400, 403, 404, 429, 503).flatMap(status -> Stream.of("estimated", "historical").map(task -> DynamicTest.dynamicTest(task + " HTTP " + status, () -> {
            server.reply("GET", "/api/v2/usage/" + task + "_cost", status, "{\"errors\":[\"failed\"]}");
            var error = assertThrows(DatadogClient.DatadogException.class, () -> {
                if (task.equals("estimated")) {
                    estimated().build().run(runContext());
                } else {
                    historical().build().run(runContext());
                }
            });
            assertEquals(status, error.getStatus());
        })));
    }
}
