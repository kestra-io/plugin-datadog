package io.kestra.plugin.datadog.usage;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.metrics.Check.OnNoData;
import io.kestra.plugin.datadog.metrics.Comparison;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Datadog operationId: GetHourlyUsage. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Check product usage against a budget", description = "Reads hourly usage with usage_read permission and compares one named measurement with a budget. MAX checks peak usage by default. SUM adds hours and organizations. Truncated results always fail. Missing or null measurements follow onNoData, which defaults to FAIL.")
@Plugin(examples = @Example(title = "Gate on peak infrastructure usage", full = true, code = """
    id: datadog_usage_gate
    namespace: company.finops
    tasks:
      - id: budget
        type: io.kestra.plugin.datadog.usage.Check
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        from: "2026-01-01T00:00:00Z"
        to: "2026-01-02T00:00:00Z"
        productFamilies: [INFRA_HOSTS]
        usageType: infra_host_count
        budget: 100
        aggregation: MAX
    """))
public class Check extends AbstractHourlyTask implements RunnableTask<Check.Output> {
    @NotNull
    @Schema(title = "Usage measurement", description = "Exact usage_type name from the hourly API measurements array.")
    @PluginProperty(group = "main")
    private Property<String> usageType;
    @NotNull
    @Schema(title = "Budget", description = "Non-negative limit in the selected measurement's units.")
    @PluginProperty(group = "main")
    private Property<BigDecimal> budget;
    @Schema(title = "Aggregation", description = "MAX takes the largest hourly measurement. SUM adds all selected measurements. Defaults to MAX.")
    @PluginProperty(group = "main")
    private Property<Aggregation> aggregation;
    @Schema(title = "Organization public ID", description = "Optional exact attributes.public_id filter applied after paging.")
    @PluginProperty(group = "main")
    private Property<String> orgId;
    @Schema(title = "Comparison", description = "Breach predicate. Defaults to GT.")
    @PluginProperty(group = "main")
    private Property<Comparison> comparison;
    @Schema(title = "Fail on breach", description = "Defaults to true. False returns the decision without failing.")
    @PluginProperty(group = "main")
    private Property<Boolean> failOnBreach;
    @Schema(title = "No-data policy", description = "FAIL fails. PASS returns breached=false. NO_DATA returns breached=null. Defaults to FAIL. Any null selected measurement makes the result unknown.")
    @PluginProperty(group = "main")
    private Property<OnNoData> onNoData;

    @Override
    public Output run(RunContext context) throws Exception {
        var rMeasurement = context.render(usageType).as(String.class).orElse("").trim();
        var rLimit = context.render(budget).as(BigDecimal.class).orElse(null);
        if (rMeasurement.isEmpty() || rLimit == null || rLimit.signum() < 0) {
            throw new IllegalArgumentException("`usageType` is required and `budget` must be non-negative");
        }
        var rMode = context.render(aggregation).as(Aggregation.class).orElse(Aggregation.MAX);
        var rPredicate = context.render(comparison).as(Comparison.class).orElse(Comparison.GT);
        var rPolicy = context.render(onNoData).as(OnNoData.class).orElse(OnNoData.FAIL);
        var rFail = context.render(failOnBreach).as(Boolean.class).orElse(true);
        var rOrganization = context.render(orgId).as(String.class).filter(s -> !s.isBlank()).orElse(null);
        var result = collect(context, null, FetchType.FETCH);
        if (result.nextRecordId() != null) {
            throw new IllegalStateException("Usage check is incomplete: increase maxResults or narrow the window");
        }
        BigDecimal value = null;
        var samples = 0L;
        var missing = false;
        for (var row : result.rows()) {
            if (!(row.get("attributes") instanceof Map<?, ?> attributes)) {
                throw new IllegalStateException("Datadog hourly usage row has no attributes");
            }
            if (rOrganization != null && !rOrganization.equals(attributes.get("public_id"))) {
                continue;
            }
            if (!(attributes.get("measurements") instanceof List<?> measurements)) {
                missing = true;
                continue;
            }
            var found = false;
            for (var item : measurements) {
                if (!(item instanceof Map<?, ?> cell)) {
                    throw new IllegalStateException("Datadog usage measurement must be an object");
                }
                if (!rMeasurement.equals(cell.get("usage_type"))) {
                    continue;
                }
                found = true;
                if (cell.get("value") == null) {
                    missing = true;
                    continue;
                }
                if (!(cell.get("value") instanceof Number number)) {
                    throw new IllegalStateException("Datadog usage value must be an int64 or null");
                }
                var count = new BigDecimal(number.toString());
                count.longValueExact();
                value = value == null ? count : rMode == Aggregation.SUM ? value.add(count) : value.max(count);
                samples++;
            }
            if (!found) {
                missing = true;
            }
        }
        if (missing || value == null) {
            if (rPolicy == OnNoData.FAIL) {
                throw new IllegalStateException("Datadog usage check has missing or null measurements");
            }
            return Output.builder().noData(true).breached(rPolicy == OnNoData.PASS ? false : null).samples(samples).build();
        }
        var breached = rPredicate.breached(value, rLimit);
        if (rFail && breached) {
            throw new IllegalStateException("Datadog usage budget breached: " + value + " " + rPredicate + " " + rLimit);
        }
        return Output.builder().value(value).breached(breached).noData(false).samples(samples).build();
    }

    public enum Aggregation { MAX, SUM }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Aggregated usage", description = "Exact decimal integer. Null for no-data outcomes.")
        private final BigDecimal value;
        @Schema(title = "Budget breached")
        private final Boolean breached;
        @Schema(title = "Missing or null data")
        private final Boolean noData;
        @Schema(title = "Non-null measurements read")
        private final Long samples;
    }
}
