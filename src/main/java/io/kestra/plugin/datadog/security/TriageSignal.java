package io.kestra.plugin.datadog.security;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Datadog operationId: v2:EditSecurityMonitoringSignal. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Triage one Datadog security signal", description = "Updates a single signal's state, assignee or archive comment. Needs security_monitoring_signals_write. This endpoint supports an archive comment rather than a general note. Outputs only the signal ID and completion flag.")
@Plugin(examples = @Example(title = "Mark a signal under review", full = true, code = """
    id: datadog_triage_signal
    namespace: company.security
    tasks:
      - id: triage
        type: io.kestra.plugin.datadog.security.TriageSignal
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        signalId: signal-id
        state: UNDER_REVIEW
    """))
public class TriageSignal extends AbstractDatadogTask implements RunnableTask<TriageSignal.Output> {
    @NotNull
    @Schema(title = "Signal identifier")
    private Property<String> signalId;
    @Schema(title = "Signal state", description = "OPEN, UNDER_REVIEW or ARCHIVED.")
    private Property<State> state;
    @Schema(title = "Assignee user UUID", description = "Datadog UUID of the user to assign.")
    private Property<String> assigneeId;
    @Schema(title = "Archive reason", description = "Reason for archiving. Requires state ARCHIVED.")
    private Property<ArchiveReason> archiveReason;
    @Schema(title = "Archive comment", description = "Comment attached to archiving. Requires state ARCHIVED.")
    private Property<String> archiveComment;

    public enum State { OPEN, UNDER_REVIEW, ARCHIVED }
    public enum ArchiveReason { NONE, FALSE_POSITIVE, TESTING_OR_MAINTENANCE, REMEDIATED, INVESTIGATED_CASE_OPENED, TRUE_POSITIVE_BENIGN, TRUE_POSITIVE_MALICIOUS, OTHER }

    @Override
    public Output run(RunContext context) throws Exception {
        String id = AccessApi.required(context, signalId, "signalId");
        String path = "/api/v2/security_monitoring/signals/" + AccessApi.segment(id) + "/update";
        State state = context.render(this.state).as(State.class).orElse(null);
        ArchiveReason reason = context.render(archiveReason).as(ArchiveReason.class).orElse(null);
        String comment = context.render(archiveComment).as(String.class).orElse(null);
        String assignee = context.render(assigneeId).as(String.class).orElse(null);
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (state != null) { attributes.put("state", state.name().toLowerCase(Locale.ROOT)); }
        if ((reason != null || comment != null) && state != State.ARCHIVED) {
            throw new IllegalArgumentException("Archive fields require state ARCHIVED");
        }
        if (reason != null) { attributes.put("archive_reason", reason.name().toLowerCase(Locale.ROOT)); }
        if (comment != null) { attributes.put("archive_comment", comment); }
        if (assignee != null) { attributes.put("assignee", Map.of("uuid", AccessApi.required(assignee, "assigneeId"))); }
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("Set state, assigneeId or archive fields");
        }
        AccessApi.request(client(context, true), "PATCH", path, Map.of(), Map.of("data", Map.of("type", "signal_metadata", "attributes", attributes)));
        return Output.builder().signalId(id).updated(true).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Signal identifier")
        private final String signalId;
        @Schema(title = "Signal updated")
        private final Boolean updated;
    }
}
