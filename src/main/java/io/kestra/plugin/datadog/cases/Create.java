package io.kestra.plugin.datadog.cases;

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

import java.util.LinkedHashMap;
import java.util.Map;

/** Datadog operationId: CreateCase. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Create a Datadog case", description = "Creates a case of the selected case type in one project. Requires cases_write.")
@Plugin(examples = @Example(title = "Track incident follow-up", full = true, code = """
    id: create_case
    namespace: company.observability
    tasks:
      - id: follow_up
        type: io.kestra.plugin.datadog.cases.Create
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        title: Review the orders outage
        caseTypeId: "00000000-0000-0000-0000-000000000001"
        projectId: "00000000-0000-0000-0000-000000000002"
        priority: P2
    """))
public class Create extends ResponseSupport implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Case title")
    @PluginProperty(group = "main")
    private Property<String> title;

    @NotNull
    @Schema(title = "Case type id", description = "The work item type UUID sent as type_id.")
    @PluginProperty(group = "main")
    private Property<String> caseTypeId;

    @NotNull
    @Schema(title = "Project id")
    @PluginProperty(group = "main")
    private Property<String> projectId;

    @Schema(title = "Case description")
    @PluginProperty(group = "main")
    private Property<String> caseDescription;

    @Schema(title = "Priority")
    @PluginProperty(group = "main")
    private Property<Priority> priority;

    @Schema(title = "Status name", description = "An existing status for this case type.")
    @PluginProperty(group = "main")
    private Property<String> statusName;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("title", required(context, title, "title"));
        attributes.put("type_id", identifier(context, caseTypeId, "caseTypeId"));
        context.render(caseDescription).as(String.class).ifPresent(v -> attributes.put("description", v));
        context.render(priority).as(Priority.class).ifPresent(v -> attributes.put("priority", v.name()));
        if (statusName != null) {
            attributes.put("status_name", required(context, statusName, "statusName"));
        }
        var data = resource("case", null, attributes);
        data.put("relationships", Map.of("project", Map.of("data",
            Map.of("type", "project", "id", identifier(context, projectId, "projectId")))));
        return send(context, "POST", "/api/v2/cases", Map.of(), Map.of("data", data), false);
    }
}
