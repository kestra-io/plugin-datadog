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
    @Schema(title = "Monitor", description = "Datadog monitor response returned unchanged.")
    private final Map<String, Object> monitor;
    @Schema(title = "Dry run", description = "True when Create validated a definition without saving it. No monitor id is assigned then.")
    private final Boolean dryRun;

    static MonitorOutput of(Map<String, Object> response) {
        if (!(response.get("id") instanceof Number id)) {
            throw new IllegalStateException("Datadog returned a monitor without an id");
        }
        return builder().monitorId(id.longValue()).monitor(response).dryRun(false).build();
    }
}
