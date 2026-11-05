package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Datadog operationId: GetSLOHistory. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Start a flow when an SLO budget becomes low", description = "Polls overall SLI over a rolling window and fires on a transition below minRemainingBudget. Uses the Check budget formula and needs slos_read. The first successful poll records the current state only. A recovery rearms the trigger. HTTP 429 and 5xx preserve the cursor and skip a poll.")
@Plugin(examples = @Example(title = "React to a low weekly error budget", full = true, code = """
    id: datadog_budget_low
    namespace: company.observability
    triggers:
      - id: low_budget
        type: io.kestra.plugin.datadog.slos.BudgetLow
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
        target: 99.9
        window: P7D
        minRemainingBudget: 20
        interval: PT5M
    tasks:
      - id: log
        type: io.kestra.plugin.core.log.Log
        message: "SLO budget remaining: {{ trigger.remainingBudget }}%"
    """))
public class BudgetLow extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<BudgetLow.Output> {
    @Builder.Default
    @Schema(title = "Polling interval", description = "Positive duration, default five minutes.")
    private final Duration interval = Duration.ofMinutes(5);
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog API key")
    private Property<String> apiKey;
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog application key", description = "Needs slos_read.")
    private Property<String> appKey;
    @Schema(title = "Datadog site", description = "Defaults to datadoghq.com.")
    private Property<String> site;
    @Schema(title = "API base URL", description = "Overrides the regional base URL for a proxy or test server.")
    private Property<String> apiUrl;
    @NotNull
    @Schema(title = "SLO identifier")
    private Property<String> sloId;
    @NotNull
    @Schema(title = "Target percentage", description = "Strictly between 0 and 100. Must match the target you intend to evaluate over window.")
    private Property<Double> target;
    @Schema(title = "Rolling window", description = "Positive whole-second duration, default P7D. Datadog enforces retention limits.")
    private Property<Duration> window;
    @Schema(title = "Minimum remaining budget", description = "Finite percentage at most 100, default 20. Equality is healthy.")
    private Property<Double> minRemainingBudget;
    @Schema(title = "Apply status corrections", description = "True by default.")
    private Property<Boolean> applyCorrections;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        String id = runContext.render(sloId).as(String.class).orElse(null);
        Slos.path(id);
        double target = Slos.target(runContext.render(this.target).as(Double.class).orElse(null));
        double minimum = Slos.minimum(runContext.render(minRemainingBudget).as(Double.class).orElse(20.0));
        Duration window = runContext.render(this.window).as(Duration.class).orElse(Duration.ofDays(7));
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("`interval` must be positive");
        }
        if (window.isNegative() || window.isZero() || window.getNano() != 0) {
            throw new IllegalArgumentException("`window` must be a positive whole-second duration");
        }
        boolean corrections = runContext.render(applyCorrections).as(Boolean.class).orElse(true);
        long to = Instant.now().getEpochSecond();
        long from = Math.subtractExact(to, window.getSeconds());
        Slos.window(from, to);
        var client = DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, true);
        Slos.Budget budget;
        try {
            budget = Slos.budget(Slos.history(client, id, from, to, target, corrections), target);
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() == 429 || e.getStatus() >= 500) {
                runContext.logger().warn("Datadog SLO poll skipped after HTTP {}", e.getStatus());
                return Optional.empty();
            }
            throw e;
        }
        var kv = runContext.namespaceKv(context.getNamespace());
        String key = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
        String configuration = client.apiBase() + ":" + id + ":" + target + ":" + minimum + ":" + window + ":" + corrections;
        var previous = kv.getValue(key);
        Map<String, Object> cursor = null;
        if (previous.isPresent()) {
            Object stored = previous.get().value();
            cursor = switch (stored) {
                case byte[] bytes -> Slos.object(JacksonMapper.ofJson().readValue(bytes, Object.class), "cursor");
                case String text -> Slos.object(JacksonMapper.ofJson().readValue(text, Object.class), "cursor");
                case Map<?, ?> map -> Slos.object(map, "cursor");
                default -> throw new IllegalStateException("Unexpected SLO budget cursor type");
            };
            if (!(cursor.get("below") instanceof Boolean) || !(cursor.get("configuration") instanceof String)) {
                throw new IllegalStateException("Invalid SLO budget cursor");
            }
        }
        boolean below = budget.remaining() < minimum;
        Execution execution = null;
        if (cursor != null && configuration.equals(cursor.get("configuration")) && Boolean.FALSE.equals(cursor.get("below")) && below) {
            var output = Output.builder().sloId(id).sli(budget.sli()).remainingBudget(budget.remaining()).target(target)
                .minRemainingBudget(minimum).from(from).to(to).build();
            // At least once: if execution creation fails, the previous healthy cursor remains available for a retry.
            execution = TriggerService.generateExecution(this, conditionContext, context, output);
        }
        byte[] state = JacksonMapper.ofJson().writeValueAsBytes(Map.of("configuration", configuration, "below", below, "sampledAt", to));
        kv.put(key, new KVValueAndMetadata(new KVMetadata("Datadog SLO budget transition cursor", (Duration) null), state));
        return Optional.ofNullable(execution);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "SLO identifier")
        private final String sloId;
        @Schema(title = "Overall SLI percentage")
        private final Double sli;
        @Schema(title = "Remaining error budget percentage")
        private final Double remainingBudget;
        @Schema(title = "Target percentage")
        private final Double target;
        @Schema(title = "Budget threshold percentage")
        private final Double minRemainingBudget;
        @Schema(title = "Sampled window start in epoch seconds")
        private final Long from;
        @Schema(title = "Sampled window end in epoch seconds")
        private final Long to;
    }
}
