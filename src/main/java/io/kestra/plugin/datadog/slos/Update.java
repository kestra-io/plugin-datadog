package io.kestra.plugin.datadog.slos;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: UpdateSLO. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Update a Datadog SLO", description = "Replaces one metric or monitor SLO configuration, including its thresholds and queries or monitors. Supply the full desired configuration. Needs slos_write.")
@Plugin(examples = @Example(title = "Replace a monitor-based SLO configuration", full = true, code = """
    id: datadog_slo_update
    namespace: company.observability
    tasks:
      - id: slo
        type: io.kestra.plugin.datadog.slos.Update
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        sloId: 0123456789abcdef0123456789abcdef
        name: Orders availability
        sloType: MONITOR
        monitorIds: [12345]
        thresholds:
          - timeframe: THIRTY_DAYS
            target: 99.9
    """))
public class Update extends AbstractSloDefinitionTask implements RunnableTask<Update.Output> {
    @NotNull
    @Schema(title = "SLO identifier")
    private Property<String> sloId;

    @Override
    public Output run(RunContext context) throws Exception {
        String path = Slos.path(context.render(sloId).as(String.class).orElse(null));
        var body = body(context);
        return Output.builder().slos(result(client(context, true).put(path, body).map())).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Updated SLOs", description = "The data array returned by UpdateSLO.")
        private final java.util.List<Map<String, Object>> slos;
    }
}
