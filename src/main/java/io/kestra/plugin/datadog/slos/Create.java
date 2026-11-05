package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: CreateSLO. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog SLO", description = "Creates a metric or monitor SLO with explicit thresholds. Needs slos_write. Every run creates a new SLO; use Update with an existing id for changes.")
@Plugin(examples = @Example(title = "Create a metric-based SLO", full = true, code = """
    id: datadog_slo_create
    namespace: company.observability
    tasks:
      - id: slo
        type: io.kestra.plugin.datadog.slos.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        name: Orders availability
        sloType: METRIC
        numerator: "sum:orders.good{*}.as_count()"
        denominator: "sum:orders.total{*}.as_count()"
        thresholds:
          - timeframe: SEVEN_DAYS
            target: 99.9
    """))
public class Create extends AbstractSloDefinitionTask implements RunnableTask<Create.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var body = body(context);
            return Output.builder().slos(result(context, client.post(Slos.PATH, body).map())).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Created SLOs", description = "The data array returned by CreateSLO.")
        private final java.util.List<Map<String, Object>> slos;
    }
}
