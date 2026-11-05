package io.kestra.plugin.datadog.oncall;

import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.incidents.ResponseSupport;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Operation-specific servers of the Datadog On-Call Paging API. */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class PagingTask extends ResponseSupport {
    protected String pagingBase(RunContext context, DatadogClient client) throws Exception {
        if (context.render(getApiUrl()).as(String.class).filter(v -> !v.isBlank()).isPresent()) {
            return client.apiBase();
        }
        String site = context.render(getSite()).as(String.class).orElse(DatadogClient.DEFAULT_SITE).trim();
        return baseForSite(site.isEmpty() ? DatadogClient.DEFAULT_SITE : site);
    }

    static String baseForSite(String site) {
        return switch (site) {
            case "datadoghq.com" -> "https://navy.oncall.datadoghq.com";
            case "us3.datadoghq.com" -> "https://teal.oncall.datadoghq.com";
            case "us5.datadoghq.com" -> "https://coral.oncall.datadoghq.com";
            case "datadoghq.eu" -> "https://beige.oncall.datadoghq.eu";
            case "ap1.datadoghq.com" -> "https://saffron.oncall.datadoghq.com";
            case "ap2.datadoghq.com" -> "https://lava.oncall.datadoghq.com";
            case "uk1.datadoghq.com" -> "https://scarlet.oncall.datadoghq.com";
            default -> throw new IllegalArgumentException("No regional On-Call paging host is configured for this site; set apiUrl explicitly");
        };
    }
}
