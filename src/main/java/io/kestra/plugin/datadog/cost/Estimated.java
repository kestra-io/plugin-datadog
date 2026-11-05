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

/** Datadog operationId: GetEstimatedCostByOrg. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read estimated organization costs", description = "Reads monthly Datadog account cost estimates for a parent organization. Requires usage_read and billing_read. Estimates cover the current and previous month and can lag incurred costs by up to 72 hours. These are Datadog charges, not cloud provider billing exports.")
@Plugin(examples = @Example(title = "Read this month's estimated Datadog charges", full = true, code = """
    id: datadog_estimated_cost
    namespace: company.finops
    tasks:
      - id: costs
        type: io.kestra.plugin.datadog.cost.Estimated
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        startMonth: "{{ now() | date('yyyy-MM') }}"
        view: SUB_ORG
    """))
public class Estimated extends AbstractCostTask implements RunnableTask<AbstractCostTask.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return load(context, "/api/v2/usage/estimated_cost");
    }
}
