package io.kestra.plugin.datadog.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
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
import io.kestra.core.storages.kv.KVStore;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Datadog operationId: QueryScalarData. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Trigger on a metric threshold crossing", description = "Polls one scalar metric query with timeseries_query permission. Records the first value without starting a flow. Fires when the breach predicate changes from false to true and re-arms after recovery. Null data, HTTP 429 and 5xx preserve the cursor. The cursor moves after execution creation for at-least-once delivery. Changes to the query, threshold or window establish a new baseline.")
@Plugin(examples = @Example(title = "React when CPU use crosses a limit", full = true, code = """
    id: datadog_cpu_crossing
    namespace: company.observability
    triggers:
      - id: threshold
        type: io.kestra.plugin.datadog.metrics.Threshold
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        query: "avg:system.cpu.user{env:prod}"
        threshold: 80
        comparison: GT
        lookback: PT5M
        interval: PT1M
    tasks:
      - id: log
        type: io.kestra.plugin.core.log.Log
        message: "CPU threshold crossed: {{ trigger.value }}"
    """))
public class Threshold extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Threshold.Output> {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Builder.Default
    @Schema(title = "Poll interval", description = "Positive duration between polls. Defaults to PT5M.")
    @PluginProperty(group = "reliability")
    private final Duration interval = Duration.ofMinutes(5);

    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog API key", description = "Organization API key from a Kestra secret.")
    private Property<String> apiKey;

    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    @Schema(title = "Datadog application key", description = "Application key with timeseries_query permission.")
    private Property<String> appKey;

    @Schema(title = "Datadog site", description = "Organization site. Defaults to datadoghq.com.")
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(title = "API base URL", description = "Optional proxy or test server URL.")
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    @NotNull
    @Schema(title = "Metric query", description = "A metric query returning one scalar value without group-by.")
    @PluginProperty(group = "main")
    private Property<String> query;

    @NotNull
    @Schema(title = "Threshold", description = "Limit in the units returned by the query.")
    @PluginProperty(group = "main")
    private Property<BigDecimal> threshold;

    @Schema(title = "Comparison", description = "Breach predicate. Defaults to GT.")
    @PluginProperty(group = "main")
    private Property<Comparison> comparison;

    @Schema(title = "Time aggregation", description = "Aggregation over the polling window. Defaults to AVG.")
    @PluginProperty(group = "processing")
    private Property<Aggregator> aggregator;

    @Schema(title = "Lookback", description = "Positive duration ending at each poll. Defaults to PT5M.")
    @PluginProperty(group = "processing")
    private Property<Duration> lookback;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rExpression = runContext.render(query).as(String.class).orElse("");
        var rLimit = runContext.render(threshold).as(BigDecimal.class)
            .orElseThrow(() -> new IllegalArgumentException("`threshold` is required"));
        var rPredicate = runContext.render(comparison).as(Comparison.class).orElse(Comparison.GT);
        var rAggregation = runContext.render(aggregator).as(Aggregator.class).orElse(Aggregator.AVG);
        var rWindow = runContext.render(lookback).as(Duration.class).orElse(Duration.ofMinutes(5));
        if (rWindow.isZero() || rWindow.isNegative() || interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("`lookback` and `interval` must be positive");
        }
        var now = Instant.now();
        var body = FormulaQueries.body(Map.of("a", rExpression), List.of(), now.minus(rWindow), now, DataSource.METRICS, rAggregation, null);
        try (var client = DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, true)) {
            var signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                JacksonMapper.ofJson().writeValueAsBytes(List.of(rExpression, rLimit.stripTrailingZeros().toPlainString(), rPredicate, rAggregation, rWindow.toString(), client.apiBase()))));
            var kv = runContext.namespaceKv(context.getNamespace());
            var key = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());
            var previous = readState(kv, key, signature);

            BigDecimal value;
            try {
                value = FormulaQueries.scalar(FormulaQueries.attributes(client.post("/api/v2/query/scalar", body)));
            } catch (DatadogClient.DatadogException e) {
                if (e.isTransient()) {
                    runContext.logger().warn("Datadog threshold poll skipped after HTTP {}", e.getStatus());
                    return Optional.empty();
                }
                throw e;
            }
            if (value == null) {
                return Optional.empty();
            }
            var sampledAt = client.serverTime().orElse(null);
            if (sampledAt == null) {
                runContext.logger().warn("Datadog threshold poll skipped because the Date header is missing");
                return Optional.empty();
            }
            var breached = rPredicate.breached(value, rLimit);
            if (previous.isEmpty() || !breached || previous.get()) {
                writeState(kv, key, signature, breached, sampledAt);
                return Optional.empty();
            }
            var output = Output.builder().value(value).threshold(rLimit).breached(true).observedAt(sampledAt).build();
            var execution = TriggerService.generateExecution(this, conditionContext, context, output);
            writeState(kv, key, signature, true, sampledAt);
            return Optional.of(execution);
        }
    }

    private static Optional<Boolean> readState(KVStore kv, String key, String signature) throws Exception {
        var stored = kv.getValue(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        var state = switch (stored.get().value()) {
            case byte[] bytes -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case String text -> JacksonMapper.ofJson().readValue(text, MAP_TYPE);
            case Map<?, ?> map -> JacksonMapper.ofJson().convertValue(map, MAP_TYPE);
            default -> throw new IllegalStateException("Datadog threshold cursor has an unexpected type");
        };
        if (!signature.equals(state.get("signature"))) {
            return Optional.empty();
        }
        if (!(state.get("breached") instanceof Boolean breached)) {
            throw new IllegalStateException("Datadog threshold cursor has no boolean breached field");
        }
        return Optional.of(breached);
    }

    private static void writeState(KVStore kv, String key, String signature, boolean breached, Instant sampledAt) throws Exception {
        var value = JacksonMapper.ofJson().writeValueAsString(Map.of("signature", signature, "breached", breached, "sampledAt", sampledAt.toString())).getBytes(StandardCharsets.UTF_8);
        kv.put(key, new KVValueAndMetadata(new KVMetadata("Datadog threshold state", (Duration) null), value));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Observed scalar value")
        private final BigDecimal value;
        @Schema(title = "Threshold")
        private final BigDecimal threshold;
        @Schema(title = "Threshold breached")
        private final Boolean breached;
        @Schema(title = "Observation time")
        private final Instant observedAt;
    }
}
