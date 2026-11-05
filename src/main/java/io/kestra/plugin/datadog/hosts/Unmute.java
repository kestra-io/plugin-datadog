package io.kestra.plugin.datadog.hosts;

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

/** Datadog operationId: UnmuteHost. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Unmute one host", description = "Removes the mute from the exact host name. Requires an API key and an application key. Sends no JSON body. HTTP 404 fails unless ignoreNotFound is true.")
@Plugin(examples = @Example(title = "End host maintenance", full = true, code = """
    id: datadog_host_unmute
    namespace: company.observability
    tasks:
      - id: unmute
        type: io.kestra.plugin.datadog.hosts.Unmute
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        hostName: worker-01
        ignoreNotFound: true
    """))
public class Unmute extends AbstractHostAction implements RunnableTask<AbstractHostAction.Output> {
    @Override
    public Output run(RunContext context) throws Exception {
        return execute(context, "unmute", null);
    }
}
