package io.kestra.plugin.datadog.cases;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: CommentCase. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Comment on a Datadog case", description = "Adds a comment to one case timeline. Returns timeline cells in body.data. Requires both keys and case write access.")
@Plugin(examples = @Example(title = "Record pipeline remediation", full = true, code = """
    id: comment_case
    namespace: company.observability
    tasks:
      - id: comment
        type: io.kestra.plugin.datadog.cases.Comment
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        caseId: "00000000-0000-0000-0000-000000000001"
        comment: "Remediation finished in execution {{ execution.id }}"
    """))
public class Comment extends CaseTask implements RunnableTask<ResponseOutput> {
    @NotNull
    @Schema(title = "Comment text")
    @PluginProperty(group = "main")
    private Property<String> comment;

    @Override
    public ResponseOutput run(RunContext context) throws Exception {
        return update(context, "comment", Map.of("comment", required(context, comment, "comment")));
    }
}
