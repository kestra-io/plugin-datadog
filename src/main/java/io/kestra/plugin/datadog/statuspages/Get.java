package io.kestra.plugin.datadog.statuspages;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

/** Datadog operationId: GetStatusPage. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog status page", description = "Reads one status page. Requires status_pages_settings_read.")
@Plugin(examples = @Example(title = "Read a status page", full = true, code = """
    id: get_status_page
    namespace: company.observability
    tasks:
      - id: page
        type: io.kestra.plugin.datadog.statuspages.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        pageId: "00000000-0000-0000-0000-000000000001"
    """))
public class Get extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Status page id")
    @PluginProperty(group = "processing")
    private Property<String> pageId;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return send(context, "GET", "/api/v2/statuspages/" + uuid(context, pageId, "pageId"), Map.of(), null, false);
    }
}
