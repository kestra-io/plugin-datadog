package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFormulaTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Named queries", description = "Map of formula variable names to metric query strings. For example `a: avg:system.cpu.user{*}`.")
    @PluginProperty(group = "main")
    private Property<Map<String, String>> queries;

    @Schema(title = "Formulas", description = "Expressions using query names, such as `a / b * 100`. Omit to return each query.")
    @PluginProperty(group = "main")
    private Property<List<String>> formulas;

    @Schema(title = "Data source", description = "METRICS or CLOUD_COST. Defaults to METRICS.")
    @PluginProperty(group = "processing")
    private Property<DataSource> dataSource;

    @Schema(title = "Window start", description = "Inclusive ISO-8601 instant. Defaults to lookback before to.")
    @PluginProperty(group = "processing")
    private Property<Instant> from;

    @Schema(title = "Window end", description = "Exclusive ISO-8601 instant. Defaults to now.")
    @PluginProperty(group = "processing")
    private Property<Instant> to;

    @Schema(title = "Lookback", description = "Positive window duration used when from is absent. Defaults to PT1H.")
    @PluginProperty(group = "processing")
    private Property<Duration> lookback;

    protected Map<String, Object> requestBody(RunContext context, Aggregator aggregator, Long interval) throws Exception {
        if (from != null && lookback != null) {
            throw new IllegalArgumentException("`lookback` cannot be used with `from`");
        }
        var rEnd = context.render(to).as(Instant.class).orElseGet(Instant::now);
        var rWindow = context.render(lookback).as(Duration.class).orElse(Duration.ofHours(1));
        if (rWindow.isNegative() || rWindow.isZero()) {
            throw new IllegalArgumentException("`lookback` must be positive");
        }
        var rStart = context.render(from).as(Instant.class).orElseGet(() -> rEnd.minus(rWindow));
        return FormulaQueries.body(context.render(queries).asMap(String.class, String.class),
            context.render(formulas).asList(String.class), rStart, rEnd,
            context.render(dataSource).as(DataSource.class).orElse(DataSource.METRICS), aggregator, interval);
    }
}
