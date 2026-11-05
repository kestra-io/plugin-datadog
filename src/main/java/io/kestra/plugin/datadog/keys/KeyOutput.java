package io.kestra.plugin.datadog.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/** Allowlisted output for CreateAPIKey and CreateCurrentUserApplicationKey. */
@Builder
@Getter
public class KeyOutput implements io.kestra.core.models.tasks.Output {
    @Schema(title = "New key identifier")
    private final String keyId;
    @Schema(title = "Key name")
    private final String name;
    @Schema(title = "Key value", description = "Redacted unless includeSecrets is true. Never log this output when secrets are included.")
    private final String key;
    @Schema(title = "Redacted key preview", description = "A fixed mask followed by Datadog's last4 field when available.")
    private final String preview;
    @Schema(title = "Application key scopes")
    private final List<String> scopes;

    static KeyOutput from(Map<String, Object> response, boolean includeSecrets) {
        Map<String, Object> data = AccessApi.object(response.get("data"));
        Map<String, Object> attributes = AccessApi.object(data.get("attributes"));
        String id = AccessApi.required(data.get("id"), "response id");
        String last = attributes.get("last4") instanceof String text && text.length() == 4 ? text : "";
        String raw = attributes.get("key") instanceof String text ? text : null;
        List<String> scopes = attributes.get("scopes") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        return KeyOutput.builder().keyId(id).name((String) attributes.get("name"))
            .key(includeSecrets ? raw : "[REDACTED]").preview("****" + last).scopes(scopes).build();
    }
}
