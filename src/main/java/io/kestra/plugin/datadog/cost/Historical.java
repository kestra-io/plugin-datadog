package io.kestra.plugin.datadog.cost;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: GetHistoricalCostByOrg. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read historical organization costs", description = "Reads monthly Datadog charges for a parent organization with usage_read and billing_read. A month's costs become available no later than the 16th of the following month. Amounts use decimal outputs.")
@Plugin(examples = @Example(title = "Export historical Datadog charges", full = true, code = """
    id: datadog_historical_cost
    namespace: company.finops
    tasks:
      - id: costs
        type: io.kestra.plugin.datadog.cost.Historical
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        startMonth: "2026-01"
        endMonth: "2026-03"
        fetchType: STORE
    """))
public class Historical extends AbstractCostTask implements RunnableTask<AbstractCostTask.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return load(context, "/api/v2/usage/historical_cost");
    }
}
