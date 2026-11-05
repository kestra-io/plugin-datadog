package io.kestra.plugin.datadog.teams;

import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.AccessApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Schema helpers for GetTeamMemberships and CreateTeamMembership. */
final class Memberships {
    private Memberships() {
    }

    static String path(String teamId) {
        return "/api/v2/team/" + AccessApi.segment(teamId) + "/memberships";
    }

    record Member(String userId, boolean admin) {
    }

    static Map<String, Member> read(DatadogClient client, String path, int max) throws Exception {
        var page = AccessApi.collect(client, path, Map.of("sort", "handle"), 100, max);
        if (page.truncated()) {
            throw new IllegalStateException("Team membership inventory exceeds maxMembers; no writes were started for this snapshot");
        }
        var members = new LinkedHashMap<String, Member>();
        for (var row : page.rows()) {
            var relationships = AccessApi.object(row.get("relationships"));
            var user = AccessApi.object(AccessApi.object(relationships.get("user")).get("data"));
            var id = AccessApi.required(user.get("id"), "membership user id");
            var role = AccessApi.object(row.get("attributes")).get("role");
            if (role != null && !"admin".equals(role)) {
                throw new IllegalStateException("Datadog returned an unknown membership role");
            }
            if (members.put(id, new Member(id, "admin".equals(role))) != null) {
                throw new IllegalStateException("Datadog returned duplicate membership users");
            }
        }
        return members;
    }

    static void protectAdmins(Map<String, Member> members, Set<String> removed, boolean allow) {
        boolean removesAdmin = members.values().stream().anyMatch(member -> member.admin() && removed.contains(member.userId()));
        boolean retainsAdmin = members.values().stream().anyMatch(member -> member.admin() && !removed.contains(member.userId()));
        if (!allow && removesAdmin && !retainsAdmin) {
            throw new IllegalArgumentException("Refusing to remove the last team admin; set allowRemoveLastAdmin to permit it");
        }
    }

    static Map<String, Object> create(String userId) {
        // Omitting the optional role creates a regular member. The only non-null API role is admin.
        return Map.of("data", Map.of("type", "team_memberships", "attributes", Map.of(),
            "relationships", Map.of("user", Map.of("data", Map.of("id", userId, "type", "users")))));
    }
}
