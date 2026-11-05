package io.kestra.plugin.datadog.cost;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.AbstractDatadogTask;
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
import java.net.URI;
import java.nio.file.Files;
import java.time.YearMonth;
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
    @Schema(title = "Start month", description = "Month in YYYY-MM format, as required by the Datadog operation description.")
    private Property<String> startMonth;
    @Schema(title = "End month", description = "Optional month in YYYY-MM format. Must not precede startMonth.")
    private Property<String> endMonth;
    @Schema(title = "Cost view", description = "SUMMARY for parent organization costs or SUB_ORG for child organization costs. Defaults to SUMMARY.")
    private Property<View> view;
    @Schema(title = "Include connected accounts", description = "Include partner customer accounts. Defaults to false.")
    private Property<Boolean> includeConnectedAccounts;
    @Schema(title = "Store costs", description = "Write one organization cost record per ION row and omit inline records. Defaults to false.")
    private Property<Boolean> store;

    protected Output load(RunContext context, String path) throws Exception {
        YearMonth start = month(context.render(startMonth).as(String.class).orElse(""));
        String endText = context.render(endMonth).as(String.class).orElse(null);
        YearMonth end = endText == null ? null : month(endText);
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("`endMonth` must not precede `startMonth`");
        }
        var params = DatadogClient.query();
        params.put("start_month", start.toString());
        if (end != null) {
            params.put("end_month", end.toString());
        }
        params.put("view", context.render(view).as(View.class).orElse(View.SUMMARY).apiValue);
        params.put("include_connected_accounts", context.render(includeConnectedAccounts).as(Boolean.class).orElse(false));
        boolean stored = context.render(store).as(Boolean.class).orElse(false);
        var response = client(context, true).get(path, params);
        // Parse money from the original JSON decimal text, before any binary floating-point conversion.
        Map<String, Object> answer = JacksonMapper.ofJson().readerFor(new TypeReference<Map<String, Object>>() { })
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(response.body());
        if (!(answer.get("data") instanceof List<?> rows)) {
            throw new IllegalStateException("Datadog cost response has no data array");
        }
        List<OrganizationCost> costs = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> data) || !(data.get("attributes") instanceof Map<?, ?> attributes)) {
                throw new IllegalStateException("Datadog cost row must contain attributes");
            }
            Object total = attributes.get("total_cost");
            if (total != null && !(total instanceof Number)) {
                throw new IllegalStateException("Datadog total_cost must be numeric");
            }
            List<Map<String, Object>> charges = new ArrayList<>();
            if (attributes.get("charges") instanceof List<?> chargeRows) {
                for (Object charge : chargeRows) {
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
        URI uri = null;
        if (stored) {
            var file = context.workingDir().createTempFile(".ion");
            try (var output = Files.newOutputStream(file)) {
                for (OrganizationCost cost : costs) {
                    FileSerde.write(output, cost);
                }
            }
            uri = context.storage().putFile(file.toFile());
        }
        return Output.builder().costs(stored ? null : costs).count((long) costs.size()).uri(uri).build();
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
        @Schema(title = "Cost record count")
        private final Long count;
        @Schema(title = "Stored ION costs")
        private final URI uri;
    }
}
