package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.NumericIds;
import io.kestra.plugin.datadog.monitors.MonitorIO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request builder for CreateSLO and UpdateSLO, for metric and monitor SLOs. */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractSloDefinitionTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "SLO name")
    @PluginProperty(group = "main")
    private Property<String> name;
    @NotNull
    @Schema(title = "SLO type", description = "METRIC maps to metric and MONITOR maps to monitor. Time-slice and count-specification definitions use the generic API task.")
    @PluginProperty(group = "main")
    private Property<SloType> sloType;
    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    @Schema(title = "SLO thresholds", description = "At least one threshold. Use each of SEVEN_DAYS, THIRTY_DAYS and NINETY_DAYS at most once. Custom timeframes are not accepted by Datadog for writes.")
    private List<Threshold> thresholds;
    @Schema(title = "SLO description")
    @PluginProperty(group = "main")
    private Property<String> sloDescription;
    @Schema(title = "Good-events metric query", description = "Required for METRIC. Sent as query.numerator.")
    @PluginProperty(group = "main")
    private Property<String> numerator;
    @Schema(title = "Total-events metric query", description = "Required for METRIC. Sent as query.denominator.")
    @PluginProperty(group = "main")
    private Property<String> denominator;
    @Schema(title = "Monitor identifiers", description = "Required for MONITOR. Positive integer ids. Not accepted for METRIC.")
    @PluginProperty(group = "main")
    private Property<List<Long>> monitorIds;
    @Schema(title = "Monitor groups", description = "Up to 100 groups, only when exactly one monitorId is supplied.")
    @PluginProperty(group = "main")
    private Property<List<String>> groups;
    @Schema(title = "SLO tags")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Include sensitive values", description = "Return credential fields and URLs in the SLO definition. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    protected Map<String, Object> body(RunContext context) throws Exception {
        var rName = context.render(this.name).as(String.class).orElse("");
        if (rName.isBlank()) {
            throw new IllegalArgumentException("`name` must not be blank");
        }
        var rKind = context.render(sloType).as(SloType.class).orElseThrow(() -> new IllegalArgumentException("`sloType` is required"));
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException("`thresholds` must not be empty");
        }
        var rThresholds = new ArrayList<Map<String, Object>>();
        var timeframes = new HashSet<Timeframe>();
        for (var threshold : thresholds) {
            if (threshold == null) {
                throw new IllegalArgumentException("A threshold must not be null");
            }
            var rTimeframe = context.render(threshold.timeframe).as(Timeframe.class).orElseThrow(() -> new IllegalArgumentException("A threshold timeframe is required"));
            if (!timeframes.add(rTimeframe)) {
                throw new IllegalArgumentException("Threshold timeframes must be unique");
            }
            var rTarget = context.render(threshold.target).as(Double.class).orElse(null);
            if (rTarget == null || !Double.isFinite(rTarget) || rTarget <= 0 || rTarget > 100) {
                throw new IllegalArgumentException("Threshold target must be finite and greater than 0 through 100");
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("timeframe", rTimeframe.value);
            row.put("target", rTarget);
            var rWarning = context.render(threshold.warning).as(Double.class).orElse(null);
            if (rWarning != null) {
                if (!Double.isFinite(rWarning) || rWarning <= rTarget || rWarning > 100) {
                    throw new IllegalArgumentException("Threshold warning must be greater than target and at most 100");
                }
                row.put("warning", rWarning);
            }
            rThresholds.add(row);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("name", rName);
        body.put("type", rKind.value);
        body.put("thresholds", rThresholds);
        var rNumerator = context.render(this.numerator).as(String.class).orElse(null);
        var rDenominator = context.render(this.denominator).as(String.class).orElse(null);
        var rMonitors = NumericIds.render(context, monitorIds, "monitorIds");
        var rGroups = context.render(this.groups).asList(String.class);
        if (rKind == SloType.METRIC) {
            if (rNumerator == null || rNumerator.isBlank() || rDenominator == null || rDenominator.isBlank()) {
                throw new IllegalArgumentException("METRIC SLOs require non-blank numerator and denominator");
            }
            if (!rMonitors.isEmpty() || !rGroups.isEmpty()) {
                throw new IllegalArgumentException("METRIC SLOs cannot have monitorIds or groups");
            }
            body.put("query", Map.of("numerator", rNumerator, "denominator", rDenominator));
        } else {
            if (rMonitors.isEmpty() || rMonitors.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new IllegalArgumentException("MONITOR SLOs require positive monitorIds");
            }
            if (rNumerator != null || rDenominator != null) {
                throw new IllegalArgumentException("MONITOR SLOs cannot have numerator or denominator");
            }
            if (rGroups.size() > 100 || !rGroups.isEmpty() && rMonitors.size() != 1) {
                throw new IllegalArgumentException("groups require exactly one monitorId and at most 100 groups");
            }
            body.put("monitor_ids", rMonitors);
            if (this.groups != null) {
                body.put("groups", rGroups);
            }
        }
        context.render(sloDescription).as(String.class).ifPresent(value -> body.put("description", value));
        if (tags != null) {
            body.put("tags", context.render(tags).asList(String.class));
        }
        return body;
    }

    protected List<Map<String, Object>> result(RunContext context, Map<String, Object> response) throws Exception {
        Slos.noErrors(response);
        if (!(response.get("data") instanceof List<?> data)) {
            throw new IllegalStateException("Datadog did not return an SLO data array");
        }
        var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
        return data.stream().map(value -> MonitorIO.redactMap(Slos.object(value, "data[]"), rIncludeSecrets)).toList();
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
        @PluginProperty(group = "main")
        private Property<Timeframe> timeframe;
        @NotNull
        @Schema(title = "Target percentage", description = "Greater than 0 and at most 100. Check requires targets below 100 to calculate an error budget.")
        @PluginProperty(group = "main")
        private Property<Double> target;
        @Schema(title = "Warning percentage", description = "Greater than target and at most 100 when present.")
        @PluginProperty(group = "main")
        private Property<Double> warning;
    }
}
