package io.kestra.plugin.datadog.hosts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** Datadog operationId: GetHostTotals. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read active and up host totals", description = "Returns 64-bit host counts using hosts_read permission. Datadog defines active as reporting in the past hour and up as reporting in the past two hours.")
@Plugin(examples = @Example(title = "Read infrastructure totals", full = true, code = """
    id: datadog_host_totals
    namespace: company.observability
    tasks:
      - id: totals
        type: io.kestra.plugin.datadog.hosts.Totals
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
    """))
public class Totals extends AbstractDatadogTask implements RunnableTask<Totals.Output> {
    @Schema(title = "Start time", description = "Optional ISO-8601 instant sent as epoch seconds in from.")
    private Property<Instant> from;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var params = DatadogClient.query();
            context.render(from).as(Instant.class).ifPresent(value -> params.put("from", value.getEpochSecond()));
            var data = client.get("/api/v1/hosts/totals", params).map();
            if (!(data.get("total_active") instanceof Number active) || !(data.get("total_up") instanceof Number up)) {
                throw new IllegalStateException("Datadog host totals response needs total_active and total_up");
            }
            return Output.builder().totalActive(active.longValue()).totalUp(up.longValue()).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Active hosts")
        private final Long totalActive;
        @Schema(title = "Up hosts")
        private final Long totalUp;
    }
}
