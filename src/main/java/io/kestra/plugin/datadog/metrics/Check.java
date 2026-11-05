package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/** Datadog operationId: QueryScalarData. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Check a scalar metric threshold", description = "Compares one scalar cell against a threshold. Needs timeseries_query. Fails on breach by default. Null or empty results fail unless onNoData is PASS or NO_DATA. Multiple numeric columns or rows fail rather than choosing an arbitrary value.")
@Plugin(examples = @Example(title = "Gate a deployment on CPU use", full = true, code = """
    id: datadog_cpu_gate
    namespace: company.observability
    tasks:
      - id: check
        type: io.kestra.plugin.datadog.metrics.Check
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        queries:
          a: "avg:system.cpu.user{env:prod}"
        threshold: 80
        comparison: GT
        lookback: PT5M
    """))
public class Check extends AbstractFormulaTask implements RunnableTask<Check.Output> {
    @NotNull
    @Schema(title = "Threshold", description = "Value to compare against, in the units returned by the query.")
    private Property<BigDecimal> threshold;

    @Schema(title = "Comparison", description = "Breach predicate: GT, GTE, LT, LTE, EQ or NE. Defaults to GT.")
    private Property<Comparison> comparison;

    @Schema(title = "Time aggregation", description = "Applied to each query. Defaults to AVG.")
    private Property<Aggregator> aggregator;

    @Schema(title = "Fail on breach", description = "Fail the task when the predicate is true. Defaults to true.")
    private Property<Boolean> failOnBreach;

    @Schema(title = "No-data policy", description = "FAIL fails. PASS returns breached=false. NO_DATA returns breached=null. Both non-failing modes return noData=true. Defaults to FAIL.")
    private Property<OnNoData> onNoData;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            BigDecimal limit = context.render(threshold).as(BigDecimal.class).orElseThrow(() -> new IllegalArgumentException("`threshold` is required"));
            Comparison predicate = context.render(comparison).as(Comparison.class).orElse(Comparison.GT);
            OnNoData policy = context.render(onNoData).as(OnNoData.class).orElse(OnNoData.FAIL);
            boolean fail = context.render(failOnBreach).as(Boolean.class).orElse(true);
            var body = requestBody(context, context.render(aggregator).as(Aggregator.class).orElse(Aggregator.AVG), null);
            BigDecimal value = FormulaQueries.scalar(FormulaQueries.attributes(client.post("/api/v2/query/scalar", body)));
            if (value == null) {
                if (policy == OnNoData.FAIL) {
                    throw new IllegalStateException("Datadog metric check returned no data");
                }
                return Output.builder().noData(true).breached(policy == OnNoData.PASS ? false : null).build();
            }
            boolean breached = predicate.breached(value, limit);
            if (breached && fail) {
                throw new IllegalStateException("Datadog metric threshold breached: " + value + " " + predicate + " " + limit);
            }
            return Output.builder().value(value).breached(breached).noData(false).build();
        }
    }

    public enum OnNoData { FAIL, PASS, NO_DATA }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Scalar value", description = "Null when no data was returned.")
        private final BigDecimal value;
        @Schema(title = "Threshold breached", description = "Null only for onNoData=NO_DATA with no data.")
        private final Boolean breached;
        @Schema(title = "No data")
        private final Boolean noData;
    }
}
