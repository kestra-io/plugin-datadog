package io.kestra.plugin.datadog.teams;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Datadog operationIds: v2:ListUsers, v2:GetTeamMemberships, v2:CreateTeamMembership and v2:DeleteTeamMembership. */
@ValidDatadogLimits
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Sync one Datadog team's members", description = "Makes the team's user membership match an explicit list of handles. Adds regular members and preserves existing roles. Removes members outside the list after checking the last-admin guard. Needs user_access_read and teams_read plus permission to manage the team. Fails on incomplete inventories or ambiguous handles before writing. Changes are sequential and are not transactional.")
@Plugin(examples = @Example(title = "Preview a team's membership sync", full = true, code = """
    id: datadog_sync_team
    namespace: company.security
    tasks:
      - id: sync
        type: io.kestra.plugin.datadog.teams.SyncMembers
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        teamId: 00000000-0000-0000-0000-000000000001
        handles: [admin@example.com, engineer@example.com]
        plan: true
    """))
public class SyncMembers extends AbstractDatadogTask implements RunnableTask<SyncMembers.Output> {
    @NotNull
    @Schema(title = "Team identifier", description = "ID of the single team to reconcile.")
    @PluginProperty(group = "main")
    private Property<String> teamId;
    @NotNull
    @Schema(title = "Desired user handles", description = "Exact Datadog user handles. An explicit empty list requests removal of every member. Unknown, disabled or duplicate handles fail validation.")
    @PluginProperty(group = "main")
    private Property<List<String>> handles;
    @Schema(title = "Plan changes", description = "Use GET requests to return the planned changes without writing. The last-admin guard is still checked.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> plan;
    @Schema(title = "Allow removing the last admin", description = "Permit removing every current admin. New members are regular members.", defaultValue = "false")
    @PluginProperty(group = "advanced")
    private Property<Boolean> allowRemoveLastAdmin;
    @Schema(title = "Maximum users to scan", description = "Between 1 and 10000. An incomplete user inventory fails before writing.", defaultValue = "10000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxUsers;
    @Schema(title = "Maximum members to scan", description = "Between 1 and 10000. An incomplete membership inventory fails before writing.", defaultValue = "10000")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxMembers;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, true)) {
            var team = AccessApi.required(context, teamId, "teamId");
            var path = Memberships.path(team);
            if (handles == null) {
                throw new IllegalArgumentException("`handles` must be an explicit list");
            }
            List<?> rHandles = AccessApi.explicitList(context, handles, "handles");
            var desiredHandles = new LinkedHashSet<String>();
            for (var handle : rHandles) {
                var normalized = AccessApi.required(handle, "handle");
                if (!desiredHandles.add(normalized)) {
                    throw new IllegalArgumentException("`handles` contains duplicates");
                }
            }
            var usersLimit = AccessApi.range(context, maxUsers, 10000, 10000, "maxUsers");
            var membersLimit = AccessApi.range(context, maxMembers, 10000, 10000, "maxMembers");
            var preview = AccessApi.flag(context, plan);
            var allow = AccessApi.flag(context, allowRemoveLastAdmin);
            var users = AccessApi.collect(client, "/api/v2/users", Map.of("sort", "handle", "sort_dir", "asc"), 100, usersLimit);
            if (users.truncated()) {
                throw new IllegalStateException("User inventory exceeds maxUsers; no changes made");
            }
            var idByHandle = new LinkedHashMap<String, String>();
            var handleById = new LinkedHashMap<String, String>();
            var disabled = new LinkedHashSet<String>();
            for (var user : users.rows()) {
                var id = AccessApi.required(user.get("id"), "user id");
                var attributes = AccessApi.object(user.get("attributes"));
                var handle = AccessApi.required(attributes.get("handle"), "user handle");
                if (idByHandle.put(handle, id) != null) {
                    throw new IllegalStateException("User inventory contains ambiguous handles; no changes made");
                }
                handleById.put(id, handle);
                if (Boolean.TRUE.equals(attributes.get("disabled"))) {
                    disabled.add(handle);
                }
            }
            var desiredIds = new LinkedHashSet<String>();
            for (var handle : desiredHandles) {
                if (!idByHandle.containsKey(handle) || disabled.contains(handle)) {
                    throw new IllegalArgumentException("A desired handle is unknown or disabled; no changes made");
                }
                desiredIds.add(idByHandle.get(handle));
            }
            var current = Memberships.read(client, path, membersLimit);
            for (var id : current.keySet()) {
                if (!handleById.containsKey(id)) {
                    throw new IllegalStateException("A team member is missing from the user inventory; no changes made");
                }
            }
            var added = new LinkedHashSet<String>(desiredIds);
            added.removeAll(current.keySet());
            var removed = new LinkedHashSet<String>(current.keySet());
            removed.removeAll(desiredIds);
            var unchanged = new LinkedHashSet<String>(desiredIds);
            unchanged.retainAll(current.keySet());
            Memberships.protectAdmins(current, removed, allow);
            var diff = new ArrayList<Change>();
            added.forEach(id -> diff.add(Change.builder().userId(id).handle(handleById.get(id)).before(false).after(true).build()));
            removed.forEach(id -> diff.add(Change.builder().userId(id).handle(handleById.get(id)).before(true).after(false).build()));
            if (!preview) {
                for (var id : added) {
                    AccessApi.request(client, "POST", path, Map.of(), Memberships.create(id));
                }
                for (var id : removed) {
                    // Re-read before every removal. Datadog has no conditional membership delete.
                    Memberships.protectAdmins(Memberships.read(client, path, membersLimit), Set.of(id), allow);
                    AccessApi.delete(client, path + "/" + AccessApi.segment(id), true);
                }
            }
            return Output.builder().teamId(team).plan(preview).status(diff.isEmpty() ? Status.UNCHANGED : Status.UPDATED)
                .added(added.stream().map(handleById::get).toList()).removed(removed.stream().map(handleById::get).toList())
                .unchanged(unchanged.stream().map(handleById::get).toList()).diff(diff).build();
        }
    }

    public enum Status { UPDATED, UNCHANGED }

    @Builder
    @Getter
    public static class Change {
        @Schema(title = "User identifier")
        private final String userId;
        @Schema(title = "User handle")
        private final String handle;
        @Schema(title = "Member before sync")
        private final Boolean before;
        @Schema(title = "Member after sync")
        private final Boolean after;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Team identifier")
        private final String teamId;
        @Schema(title = "Sync result", description = "UPDATED or UNCHANGED. With plan this describes the planned result.")
        private final Status status;
        @Schema(title = "Plan mode")
        private final Boolean plan;
        @Schema(title = "Added handles", description = "Planned additions when plan is true.")
        private final List<String> added;
        @Schema(title = "Removed handles", description = "Planned removals when plan is true.")
        private final List<String> removed;
        @Schema(title = "Unchanged handles")
        private final List<String> unchanged;
        @Schema(title = "Membership field changes")
        private final List<Change> diff;
    }
}
