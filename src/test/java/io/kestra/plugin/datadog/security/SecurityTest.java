package io.kestra.plugin.datadog.security;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.kestra.plugin.datadog.keys.OrganizationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityTest extends OrganizationTestSupport {
    private static final String PATH = "/api/v2/security_monitoring/signals/search";
    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2026-01-02T00:00:00Z";

    private SignalsSearch.SignalsSearchBuilder<?, ?> search() {
        return connection(SignalsSearch.builder()).from(Property.ofValue(FROM)).to(Property.ofValue(TO));
    }

    private String signalPage(String id, String cursor) throws Exception {
        return json(Map.of("data", List.of(resource(id, "signal", Map.of("timestamp", FROM, "message", "alert", "attributes", Map.of("token", VALUE)))),
            "meta", Map.of("status", "done", "page", cursor == null ? Map.of() : Map.of("after", cursor))));
    }

    @Test
    void searchFollowsCursorAndRedactsNestedSignalFields() throws Exception {
        server.reply("POST", PATH, 200, signalPage("one", "cursor-two")).reply("POST", PATH, 200, signalPage("two", null));
        var output = search().query(Property.ofValue("status:high")).sort(Property.ofValue(SignalsSearch.Sort.NEWEST)).build().run(runContext());
        assertEquals(2, output.getSize());
        assertFalse(output.getTruncated());
        noCredential(output);
        assertEquals(Map.of("from", FROM, "to", TO, "query", "status:high"), server.requests.get(0).json().get("filter"));
        assertEquals("-timestamp", server.requests.get(0).json().get("sort"));
        assertEquals("cursor-two", AccessApi.object(server.requests.get(1).json().get("page")).get("cursor"));
        var included = search().includeSecrets(Property.ofValue(true)).build().run(runContext());
        assertTrue(json(included).contains(VALUE));
    }

    @Test
    void searchReportsLimitsAndRejectsRepeatedCursorsAndPartialResults() throws Exception {
        server.reply("POST", PATH, 200, signalPage("one", "repeat"));
        assertTrue(search().maxResults(Property.ofValue(1)).build().run(runContext()).getTruncated());
        assertThrows(IllegalStateException.class, () -> search().build().run(runContext()));
        server.reply("POST", PATH, 200, "{\"data\":[],\"meta\":{\"status\":\"timeout\"}}");
        assertThrows(IllegalStateException.class, () -> search().build().run(runContext()));
        server.reply("POST", PATH, 200, "{\"data\":[],\"meta\":{\"page\":{\"after\":\"x\"}}}");
        assertThrows(IllegalStateException.class, () -> search().build().run(runContext()));
    }

    @Test
    void searchRequiresAValidWindowAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> connection(SignalsSearch.builder()).to(Property.ofValue(TO)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(SignalsSearch.builder()).from(Property.ofValue(FROM)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> search().from(Property.ofValue("now-1h")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> search().to(Property.ofValue(FROM)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> search().pageSize(Property.ofValue(101)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> search().maxResults(Property.ofValue(0)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void triageMapsApiEnumsAndAssigneeShapeWithoutReturningComment() throws Exception {
        server.reply("PATCH", "/api/v2/security_monitoring/signals/signal/update", 200, json(Map.of("data", resource("signal", "signal_metadata", Map.of("assignee", Map.of("uuid", "user-id"), "state", "archived", "incident_ids", List.of(), "archive_comment", VALUE)))));
        var output = connection(TriageSignal.builder()).signalId(Property.ofValue("signal"))
            .state(Property.ofValue(TriageSignal.State.ARCHIVED)).assigneeId(Property.ofValue("user-id"))
            .archiveReason(Property.ofValue(TriageSignal.ArchiveReason.FALSE_POSITIVE)).archiveComment(Property.ofValue(VALUE)).build().run(runContext());
        assertTrue(output.getUpdated());
        noCredential(output);
        Map<String, Object> data = AccessApi.object(server.only().json().get("data"));
        assertEquals("signal_metadata", data.get("type"));
        assertEquals(Map.of("state", "archived", "assignee", Map.of("uuid", "user-id"), "archive_reason", "false_positive", "archive_comment", VALUE), data.get("attributes"));
    }

    @Test
    void triageValidatesBeforeCalls() {
        assertThrows(IllegalArgumentException.class, () -> connection(TriageSignal.builder()).state(Property.ofValue(TriageSignal.State.OPEN)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(TriageSignal.builder()).signalId(Property.ofValue("signal")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(TriageSignal.builder()).signalId(Property.ofValue("signal")).assigneeId(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(TriageSignal.builder()).signalId(Property.ofValue("signal")).state(Property.ofValue(TriageSignal.State.OPEN)).archiveComment(Property.ofValue("comment")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> connection(TriageSignal.builder()).signalId(Property.ofValue("signal")).archiveReason(Property.ofValue(TriageSignal.ArchiveReason.OTHER)).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }
}
