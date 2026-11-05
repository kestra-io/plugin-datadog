package io.kestra.plugin.datadog.synthetics;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/** Datadog operationId: v1:UpdateTestPauseStatus. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Pause or resume a Datadog synthetic test", description = "Sets one identified test to PAUSED or LIVE. PAUSED stops scheduled runs. LIVE resumes them. Needs synthetics_write.")
@Plugin(examples = @Example(title = "Pause a test during maintenance", full = true, code = """
    id: pause_synthetic
    namespace: company.delivery
    tasks:
      - id: pause
        type: io.kestra.plugin.datadog.synthetics.SetStatus
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        publicId: abc-def-ghi
        status: PAUSED
    """))
public class SetStatus extends AbstractDatadogTask implements RunnableTask<SetStatus.Output> {
    @NotNull
    @Schema(title = "Public test ID")
    @PluginProperty(group = "main")
    private Property<String> publicId;
    @NotNull
    @Schema(title = "New schedule status")
    @PluginProperty(group = "main")
    private Property<Status> status;
    @Schema(title = "Ignore missing test", description = "Return accepted=false on HTTP 404.", defaultValue = "false")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var rId = Synthetics.id(context.render(publicId).as(String.class).orElse(null), "publicId");
            var rValue = context.render(status).as(Status.class).orElseThrow(() -> new IllegalArgumentException("`status` is required"));
            var rIgnore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
            try {
                var response = client.put("/api/v1/synthetics/tests/" + rId + "/status", Map.of("new_status", rValue.apiValue)).json();
                if (!(response instanceof Boolean accepted)) {
                    throw new IllegalStateException("Datadog returned no boolean for the pause status");
                }
                if (!accepted) {
                    throw new IllegalStateException("Datadog did not accept the schedule status for test " + rId);
                }
                return Output.builder().accepted(true).build();
            } catch (DatadogClient.DatadogException e) {
                if (e.getStatus() != 404 || !rIgnore) {
                    throw e;
                }
                return Output.builder().accepted(false).build();
            }
        }
    }

    public enum Status {
        LIVE("live"), PAUSED("paused");
        final String apiValue;
        Status(String apiValue) {
            this.apiValue = apiValue;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Status change accepted")
        private final Boolean accepted;
    }
}
