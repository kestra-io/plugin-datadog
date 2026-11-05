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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Datadog operationIds: v2:ListUsers, v2:GetTeamMemberships, v2:CreateTeamMembership and v2:DeleteTeamMembership. */
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
        dryRun: true
    """))
public class SyncMembers extends AbstractDatadogTask implements RunnableTask<SyncMembers.Output> {
    @NotNull
    @Schema(title = "Team identifier", description = "ID of the single team to reconcile.")
    private Property<String> teamId;
    @NotNull
    @Schema(title = "Desired user handles", description = "Exact Datadog user handles. An explicit empty list requests removal of every member. Unknown, disabled or duplicate handles fail validation.")
    private Property<List<String>> handles;
    @Schema(title = "Dry run", description = "Return the planned changes without writing. The last-admin guard is still checked.", defaultValue = "false")
    private Property<Boolean> dryRun;
    @Schema(title = "Allow removing the last admin", description = "Permit removing every current admin. New members are regular members.", defaultValue = "false")
    private Property<Boolean> allowRemoveLastAdmin;
    @Schema(title = "Maximum users to scan", description = "Between 1 and 10000. An incomplete user inventory fails before writing.", defaultValue = "10000")
    private Property<Integer> maxUsers;
    @Schema(title = "Maximum members to scan", description = "Between 1 and 10000. An incomplete membership inventory fails before writing.", defaultValue = "10000")
    private Property<Integer> maxMembers;

    @Override
    public Output run(RunContext context) throws Exception {
        String team = AccessApi.required(context, teamId, "teamId");
        String path = Memberships.path(team);
        if (handles == null) {
            throw new IllegalArgumentException("`handles` must be an explicit list");
        }
        List<?> renderedHandles = AccessApi.explicitList(context, handles, "handles");
        Set<String> desiredHandles = new LinkedHashSet<>();
        for (Object handle : renderedHandles) {
            String normalized = AccessApi.required(handle, "handle");
            if (!desiredHandles.add(normalized)) {
                throw new IllegalArgumentException("`handles` contains duplicates");
            }
        }
        int usersLimit = AccessApi.range(context, maxUsers, 10000, 10000, "maxUsers");
        int membersLimit = AccessApi.range(context, maxMembers, 10000, 10000, "maxMembers");
        boolean dry = AccessApi.flag(context, dryRun);
        boolean allow = AccessApi.flag(context, allowRemoveLastAdmin);
        DatadogClient client = client(context, true);
        AccessApi.Page users = AccessApi.collect(client, "/api/v2/users", Map.of("sort", "handle", "sort_dir", "asc"), 100, usersLimit);
        if (users.truncated()) {
            throw new IllegalStateException("User inventory exceeds maxUsers; no changes made");
        }
        Map<String, String> idByHandle = new LinkedHashMap<>();
        Map<String, String> handleById = new LinkedHashMap<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Map<String, Object> user : users.rows()) {
            String id = AccessApi.required(user.get("id"), "user id");
            Map<String, Object> attributes = AccessApi.object(user.get("attributes"));
            String handle = AccessApi.required(attributes.get("handle"), "user handle");
            if (idByHandle.put(handle, id) != null) {
                throw new IllegalStateException("User inventory contains ambiguous handles; no changes made");
            }
            handleById.put(id, handle);
            if (Boolean.TRUE.equals(attributes.get("disabled"))) {
                disabled.add(handle);
            }
        }
        Set<String> desiredIds = new LinkedHashSet<>();
        for (String handle : desiredHandles) {
            if (!idByHandle.containsKey(handle) || disabled.contains(handle)) {
                throw new IllegalArgumentException("A desired handle is unknown or disabled; no changes made");
            }
            desiredIds.add(idByHandle.get(handle));
        }
        Map<String, Memberships.Member> current = Memberships.read(client, path, membersLimit);
        for (String id : current.keySet()) {
            if (!handleById.containsKey(id)) {
                throw new IllegalStateException("A team member is missing from the user inventory; no changes made");
            }
        }
        Set<String> added = new LinkedHashSet<>(desiredIds);
        added.removeAll(current.keySet());
        Set<String> removed = new LinkedHashSet<>(current.keySet());
        removed.removeAll(desiredIds);
        Set<String> unchanged = new LinkedHashSet<>(desiredIds);
        unchanged.retainAll(current.keySet());
        Memberships.protectAdmins(current, removed, allow);
        List<Change> diff = new ArrayList<>();
        added.forEach(id -> diff.add(Change.builder().userId(id).handle(handleById.get(id)).before(false).after(true).build()));
        removed.forEach(id -> diff.add(Change.builder().userId(id).handle(handleById.get(id)).before(true).after(false).build()));
        if (!dry) {
            for (String id : added) {
                AccessApi.request(client, "POST", path, Map.of(), Memberships.create(id));
            }
            for (String id : removed) {
                // Re-read before every removal. Datadog has no conditional membership delete.
                Memberships.protectAdmins(Memberships.read(client, path, membersLimit), Set.of(id), allow);
                AccessApi.delete(client, path + "/" + AccessApi.segment(id), true);
            }
        }
        return Output.builder().teamId(team).dryRun(dry).status(diff.isEmpty() ? Status.UNCHANGED : Status.UPDATED)
            .added(added.stream().map(handleById::get).toList()).removed(removed.stream().map(handleById::get).toList())
            .unchanged(unchanged.stream().map(handleById::get).toList()).diff(diff).build();
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
        @Schema(title = "Sync result", description = "UPDATED or UNCHANGED. With dryRun this describes the planned result.")
        private final Status status;
        @Schema(title = "Dry run")
        private final Boolean dryRun;
        @Schema(title = "Added handles", description = "Planned additions when dryRun is true.")
        private final List<String> added;
        @Schema(title = "Removed handles", description = "Planned removals when dryRun is true.")
        private final List<String> removed;
        @Schema(title = "Unchanged handles")
        private final List<String> unchanged;
        @Schema(title = "Membership field changes")
        private final List<Change> diff;
    }
}
