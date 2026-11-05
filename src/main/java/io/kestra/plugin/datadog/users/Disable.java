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

/** Datadog operationId: v2:DisableUser. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Disable one Datadog user", description = "Disables access for the user identified by userId. Needs user_access_manage or service_account_write as applicable to the target account.")
@Plugin(examples = @Example(title = "Disable a departing user", full = true, code = """
    id: datadog_disable_user
    namespace: company.security
    tasks:
      - id: disable
        type: io.kestra.plugin.datadog.users.Disable
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        userId: 00000000-0000-0000-0000-000000000001
        ignoreNotFound: true
    """))
public class Disable extends AbstractDatadogTask implements RunnableTask<Disable.Output> {
    @NotNull
    @Schema(title = "User identifier")
    private Property<String> userId;
    @Schema(title = "Ignore missing user", description = "Treat HTTP 404 as already absent. Other errors fail.", defaultValue = "false")
    private Property<Boolean> ignoreNotFound;

    @Override
    public Output run(RunContext context) throws Exception {
        String path = "/api/v2/users/" + AccessApi.segment(AccessApi.required(context, userId, "userId"));
        boolean ignore = AccessApi.flag(context, ignoreNotFound);
        return Output.builder().disabled(AccessApi.delete(client(context, true), path, ignore)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "User disabled", description = "False when ignoreNotFound accepted HTTP 404.")
        private final Boolean disabled;
    }
}
