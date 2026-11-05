package io.kestra.plugin.datadog.metrics;

import java.math.BigDecimal;

/** Decimal threshold comparison shared by metric and usage gates. */
public enum Comparison {
    GT, GTE, LT, LTE, EQ, NE;

    public boolean breached(BigDecimal value, BigDecimal threshold) {
        int order = value.compareTo(threshold);
        return switch (this) {
            case GT -> order > 0;
            case GTE -> order >= 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            case EQ -> order == 0;
            case NE -> order != 0;
        };
    }
}
