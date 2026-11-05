package io.kestra.plugin.datadog.statuspages;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** Datadog operationId: CreateDegradation. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a status page degradation", description = "Publishes one degradation with affected component states. Requires status_pages_incident_write. Dry run is true by default and subscriber notifications default to false.")
@Plugin(examples = @Example(title = "Preview an outage update", full = true, code = """
    id: create_degradation
    namespace: company.observability
    tasks:
      - id: degradation
        type: io.kestra.plugin.datadog.statuspages.CreateDegradation
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        pageId: "00000000-0000-0000-0000-000000000001"
        title: Orders API latency
        status: INVESTIGATING
        message: We are investigating elevated latency.
        components:
          "00000000-0000-0000-0000-000000000002": DEGRADED
        dryRun: true
    """))
public class CreateDegradation extends DegradationTask implements RunnableTask<ResponseOutput> {
    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return publish(context, null);
    }
}
