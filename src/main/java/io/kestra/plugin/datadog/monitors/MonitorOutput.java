package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Builder
@Getter
public class MonitorOutput implements Output {
    @Schema(title = "Monitor id")
    private final Long monitorId;
    @Schema(title = "Monitor", description = "Datadog monitor response with sensitive output values filtered unless includeSecrets is true.")
    private final Map<String, Object> monitor;

    static MonitorOutput of(Map<String, Object> response, boolean includeSecrets) {
        if (!(response.get("id") instanceof Number id)) {
            throw new IllegalStateException("Datadog returned a monitor without an id");
        }
        return builder().monitorId(id.longValue()).monitor(MonitorIO.redactMap(response, includeSecrets)).build();
    }
}
