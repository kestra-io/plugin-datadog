package io.kestra.plugin.datadog.oncall;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PagingTaskTest extends AbstractDatadogTest {
    @Test
    void mapsEverySupportedRegionToItsPagingHost() {
        Map<String, String> expected = Map.of(
            "datadoghq.com", "navy.oncall.datadoghq.com",
            "us3.datadoghq.com", "teal.oncall.datadoghq.com",
            "us5.datadoghq.com", "coral.oncall.datadoghq.com",
            "datadoghq.eu", "beige.oncall.datadoghq.eu",
            "ap1.datadoghq.com", "saffron.oncall.datadoghq.com",
            "ap2.datadoghq.com", "lava.oncall.datadoghq.com",
            "uk1.datadoghq.com", "scarlet.oncall.datadoghq.com");
        expected.forEach((site, host) -> assertEquals("https://" + host, PagingTask.baseForSite(site)));
        assertThrows(IllegalArgumentException.class, () -> PagingTask.baseForSite("ddog-gov.com"));
    }

    @Test
    void dryRunUsesRegionalHostAndValidatesTargetsBeforeCreatingAClient() throws Exception {
        var task = Page.builder().id(IdUtils.create()).type(Page.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .site(Property.ofValue("datadoghq.eu")).title(Property.ofValue("Orders"))
            .targetType(Property.ofValue(Page.TargetType.USER_ID)).target(Property.ofValue("user"))
            .urgency(Property.ofValue(Page.Urgency.LOW)).build();
        assertEquals("https://beige.oncall.datadoghq.eu/api/v2/on-call/pages", task.run(runContext()).getRequest().get("url"));
        assertTrue(server.requests.isEmpty());
        var invalid = Page.builder().id(IdUtils.create()).type(Page.class.getName())
            .title(Property.ofValue("Orders")).targetType(Property.ofValue(Page.TargetType.TEAM_ID))
            .target(Property.ofValue(" ")).urgency(Property.ofValue(Page.Urgency.HIGH)).build();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> invalid.run(runContext())).getMessage().contains("target"));
    }
}
