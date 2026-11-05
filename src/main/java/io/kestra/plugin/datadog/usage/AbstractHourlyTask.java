package io.kestra.plugin.datadog.usage;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@ValidDatadogLimits
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractHourlyTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Start hour", description = "Inclusive ISO-8601 instant at an exact UTC hour. Sent as a full date-time with seconds and UTC timezone.")
    @PluginProperty(group = "processing")
    private Property<Instant> from;
    @Schema(title = "End hour", description = "Exclusive ISO-8601 instant at an exact UTC hour. Must follow from. Sent as a full date-time with seconds and UTC timezone.")
    @PluginProperty(group = "processing")
    private Property<Instant> to;
    @NotNull
    @Schema(title = "Product families", description = "Non-empty list of Datadog product families, such as INFRA_HOSTS or LOGS.")
    @PluginProperty(group = "main")
    private Property<List<ProductFamily>> productFamilies;
    @Schema(title = "Include descendant organizations", description = "Defaults to false. Including descendants can return several organizations per hour.")
    @PluginProperty(group = "processing")
    private Property<Boolean> includeDescendants;
    @Schema(title = "Include connected accounts", description = "Include partner customer accounts. Defaults to false.")
    @PluginProperty(group = "processing")
    private Property<Boolean> includeConnectedAccounts;
    @Schema(title = "Include breakdown", description = "Include usage subcategories for logs. Defaults to false.")
    @PluginProperty(group = "processing")
    private Property<Boolean> includeBreakdown;
    @Schema(title = "Page size", description = "Rows per request, from 1 to 500. Defaults to 500.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(500) Integer> pageSize;
    @Schema(title = "Maximum results", description = "Maximum rows read, from 1 to 10000. Defaults to 1000.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxResults;

    protected UsageData.Result collect(RunContext context, String cursor, FetchType fetchType) throws Exception {
        try (var client = client(context, true)) {
            var rStart = context.render(from).as(Instant.class).orElseThrow(() -> new IllegalArgumentException("`from` is required"));
            var rEnd = context.render(to).as(Instant.class).orElse(null);
            if (!rStart.equals(rStart.truncatedTo(ChronoUnit.HOURS)) || (rEnd != null && (!rEnd.equals(rEnd.truncatedTo(ChronoUnit.HOURS)) || !rStart.isBefore(rEnd)))) {
                throw new IllegalArgumentException("Usage bounds must be exact UTC hours with `from` before `to`");
            }
            var rFamilies = context.render(productFamilies).asList(ProductFamily.class);
            if (rFamilies.isEmpty()) {
                throw new IllegalArgumentException("`productFamilies` must not be empty");
            }
            var rPage = context.render(pageSize).as(Integer.class).orElse(500);
            var rMax = context.render(maxResults).as(Integer.class).orElse(1000);
            if (rPage < 1 || rPage > 500 || rMax < 1 || rMax > 10000) {
                throw new IllegalArgumentException("`pageSize` must be 1..500 and `maxResults` must be 1..10000");
            }
            var params = DatadogClient.query();
            params.put("filter[timestamp][start]", rStart.toString());
            if (rEnd != null) {
                params.put("filter[timestamp][end]", rEnd.toString());
            }
            params.put("filter[product_families]", rFamilies.stream().map(ProductFamily::apiValue).distinct().collect(Collectors.joining(",")));
            params.put("filter[include_descendants]", context.render(includeDescendants).as(Boolean.class).orElse(false));
            params.put("filter[include_connected_accounts]", context.render(includeConnectedAccounts).as(Boolean.class).orElse(false));
            params.put("filter[include_breakdown]", context.render(includeBreakdown).as(Boolean.class).orElse(false));
            return UsageData.collect(context, client, params, rPage, rMax, cursor, fetchType);
        }
    }
}
