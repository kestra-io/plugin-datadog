package io.kestra.plugin.datadog.users;

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

import java.util.Map;

/** Datadog operationId: v2:GetUser. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Get a Datadog user", description = "Reads one user or service account by ID with its access metadata and role relationships. Needs user_access_read. Credential fields are redacted by default.")
@Plugin(examples = @Example(title = "Read a user's access", full = true, code = """
    id: datadog_get_user
    namespace: company.security
    tasks:
      - id: user
        type: io.kestra.plugin.datadog.users.Get
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        userId: 00000000-0000-0000-0000-000000000001
    """))
public class Get extends AbstractDatadogTask implements RunnableTask<Get.Output> {
    @NotNull
    @Schema(title = "User identifier")
    private Property<String> userId;
    @Schema(title = "Include secrets", description = "Include credential and URL fields in the response.", defaultValue = "false")
    private Property<Boolean> includeSecrets;

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext context) throws Exception {
        String path = "/api/v2/users/" + AccessApi.segment(AccessApi.required(context, userId, "userId"));
        boolean include = AccessApi.flag(context, includeSecrets);
        Map<String, Object> data = AccessApi.object(AccessApi.json(AccessApi.request(client(context, true), "GET", path, Map.of(), null)).get("data"));
        return Output.builder().user((Map<String, Object>) AccessApi.redact(data, include)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "User resource", description = "Datadog's user data with credential fields redacted unless includeSecrets is true.")
        private final Map<String, Object> user;
    }
}
