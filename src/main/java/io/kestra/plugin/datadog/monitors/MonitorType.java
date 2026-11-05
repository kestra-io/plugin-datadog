package io.kestra.plugin.datadog.monitors;

/** API values from the v1 MonitorType schema. */
public enum MonitorType {
    COMPOSITE("composite"), EVENT_ALERT("event alert"), LOG_ALERT("log alert"),
    METRIC_ALERT("metric alert"), PROCESS_ALERT("process alert"), QUERY_ALERT("query alert"),
    RUM_ALERT("rum alert"), SERVICE_CHECK("service check"), SYNTHETICS_ALERT("synthetics alert"),
    TRACE_ANALYTICS_ALERT("trace-analytics alert"), SLO_ALERT("slo alert"), EVENT_V2_ALERT("event-v2 alert"),
    AUDIT_ALERT("audit alert"), CI_PIPELINES_ALERT("ci-pipelines alert"), CI_TESTS_ALERT("ci-tests alert"),
    ERROR_TRACKING_ALERT("error-tracking alert"), DATABASE_MONITORING_ALERT("database-monitoring alert"),
    NETWORK_PERFORMANCE_ALERT("network-performance alert"), COST_ALERT("cost alert"),
    DATA_QUALITY_ALERT("data-quality alert"), NETWORK_PATH_ALERT("network-path alert"),
    DATA_JOBS_ALERT("data-jobs alert"), LLM_OBSERVABILITY_ALERT("llm-observability alert");

    private final String value;

    MonitorType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    static MonitorType fromValue(Object value) {
        for (MonitorType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("The monitor definition `type` must be a Datadog MonitorType API value");
    }
}
