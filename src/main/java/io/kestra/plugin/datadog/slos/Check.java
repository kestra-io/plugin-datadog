package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

/** Datadog operationId: GetSLOHistory. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Gate a flow on a Datadog SLO error budget", description = "Reads data.overall.sli_value over the requested window. Remaining budget is 100 * (SLI - target) / (100 - target), in percent. Fails when below minRemainingBudget unless failOnBreach is false. Equality passes. Missing SLI, query errors and preview data always fail. Needs slos_read.")
@Plugin(examples = @Example(title = "Check the error budget before deploying", full = true, code = """
    id: datadog_slo_gate
    namespace: company.observability
    tasks:
      - id: budget
        type: io.kestra.plugin.datadog.slos.Check
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
        from: "{{ now() | dateAdd(-7, 'DAYS') | date('X') }}"
        to: "{{ now() | date('X') }}"
        target: 99.9
        minRemainingBudget: 20
    """))
public class Check extends AbstractWindowTask implements RunnableTask<Check.Output> {
    @NotNull
    @Schema(title = "Target percentage", description = "The target to evaluate for this window, strictly between 0 and 100. Choose it explicitly rather than assuming a configured timeframe.")
    @PluginProperty(group = "main")
    private Property<Double> target;
    @Schema(title = "Minimum remaining budget", description = "Percent of the allowed error budget. Defaults to 20. Finite and at most 100; negative values permit overspend.")
    @PluginProperty(group = "main")
    private Property<Double> minRemainingBudget;
    @Schema(title = "Fail on a low budget", description = "True by default. When false return passed=false for a breach. Invalid data still fails.")
    @PluginProperty(group = "main")
    private Property<Boolean> failOnBreach;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = context.render(getSloId()).as(String.class).orElse(null);
            Slos.path(rId);
            var rFrom = context.render(getFrom()).as(Long.class).orElse(null);
            var rTo = context.render(getTo()).as(Long.class).orElse(null);
            Slos.window(rFrom, rTo);
            var rTarget = Slos.target(context.render(this.target).as(Double.class).orElse(null));
            var rMinimum = Slos.minimum(context.render(minRemainingBudget).as(Double.class).orElse(20.0));
            var rCorrections = context.render(getApplyCorrections()).as(Boolean.class).orElse(true);
            var rFail = context.render(failOnBreach).as(Boolean.class).orElse(true);
            var budget = Slos.budget(Slos.history(client, rId, rFrom, rTo, rTarget, rCorrections), rTarget);
            var passed = budget.remaining() >= rMinimum;
            if (!passed && rFail) {
                throw new IllegalStateException("SLO " + rId + " has " + budget.remaining() + "% error budget remaining, below " + rMinimum + "%");
            }
            return Output.builder().sloId(rId).from(rFrom).to(rTo).sli(budget.sli()).target(rTarget).remainingBudget(budget.remaining())
                .minRemainingBudget(rMinimum).passed(passed).correctionsApplied(rCorrections).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO identifier")
        private final String sloId;
        @Schema(title = "Window start in epoch seconds")
        private final Long from;
        @Schema(title = "Window end in epoch seconds")
        private final Long to;
        @Schema(title = "Overall SLI percentage", description = "data.overall.sli_value from GetSLOHistory.")
        private final Double sli;
        @Schema(title = "Evaluated target percentage")
        private final Double target;
        @Schema(title = "Remaining error budget percentage", description = "100 * (SLI - target) / (100 - target). Negative means overspent. This is not a count of events or minutes.")
        private final Double remainingBudget;
        @Schema(title = "Minimum allowed remaining budget percentage")
        private final Double minRemainingBudget;
        @Schema(title = "Budget gate passed")
        private final Boolean passed;
        @Schema(title = "Corrections were requested")
        private final Boolean correctionsApplied;
    }
}
