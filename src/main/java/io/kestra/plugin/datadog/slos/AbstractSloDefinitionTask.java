package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.NumericIds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Request builder for CreateSLO and UpdateSLO, for metric and monitor SLOs. */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractSloDefinitionTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "SLO name")
    private Property<String> name;
    @NotNull
    @Schema(title = "SLO type", description = "METRIC maps to metric and MONITOR maps to monitor. Time-slice and count-specification definitions use the generic API task.")
    private Property<SloType> sloType;
    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    @Schema(title = "SLO thresholds", description = "At least one threshold. Use each of SEVEN_DAYS, THIRTY_DAYS and NINETY_DAYS at most once. Custom timeframes are not accepted by Datadog for writes.")
    private java.util.List<Threshold> thresholds;
    @Schema(title = "SLO description")
    private Property<String> sloDescription;
    @Schema(title = "Good-events metric query", description = "Required for METRIC. Sent as query.numerator.")
    private Property<String> numerator;
    @Schema(title = "Total-events metric query", description = "Required for METRIC. Sent as query.denominator.")
    private Property<String> denominator;
    @Schema(title = "Monitor identifiers", description = "Required for MONITOR. Positive integer ids. Not accepted for METRIC.")
    private Property<java.util.List<Long>> monitorIds;
    @Schema(title = "Monitor groups", description = "Up to 100 groups, only when exactly one monitorId is supplied.")
    private Property<java.util.List<String>> groups;
    @Schema(title = "SLO tags")
    private Property<java.util.List<String>> tags;

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in the SLO definition. False by default.")
    private Property<Boolean> includeSecrets;

    protected Map<String, Object> body(RunContext context) throws Exception {
        String name = context.render(this.name).as(String.class).orElse("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("`name` must not be blank");
        }
        SloType kind = context.render(sloType).as(SloType.class).orElseThrow(() -> new IllegalArgumentException("`sloType` is required"));
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException("`thresholds` must not be empty");
        }
        var renderedThresholds = new ArrayList<Map<String, Object>>();
        Set<Timeframe> timeframes = new HashSet<>();
        for (Threshold threshold : thresholds) {
            if (threshold == null) {
                throw new IllegalArgumentException("A threshold must not be null");
            }
            Timeframe timeframe = context.render(threshold.timeframe).as(Timeframe.class).orElseThrow(() -> new IllegalArgumentException("A threshold timeframe is required"));
            if (!timeframes.add(timeframe)) {
                throw new IllegalArgumentException("Threshold timeframes must be unique");
            }
            Double target = context.render(threshold.target).as(Double.class).orElse(null);
            if (target == null || !Double.isFinite(target) || target <= 0 || target > 100) {
                throw new IllegalArgumentException("Threshold target must be finite and greater than 0 through 100");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timeframe", timeframe.value);
            row.put("target", target);
            Double warning = context.render(threshold.warning).as(Double.class).orElse(null);
            if (warning != null) {
                if (!Double.isFinite(warning) || warning <= target || warning > 100) {
                    throw new IllegalArgumentException("Threshold warning must be greater than target and at most 100");
                }
                row.put("warning", warning);
            }
            renderedThresholds.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", kind.value);
        body.put("thresholds", renderedThresholds);
        String numerator = context.render(this.numerator).as(String.class).orElse(null);
        String denominator = context.render(this.denominator).as(String.class).orElse(null);
        var monitors = NumericIds.render(context, monitorIds, "monitorIds");
        var groups = context.render(this.groups).asList(String.class);
        if (kind == SloType.METRIC) {
            if (numerator == null || numerator.isBlank() || denominator == null || denominator.isBlank()) {
                throw new IllegalArgumentException("METRIC SLOs require non-blank numerator and denominator");
            }
            if (!monitors.isEmpty() || !groups.isEmpty()) {
                throw new IllegalArgumentException("METRIC SLOs cannot have monitorIds or groups");
            }
            body.put("query", Map.of("numerator", numerator, "denominator", denominator));
        } else {
            if (monitors.isEmpty() || monitors.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new IllegalArgumentException("MONITOR SLOs require positive monitorIds");
            }
            if (numerator != null || denominator != null) {
                throw new IllegalArgumentException("MONITOR SLOs cannot have numerator or denominator");
            }
            if (groups.size() > 100 || !groups.isEmpty() && monitors.size() != 1) {
                throw new IllegalArgumentException("groups require exactly one monitorId and at most 100 groups");
            }
            body.put("monitor_ids", monitors);
            if (this.groups != null) {
                body.put("groups", groups);
            }
        }
        context.render(sloDescription).as(String.class).ifPresent(value -> body.put("description", value));
        if (tags != null) {
            body.put("tags", context.render(tags).asList(String.class));
        }
        return body;
    }

    protected java.util.List<Map<String, Object>> result(RunContext context, Map<String, Object> response) throws Exception {
        Slos.noErrors(response);
        if (!(response.get("data") instanceof java.util.List<?> data)) {
            throw new IllegalStateException("Datadog did not return an SLO data array");
        }
        var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
        return data.stream().map(value -> io.kestra.plugin.datadog.monitors.MonitorIO.redactMap(Slos.object(value, "data[]"), rIncludeSecrets)).toList();
    }

    public enum SloType {
        METRIC("metric"), MONITOR("monitor");
        private final String value;
        SloType(String value) { this.value = value; }
    }

    public enum Timeframe {
        SEVEN_DAYS("7d"), THIRTY_DAYS("30d"), NINETY_DAYS("90d");
        private final String value;
        Timeframe(String value) { this.value = value; }
    }

    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class Threshold {
        @NotNull
        @Schema(title = "Threshold timeframe")
        private Property<Timeframe> timeframe;
        @NotNull
        @Schema(title = "Target percentage", description = "Greater than 0 and at most 100. Check requires targets below 100 to calculate an error budget.")
        private Property<Double> target;
        @Schema(title = "Warning percentage", description = "Greater than target and at most 100 when present.")
        private Property<Double> warning;
    }
}
