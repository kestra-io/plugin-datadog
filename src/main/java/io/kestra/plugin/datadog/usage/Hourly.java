package io.kestra.plugin.datadog.usage;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Datadog operationId: GetHourlyUsage. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read hourly product usage", description = "Reads the v2 usage meter with usage_read permission. Follows next_record_id until the result limit. Integer measurements retain their integer representation and null measurements remain null. Use nextRecordId to resume with the same filters.")
@Plugin(examples = @Example(title = "Export daily infrastructure usage", full = true, code = """
    id: datadog_hourly_usage
    namespace: company.finops
    tasks:
      - id: usage
        type: io.kestra.plugin.datadog.usage.Hourly
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        from: "2026-01-01T00:00:00Z"
        to: "2026-01-02T00:00:00Z"
        productFamilies: [INFRA_HOSTS]
        store: true
    """))
public class Hourly extends AbstractHourlyTask implements RunnableTask<Hourly.Output> {
    @Schema(title = "Resume cursor", description = "Opaque next_record_id returned by a previous run with the same filters.")
    private Property<String> nextRecordId;
    @Schema(title = "Store usage", description = "Stream one usage row per ION record and omit inline rows. Defaults to false.")
    private Property<Boolean> store;

    @Override
    public Output run(RunContext context) throws Exception {
        var result = collect(context, context.render(nextRecordId).as(String.class).filter(s -> !s.isBlank()).orElse(null), context.render(store).as(Boolean.class).orElse(false));
        return Output.builder().rows(result.rows()).count(result.count()).nextRecordId(result.nextRecordId())
            .truncated(result.nextRecordId() != null).uri(result.uri()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Usage rows", description = "Datadog usage_timeseries records, including measurements with int64 or null values.")
        private final List<Map<String, Object>> rows;
        @Schema(title = "Collected row count")
        private final Long count;
        @Schema(title = "Next record cursor")
        private final String nextRecordId;
        @Schema(title = "Result limit reached")
        private final Boolean truncated;
        @Schema(title = "Stored ION rows")
        private final URI uri;
    }
}
