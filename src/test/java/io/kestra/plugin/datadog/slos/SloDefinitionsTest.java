package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.kestra.plugin.datadog.slos.AbstractSloDefinitionTask.*;
import static org.junit.jupiter.api.Assertions.*;

class SloDefinitionsTest extends AbstractDatadogTest {
    private Threshold threshold(Timeframe timeframe, Double target, Double warning) {
        return Threshold.builder().timeframe(timeframe == null ? null : Property.ofValue(timeframe))
            .target(target == null ? null : Property.ofValue(target)).warning(warning == null ? null : Property.ofValue(warning)).build();
    }

    private Create.CreateBuilder<?, ?> creator() {
        return Create.builder().id(IdUtils.create()).type(Create.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .name(Property.ofValue("Orders availability")).sloType(Property.ofValue(SloType.METRIC))
            .numerator(Property.ofValue("sum:orders.good{*}.as_count()")).denominator(Property.ofValue("sum:orders.total{*}.as_count()"))
            .thresholds(List.of(threshold(Timeframe.SEVEN_DAYS, 99.9, null)));
    }

    private Update.UpdateBuilder<?, ?> updater() {
        return Update.builder().id(IdUtils.create()).type(Update.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .sloId(Property.ofValue("slo-123")).name(Property.ofValue("Orders availability")).sloType(Property.ofValue(SloType.MONITOR))
            .monitorIds(Property.ofValue(List.of(123L))).thresholds(List.of(threshold(Timeframe.THIRTY_DAYS, 99.9, null)));
    }

    private Delete.DeleteBuilder<?, ?> deleter() {
        return Delete.builder().id(IdUtils.create()).type(Delete.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).sloId(Property.ofValue("slo-123"));
    }

    @Test
    void createsMetricSloWithMappedEnumsAndAllTimeframes() throws Exception {
        server.reply("POST", "/api/v1/slo", 200, "{\"data\":[" + SlosTest.SLO + "],\"errors\":[]}");
        var task = creator().thresholds(List.of(threshold(Timeframe.SEVEN_DAYS, 99.9, 99.95), threshold(Timeframe.THIRTY_DAYS, 99.95, null), threshold(Timeframe.NINETY_DAYS, 99.99, null)))
            .tags(Property.ofValue(List.of("env:prod"))).sloDescription(Property.ofValue("Orders reliability")).build();
        assertEquals("slo-123", task.run(runContext()).getSlos().getFirst().get("id"));
        var body = server.only().json();
        assertEquals("metric", body.get("type"));
        assertEquals("Orders reliability", body.get("description"));
        assertEquals(List.of("env:prod"), body.get("tags"));
        var thresholds = (List<?>) body.get("thresholds");
        assertEquals(List.of("7d", "30d", "90d"), thresholds.stream().map(row -> ((Map<?, ?>) row).get("timeframe")).toList());
        assertEquals(99.95, ((Map<?, ?>) thresholds.getFirst()).get("warning"));
        assertEquals("sum:orders.good{*}.as_count()", ((Map<?, ?>) body.get("query")).get("numerator"));
        assertFalse(body.containsKey("monitor_ids"));
    }

    @Test
    void updatesAnIdentifiedMonitorSloWithGroups() throws Exception {
        var monitor = "{\"id\":\"slo-123\",\"name\":\"Orders availability\",\"type\":\"monitor\",\"thresholds\":[{\"timeframe\":\"30d\",\"target\":99.9}],\"monitor_ids\":[123]}";
        server.reply("PUT", "/api/v1/slo/slo-123", 200, "{\"data\":[" + monitor + "],\"errors\":[]}");
        assertEquals("monitor", updater().groups(Property.ofValue(List.of("env:prod"))).build().run(runContext()).getSlos().getFirst().get("type"));
        assertEquals("monitor", server.only().json().get("type"));
        assertEquals(List.of(123), server.only().json().get("monitor_ids"));
        assertEquals(List.of("env:prod"), server.only().json().get("groups"));
        assertFalse(server.only().json().containsKey("query"));
    }

    @Test
    void validatesRequiredPropertiesAndThresholdRulesBeforeCalling() {
        assertThrows(IllegalArgumentException.class, () -> creator().name(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().name(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().sloType(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().thresholds(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().thresholds(List.of()).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().thresholds(Arrays.asList((Threshold) null)).build().run(runContext()));
        for (var invalid : List.of(
            threshold(null, 99.9, null), threshold(Timeframe.SEVEN_DAYS, null, null), threshold(Timeframe.SEVEN_DAYS, 0.0, null),
            threshold(Timeframe.SEVEN_DAYS, 101.0, null), threshold(Timeframe.SEVEN_DAYS, 99.9, 99.8),
            threshold(Timeframe.SEVEN_DAYS, 99.9, 99.9), threshold(Timeframe.SEVEN_DAYS, 99.9, 101.0)
        )) {
            assertThrows(IllegalArgumentException.class, () -> creator().thresholds(List.of(invalid)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> creator().thresholds(List.of(threshold(Timeframe.SEVEN_DAYS, 99.9, null), threshold(Timeframe.SEVEN_DAYS, 99.0, null))).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void validatesMetricAndMonitorConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> creator().numerator(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().denominator(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().denominator(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().monitorIds(Property.ofValue(List.of(1L))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> creator().groups(Property.ofValue(List.of("env:prod"))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().monitorIds(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().monitorIds(Property.ofValue(List.of(0L))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().numerator(Property.ofValue("query")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().denominator(Property.ofValue("query")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().monitorIds(Property.ofValue(List.of(1L, 2L))).groups(Property.ofValue(List.of("env:prod"))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().groups(Property.ofValue(Collections.nCopies(101, "group"))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> updater().sloId(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> deleter().sloId(Property.ofValue("a/b")).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void deletesOnlyOneSloAndHandlesMissingWithoutForce() throws Exception {
        server.reply("DELETE", "/api/v1/slo/slo-123", 200, "{\"data\":[\"slo-123\"],\"errors\":{}}")
            .reply("DELETE", "/api/v1/slo/slo-123", 404, "{\"errors\":[\"not found\"]}");
        assertTrue(deleter().build().run(runContext()).getDeleted());
        assertNull(server.only().query());
        assertFalse(deleter().ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getDeleted());
        assertThrows(DatadogClient.DatadogException.class, () -> deleter().build().run(runContext()));
    }

    @Test
    void propagatesAllErrorClassesOnWritesEvenWhenMissingIsIgnored() {
        for (var status : new int[]{400, 403, 404, 429, 503}) {
            server.reply("POST", "/api/v1/slo", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> creator().build().run(runContext())).getStatus());
            server.reply("PUT", "/api/v1/slo/slo-123", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> updater().build().run(runContext())).getStatus());
            server.reply("DELETE", "/api/v1/slo/slo-123", status, "{\"errors\":[\"failed\"]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> deleter().build().run(runContext())).getStatus());
        }
    }

    @Test
    void doesNotClaimSuccessOnDeleteErrorsOrAnUnconfirmedId() {
        for (var body : List.of("{}", "{\"data\":[]}", "{\"data\":[\"slo-123\"],\"errors\":{\"slo-123\":\"referenced\"}}")) {
            server.reply("DELETE", "/api/v1/slo/slo-123", 200, body);
            assertThrows(IllegalStateException.class, () -> deleter().build().run(runContext()));
        }
        server.reply("POST", "/api/v1/slo", 200, "{\"data\":[],\"errors\":[\"failed\"]}");
        assertThrows(IllegalStateException.class, () -> creator().build().run(runContext()));
        server.reply("PUT", "/api/v1/slo/slo-123", 200, "{}");
        assertThrows(IllegalStateException.class, () -> updater().build().run(runContext()));
    }
}
