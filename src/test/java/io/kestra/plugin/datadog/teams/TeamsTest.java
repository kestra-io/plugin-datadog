package io.kestra.plugin.datadog.teams;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.OrganizationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TeamsTest extends OrganizationTestSupport {
    private static final String PATH = "/api/v2/team/team-id/memberships";

    private Map<String, Object> user(String id, String handle, boolean disabled) {
        return resource(id, "users", Map.of("handle", handle, "disabled", disabled));
    }

    private Map<String, Object> member(String id, boolean admin) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("role", admin ? "admin" : null);
        return Map.of("id", "membership-" + id, "type", "team_memberships", "attributes", attributes,
            "relationships", Map.of("user", Map.of("data", Map.of("id", id, "type", "users"))));
    }

    private String members(java.util.List<?> values) throws Exception {
        return json(Map.of("data", values, "meta", Map.of("pagination", Map.of("total", values.size()))));
    }

    private void users() throws Exception {
        server.reply("GET", "/api/v2/users", 200, page(java.util.List.of(user("admin", "admin@example.com", false), user("old", "old@example.com", false), user("new", "new@example.com", false)), 3));
    }

    @Test
    void syncDiffAndSecondRunAreIdempotent() throws Exception {
        users();
        String initial = members(java.util.List.of(member("admin", true), member("old", false)));
        String finalState = members(java.util.List.of(member("admin", true), member("new", false)));
        server.reply("GET", PATH, 200, initial).reply("GET", PATH, 200, initial).reply("GET", PATH, 200, finalState);
        server.reply("POST", PATH, 200, json(Map.of("data", member("new", false))));
        server.reply("DELETE", PATH + "/old", 204, "");
        var task = connection(SyncMembers.builder()).teamId(Property.ofValue("team-id"))
            .handles(Property.ofValue(java.util.List.of("admin@example.com", "new@example.com"))).build();
        var first = task.run(runContext());
        assertEquals(SyncMembers.Status.UPDATED, first.getStatus());
        assertEquals(java.util.List.of("new@example.com"), first.getAdded());
        assertEquals(java.util.List.of("old@example.com"), first.getRemoved());
        assertEquals(java.util.List.of("admin@example.com"), first.getUnchanged());
        assertEquals(2, first.getDiff().size());
        assertFalse(first.getDiff().get(0).getBefore());
        assertTrue(first.getDiff().get(0).getAfter());
        var add = server.requests.stream().filter(request -> request.method().equals("POST")).findFirst().orElseThrow();
        assertFalse(add.body().contains("\"role\":\"admin\""));
        assertTrue(add.body().contains("\"id\":\"new\""));
        assertFalse(add.body().contains("membership-new"));
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests,
            "v2:ListUsers", "v2:GetTeamMemberships", "v2:CreateTeamMembership", "v2:DeleteTeamMembership");
        server.requests.clear();
        var second = task.run(runContext());
        assertEquals(SyncMembers.Status.UNCHANGED, second.getStatus());
        assertTrue(second.getDiff().isEmpty());
        assertTrue(second.getAdded().isEmpty());
        assertTrue(second.getRemoved().isEmpty());
        io.kestra.plugin.datadog.contract.OpenApiContract.assertRequests(server.requests,
            "v2:ListUsers", "v2:GetTeamMemberships");
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void planReportsChangesWithoutWrites() throws Exception {
        users();
        server.reply("GET", PATH, 200, members(java.util.List.of(member("admin", true), member("old", false))));
        var output = connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of("admin@example.com", "new@example.com")))
            .plan(Property.ofValue(true)).build().run(runContext());
        assertEquals(java.util.List.of("new@example.com"), output.getAdded());
        assertEquals(java.util.List.of("old@example.com"), output.getRemoved());
        assertTrue(output.getPlan());
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void lastAdminGuardRunsBeforeAnyWriteAndCanBeExplicitlyOverridden() throws Exception {
        users();
        server.reply("GET", PATH, 200, members(java.util.List.of(member("admin", true))));
        var builder = connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of("new@example.com")));
        assertThrows(IllegalArgumentException.class, () -> builder.build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
        var output = builder.allowRemoveLastAdmin(Property.ofValue(true)).plan(Property.ofValue(true)).build().run(runContext());
        assertEquals(java.util.List.of("admin@example.com"), output.getRemoved());
        assertThrows(IllegalArgumentException.class, () -> connection(RemoveMember.builder()).teamId(Property.ofValue("team-id"))
            .userId(Property.ofValue("admin")).build().run(runContext()));
    }

    @Test
    void guardIsRecheckedWhenAnAdminDisappearsBetweenReads() throws Exception {
        users();
        server.reply("GET", PATH, 200, members(java.util.List.of(member("admin", true), member("old", true))));
        server.reply("GET", PATH, 200, members(java.util.List.of(member("old", true))));
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id"))
            .handles(Property.ofValue(java.util.List.of("admin@example.com"))).build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void missingAndInvalidInputsNeverCallDatadog() {
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).handles(Property.ofValue(java.util.List.of())).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(null)).build().run(runContext()));
        for (java.util.List<String> handles : java.util.List.of(java.util.List.of(" "), java.util.List.of("same", "same"))) {
            assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(handles)).build().run(runContext()));
        }
        for (int limit : new int[]{0, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).maxUsers(Property.ofValue(limit)).build().run(runContext()));
            assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).maxMembers(Property.ofValue(limit)).build().run(runContext()));
        }
        assertThrows(IllegalArgumentException.class, () -> connection(RemoveMember.builder()).userId(Property.ofValue("id")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(RemoveMember.builder()).teamId(Property.ofValue("id")).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void unknownDisabledAmbiguousAndMissingInventoryUsersFailBeforeWrites() throws Exception {
        users();
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of("unknown"))).build().run(runContext()));
        server.reply("GET", "/api/v2/users", 200, page(java.util.List.of(user("u", "disabled", true)), 1));
        assertThrows(IllegalArgumentException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of("disabled"))).build().run(runContext()));
        server.reply("GET", "/api/v2/users", 200, page(java.util.List.of(user("u1", "duplicate", false), user("u2", "duplicate", false)), 2));
        assertThrows(IllegalStateException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).build().run(runContext()));
        users();
        server.reply("GET", PATH, 200, members(java.util.List.of(member("unlisted", false))));
        assertThrows(IllegalStateException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void fullInventoriesPageAndTruncatedInventoriesBlockSync() throws Exception {
        var hundredUsers = IntStream.range(0, 100).mapToObj(i -> user("u" + i, "handle" + i, false)).toList();
        var hundredMembers = IntStream.range(0, 100).mapToObj(i -> member("u" + i, i == 0)).toList();
        server.reply("GET", "/api/v2/users", 200, page(hundredUsers, 101));
        server.reply("GET", "/api/v2/users", 200, page(java.util.List.of(user("u100", "handle100", false)), 101));
        server.reply("GET", PATH, 200, json(Map.of("data", hundredMembers, "meta", Map.of("pagination", Map.of("total", 101)))));
        server.reply("GET", PATH, 200, json(Map.of("data", java.util.List.of(member("u100", false)), "meta", Map.of("pagination", Map.of("total", 101)))));
        var desired = IntStream.range(0, 101).mapToObj(i -> "handle" + i).toList();
        var output = connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(desired)).build().run(runContext());
        assertEquals(101, output.getUnchanged().size());
        assertEquals(SyncMembers.Status.UNCHANGED, output.getStatus());
        assertEquals(4, server.requests.size());
        server.reply("GET", "/api/v2/users", 200, page(hundredUsers, 101));
        assertThrows(IllegalStateException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).maxUsers(Property.ofValue(100)).build().run(runContext()));
        users();
        server.reply("GET", PATH, 200, members(java.util.List.of(member("admin", true), member("old", false))));
        assertThrows(IllegalStateException.class, () -> connection(SyncMembers.builder()).teamId(Property.ofValue("team-id")).handles(Property.ofValue(java.util.List.of())).maxMembers(Property.ofValue(1)).build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(request -> request.method().equals("GET")));
    }

    @Test
    void removeIsSingleUserWithMissingObjectSemantics() throws Exception {
        server.reply("GET", PATH, 200, members(java.util.List.of(member("admin", true), member("old", false))));
        server.reply("DELETE", PATH + "/old", 204, "");
        assertTrue(connection(RemoveMember.builder()).teamId(Property.ofValue("team-id")).userId(Property.ofValue("old")).build().run(runContext()).getRemoved());
        assertEquals(PATH + "/old", server.requests.get(1).path());
        server.reply("DELETE", PATH + "/old", 404, "{}");
        assertFalse(connection(RemoveMember.builder()).teamId(Property.ofValue("team-id")).userId(Property.ofValue("old")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()).getRemoved());
        server.reply("GET", PATH, 403, "{}");
        assertThrows(DatadogClient.DatadogException.class, () -> connection(RemoveMember.builder()).teamId(Property.ofValue("team-id")).userId(Property.ofValue("old")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void teamListPagesAndRedacts() throws Exception {
        server.reply("GET", "/api/v2/team", 200, page(java.util.List.of(resource("a", "team", Map.of("name", "A", "handle", "a", "webhook_url", VALUE))), 2));
        server.reply("GET", "/api/v2/team", 200, page(java.util.List.of(resource("b", "team", Map.of("name", "B", "handle", "b"))), 2));
        var output = connection(List.builder()).pageSize(Property.ofValue(1)).keyword(Property.ofValue("platform")).build().run(runContext());
        noCredential(output);
        assertEquals(2, output.getSize());
        assertTrue(server.requests.get(0).query().contains("filter%5Bkeyword%5D=platform"));
        assertTrue(server.requests.get(1).query().contains("page%5Bnumber%5D=1"));
        server.reply("GET", "/api/v2/team", 200, page(java.util.List.of(resource("a", "team", Map.of("webhook_url", VALUE))), 1));
        assertTrue(json(connection(List.builder()).includeSecrets(Property.ofValue(true)).build().run(runContext())).contains(VALUE));
    }
}
