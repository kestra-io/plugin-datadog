package io.kestra.plugin.datadog.metrics;

import java.util.Locale;

/** MetricsAggregator values used by QueryScalarData. */
public enum Aggregator {
    AVG, MIN, MAX, SUM, LAST, PERCENTILE, MEAN, L2NORM, AREA;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
