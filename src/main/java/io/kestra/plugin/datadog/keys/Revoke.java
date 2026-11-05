package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Datadog operationIds: v2:DeleteAPIKey and v2:DeleteApplicationKey. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Revoke one Datadog key", description = "Permanently revokes the key identified by keyId. Deploy a replacement first. Needs api_keys_delete for API keys or org_app_keys_write for application keys.")
@Plugin(examples = @Example(title = "Revoke a replaced key", full = true, code = """
    id: datadog_revoke_key
    namespace: company.security
    tasks:
      - id: revoke
        type: io.kestra.plugin.datadog.keys.Revoke
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        keyId: 00000000-0000-0000-0000-000000000001
        ignoreNotFound: true
    """))
public class Revoke extends AbstractDatadogTask implements RunnableTask<Revoke.Output> {
    @NotNull
    @Schema(title = "Key identifier", description = "Resource ID of the key to revoke. Never supply the key value.")
    @PluginProperty(group = "main")
    private Property<String> keyId;
    @Schema(title = "Key kind", description = "API or APPLICATION.", defaultValue = "API")
    @PluginProperty(group = "main")
    private Property<KeyKind> keyKind;
    @Schema(title = "Ignore missing key", description = "Treat HTTP 404 as already revoked. Other errors fail.", defaultValue = "false")
    @PluginProperty(group = "reliability")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var id = AccessApi.required(context, keyId, "keyId");
            var rKind = context.render(keyKind).as(KeyKind.class).orElse(KeyKind.API);
            var path = rKind.resourcePath(id);
            var ignore = AccessApi.flag(context, ignoreNotFound);
            return Output.builder().keyId(id).revoked(AccessApi.delete(client, path, ignore)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Key identifier")
        private final String keyId;
        @Schema(title = "Key revoked", description = "False when ignoreNotFound accepted HTTP 404.")
        private final Boolean revoked;
    }
}
