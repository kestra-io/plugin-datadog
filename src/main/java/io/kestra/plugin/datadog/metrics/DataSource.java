package io.kestra.plugin.datadog.metrics;

import java.util.Locale;

/** MetricsDataSource values for metric formula queries. */
public enum DataSource {
    METRICS, CLOUD_COST;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
