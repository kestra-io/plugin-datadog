package io.kestra.plugin.datadog.cost;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractCostTask extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Start month", description = "Month in YYYY-MM format. Sent as midnight UTC on the first day to satisfy the API date-time schema.")
    @PluginProperty(group = "main")
    private Property<String> startMonth;
    @Schema(title = "End month", description = "Optional month in YYYY-MM format. Must not precede startMonth. Sent as midnight UTC on the first day.")
    @PluginProperty(group = "main")
    private Property<String> endMonth;
    @Schema(title = "Cost view", description = "SUMMARY for parent organization costs or SUB_ORG for child organization costs. Defaults to SUMMARY.")
    @PluginProperty(group = "main")
    private Property<View> view;
    @Schema(title = "Include connected accounts", description = "Include partner customer accounts. Defaults to false.")
    @PluginProperty(group = "processing")
    private Property<Boolean> includeConnectedAccounts;
    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    protected Output load(RunContext context, String path) throws Exception {
        try (var client = client(context, true)) {
            var rStart = month(context.render(startMonth).as(String.class).orElse(""));
            var rEndText = context.render(endMonth).as(String.class).orElse(null);
            var end = rEndText == null ? null : month(rEndText);
            if (end != null && end.isBefore(rStart)) {
                throw new IllegalArgumentException("`endMonth` must not precede `startMonth`");
            }
            var params = DatadogClient.query();
            params.put("start_month", rStart.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            if (end != null) {
                params.put("end_month", end.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            }
            params.put("view", context.render(view).as(View.class).orElse(View.SUMMARY).apiValue);
            params.put("include_connected_accounts", context.render(includeConnectedAccounts).as(Boolean.class).orElse(false));
            var response = client.get(path, params);
            // Parse money from the original JSON decimal text, before any binary floating-point conversion.
            var answer = JacksonMapper.ofJson().readerFor(new TypeReference<Map<String, Object>>() { })
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).<Map<String, Object>>readValue(response.body());
            if (!(answer.get("data") instanceof List<?> rows)) {
                throw new IllegalStateException("Datadog cost response has no data array");
            }
            var costs = new ArrayList<OrganizationCost>();
            for (var row : rows) {
                if (!(row instanceof Map<?, ?> data) || !(data.get("attributes") instanceof Map<?, ?> attributes)) {
                    throw new IllegalStateException("Datadog cost row must contain attributes");
                }
                var total = attributes.get("total_cost");
                if (total != null && !(total instanceof Number)) {
                    throw new IllegalStateException("Datadog total_cost must be numeric");
                }
                var charges = new ArrayList<Map<String, Object>>();
                if (attributes.get("charges") instanceof List<?> chargeRows) {
                    for (var charge : chargeRows) {
                        if (!(charge instanceof Map<?, ?> map)) {
                            throw new IllegalStateException("Datadog cost charge must be an object");
                        }
                        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) map;
                        charges.add(item);
                    }
                }
                costs.add(OrganizationCost.builder().id((String) data.get("id")).orgId((String) attributes.get("public_id"))
                    .orgName((String) attributes.get("org_name")).date((String) attributes.get("date"))
                    .accountId((String) attributes.get("account_public_id")).accountName((String) attributes.get("account_name"))
                    .region((String) attributes.get("region")).totalCost(total == null ? null : new BigDecimal(total.toString())).charges(charges).build());
            }
            var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), costs);
            return Output.builder().costs(result.rows()).row(result.row()).count((long) result.size()).uri(result.uri()).build();
        }
    }

    private static YearMonth month(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) {
                throw new IllegalArgumentException("Cost months must use YYYY-MM");
            }
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Cost months must use valid YYYY-MM values", e);
        }
    }

    public enum View {
        SUMMARY("summary"), SUB_ORG("sub-org");

        private final String apiValue;

        View(String apiValue) {
            this.apiValue = apiValue;
        }
    }

    @Builder
    @Getter
    public static class OrganizationCost {
        @Schema(title = "Cost record ID")
        private final String id;
        @Schema(title = "Organization public ID")
        private final String orgId;
        @Schema(title = "Organization name")
        private final String orgName;
        @Schema(title = "Account public ID")
        private final String accountId;
        @Schema(title = "Account name")
        private final String accountName;
        @Schema(title = "Cost date", description = "Date text returned by Datadog.")
        private final String date;
        @Schema(title = "Region")
        private final String region;
        @Schema(title = "Total cost", description = "Decimal value read directly from Datadog JSON. No currency conversion is applied.")
        private final BigDecimal totalCost;
        @Schema(title = "Charge breakdown", description = "Datadog charges with decimal values preserved.")
        private final List<Map<String, Object>> charges;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Organization costs")
        private final List<OrganizationCost> costs;
        @Schema(title = "First organization cost", description = "Present with FETCH_ONE.")
        private final OrganizationCost row;
        @Schema(title = "Cost record count")
        private final Long count;
        @Schema(title = "Stored ION costs")
        private final URI uri;
    }
}
