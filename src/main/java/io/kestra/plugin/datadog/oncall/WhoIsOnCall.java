package io.kestra.plugin.datadog.oncall;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.kestra.plugin.datadog.incidents.ResponseSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import java.util.Map;

/** Datadog operationId: GetTeamOnCallUsers. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Read the current On-Call responders for a team", description = "Reads the team responders and escalations on the standard API host. This endpoint has no time parameter. Requires both keys.")
@Plugin(examples = @Example(title = "Find the current responders", full = true, code = """
    id: current_responders
    namespace: company.observability
    tasks:
      - id: responders
        type: io.kestra.plugin.datadog.oncall.WhoIsOnCall
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        teamId: "00000000-0000-0000-0000-000000000001"
    """))
public class WhoIsOnCall extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Team id")
    private Property<String> teamId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return send(context, "GET", "/api/v2/on-call/teams/" + identifier(context, teamId, "teamId") + "/on-call",
            Map.of("include", "responders,escalations.responders"), null, false);
    }
}
