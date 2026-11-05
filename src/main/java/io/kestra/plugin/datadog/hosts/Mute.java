package io.kestra.plugin.datadog.hosts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;

/** Datadog operationId: MuteHost. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Mute one host for a bounded period", description = "Creates a Datadog v2 downtime for one host through MuteHost. Requires an API key and an application key. Exactly one of end or duration is required. Existing mute settings are not overwritten unless override is true.")
@Plugin(examples = @Example(title = "Mute a host during maintenance", full = true, code = """
    id: datadog_host_maintenance
    namespace: company.observability
    tasks:
      - id: mute
        type: io.kestra.plugin.datadog.hosts.Mute
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        hostName: worker-01
        duration: PT30M
        message: "Scheduled maintenance"
    """))
public class Mute extends AbstractHostAction implements RunnableTask<AbstractHostAction.Output> {
    @Schema(title = "Mute end", description = "ISO-8601 instant in the future. Mutually exclusive with duration.")
    @PluginProperty(group = "main")
    private Property<Instant> end;
    @Schema(title = "Mute duration", description = "Positive duration from now. The resulting end must be in a future epoch second.")
    @PluginProperty(group = "main")
    private Property<Duration> duration;
    @Schema(title = "Mute message", description = "Message associated with the downtime.")
    @PluginProperty(group = "main")
    private Property<String> message;
    @Schema(title = "Override existing mute", description = "Explicitly overwrite the host's existing mute settings. Defaults to false.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> override;

    @Override
    public Output run(RunContext context) throws Exception {
        var rStop = context.render(end).as(Instant.class).orElse(null);
        var rLength = context.render(duration).as(Duration.class).orElse(null);
        if ((rStop == null) == (rLength == null)) {
            throw new IllegalArgumentException("Set exactly one of `end` or `duration`");
        }
        var now = Instant.now();
        if (rLength != null) {
            if (rLength.isZero() || rLength.isNegative()) {
                throw new IllegalArgumentException("`duration` must be positive");
            }
            rStop = now.plus(rLength);
        }
        if (rStop.getEpochSecond() <= now.getEpochSecond()) {
            throw new IllegalArgumentException("The mute end must be in a future epoch second");
        }
        var body = DatadogClient.query();
        body.put("end", rStop.getEpochSecond());
        body.put("override", context.render(override).as(Boolean.class).orElse(false));
        context.render(message).as(String.class).ifPresent(value -> body.put("message", value));
        return execute(context, "mute", body);
    }
}
