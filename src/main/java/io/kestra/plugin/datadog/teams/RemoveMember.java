package io.kestra.plugin.datadog.teams;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Set;

/** Operations: v2 GetTeamMemberships and DeleteTeamMembership. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Remove one Datadog team member", description = "Removes one user from one team after checking the last-admin guard. Needs teams_read and permission to manage the team. Reads at most 10000 memberships and fails on an incomplete inventory.")
@Plugin(examples = @Example(title = "Remove a former team member", full = true, code = """
    id: datadog_remove_member
    namespace: company.security
    tasks:
      - id: remove
        type: io.kestra.plugin.datadog.teams.RemoveMember
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        teamId: 00000000-0000-0000-0000-000000000001
        userId: 00000000-0000-0000-0000-000000000002
        ignoreNotFound: true
    """))
public class RemoveMember extends AbstractDatadogTask implements RunnableTask<RemoveMember.Output> {
    @NotNull
    @Schema(title = "Team identifier")
    private Property<String> teamId;
    @NotNull
    @Schema(title = "User identifier", description = "The user ID rather than the membership ID.")
    private Property<String> userId;
    @Schema(title = "Ignore missing membership", description = "Treat HTTP 404 as already removed.", defaultValue = "false")
    private Property<Boolean> ignoreNotFound;
    @Schema(title = "Allow removing the last admin", description = "Permit removal of the team's final admin.", defaultValue = "false")
    private Property<Boolean> allowRemoveLastAdmin;

    @Override
    public Output run(RunContext context) throws Exception {
        String team = AccessApi.required(context, teamId, "teamId");
        String user = AccessApi.required(context, userId, "userId");
        String path = Memberships.path(team);
        String target = path + "/" + AccessApi.segment(user);
        boolean ignore = AccessApi.flag(context, ignoreNotFound);
        boolean allow = AccessApi.flag(context, allowRemoveLastAdmin);
        DatadogClient client = client(context, true);
        try {
            Memberships.protectAdmins(Memberships.read(client, path, 10000), Set.of(user), allow);
        } catch (DatadogClient.DatadogException e) {
            if (ignore && e.getStatus() == 404) {
                return Output.builder().removed(false).build();
            }
            throw e;
        }
        return Output.builder().removed(AccessApi.delete(client, target, ignore)).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Membership removed", description = "False when ignoreNotFound accepted HTTP 404.")
        private final Boolean removed;
    }
}
