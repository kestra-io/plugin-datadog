package io.kestra.plugin.datadog.hosts;

import io.kestra.core.models.property.Property;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractHostAction extends AbstractDatadogTask {
    @NotNull
    @Schema(title = "Host name", description = "Exact name of one host. Blank names and wildcards are rejected.")
    private Property<String> hostName;

    @Schema(title = "Ignore missing host", description = "Return found=false on HTTP 404. Defaults to false.")
    private Property<Boolean> ignoreNotFound;

    protected Output execute(RunContext context, String action, Object body) throws Exception {
        String host = context.render(hostName).as(String.class).orElse("").trim();
        if (host.isEmpty() || host.contains("*") || host.contains("?")) {
            throw new IllegalArgumentException("`hostName` must identify one host without wildcards");
        }
        boolean ignore = context.render(ignoreNotFound).as(Boolean.class).orElse(false);
        try {
            Map<String, Object> response = client(context, true).post("/api/v1/host/" + URLEncoder.encode(host, StandardCharsets.UTF_8).replace("+", "%20") + "/" + action, body).map();
            return Output.builder().found(true).hostname((String) response.get("hostname"))
                .action((String) response.get("action")).message((String) response.get("message"))
                .end(response.get("end") instanceof Number n ? n.longValue() : null).build();
        } catch (DatadogClient.DatadogException e) {
            if (ignore && e.getStatus() == 404) {
                return Output.builder().found(false).hostname(host).build();
            }
            throw e;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Host found")
        private final Boolean found;
        @Schema(title = "Host name")
        private final String hostname;
        @Schema(title = "Action reported by Datadog")
        private final String action;
        @Schema(title = "Mute end in epoch seconds")
        private final Long end;
        @Schema(title = "Mute message")
        private final String message;
    }
}
