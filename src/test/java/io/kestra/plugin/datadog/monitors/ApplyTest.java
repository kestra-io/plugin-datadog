package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.contract.OpenApiContract;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ApplyTest extends AbstractDatadogTest {
    static final String QUERY = "avg(last_5m):avg:system.cpu.user{env:prod} > 80";
    static final String TAG = "managed_by:kestra:cpu-prod";

    static Map<String, Object> definition() {
        return new LinkedHashMap<>(Map.of("name", "CPU usage", "type", "query alert", "query", QUERY,
            "tags", List.of(TAG, "env:prod"), "options", Map.of("thresholds", Map.of("critical", 80))));
    }

    private Apply.ApplyBuilder<?, ?> task() throws Exception {
        return Apply.builder().id(IdUtils.create()).type(Apply.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(definition())));
    }

    private void existing(Map<String, Object> fields) throws Exception {
        var response = new LinkedHashMap<String, Object>(fields);
        response.put("id", 123);
        var json = JacksonMapper.ofJson().writeValueAsString(response);
        server.reply("GET", "/api/v1/monitor", 200, "[" + json + "]");
        server.reply("GET", "/api/v1/monitor/123", 200, json);
    }

    @Test
    void createsThenSecondRunIsUnchanged() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        var response = definition();
        response.put("id", 123);
        response.put("created", "2026-01-01T00:00:00Z");
        response.put("options", Map.of("thresholds", Map.of("critical", 80.0), "include_tags", true));
        var json = JacksonMapper.ofJson().writeValueAsString(response);
        server.reply("POST", "/api/v1/monitor", 200, json);
        var apply = task().build();
        var first = apply.run(runContext()).getResults().getFirst();
        assertEquals(Apply.Action.CREATED, first.getAction());
        assertEquals(123L, first.getMonitorId());
        assertFalse(first.getDiff().isEmpty());
        assertEquals(List.of("GET", "POST", "POST"), server.requests.stream().map(r -> r.method()).toList());
        assertEquals("/api/v1/monitor/validate", server.requests.get(1).path());
        assertEquals(definition(), server.requests.get(2).json());

        existing(response);
        var second = apply.run(runContext()).getResults().getFirst();
        assertEquals(Apply.Action.UNCHANGED, second.getAction());
        assertTrue(second.getDiff().isEmpty());
        assertEquals(1, server.requests.stream().filter(r -> r.path().equals("/api/v1/monitor") && r.method().equals("POST")).count());
        assertEquals(0, server.requests.stream().filter(r -> r.method().equals("PUT")).count());
        OpenApiContract.assertRequests(server.requests,
            "v1:ListMonitors", "v1:GetMonitor", "v1:ValidateMonitor", "v1:ValidateExistingMonitor", "v1:CreateMonitor");
        OpenApiContract.of("v1:CreateMonitor").assertResponse(200, json);
    }

    @Test
    void updatesQueryAndPreservesUnmanagedOptionsThenIsUnchanged() throws Exception {
        var old = definition();
        old.put("query", "avg(last_5m):avg:system.cpu.user{env:prod} > 90");
        old.put("options", Map.of("thresholds", Map.of("critical", 90, "warning", 70), "include_tags", true));
        existing(old);
        var response = definition();
        response.put("id", 123);
        server.reply("PUT", "/api/v1/monitor/123", 200, JacksonMapper.ofJson().writeValueAsString(response));
        var apply = task().build();
        var first = apply.run(runContext()).getResults().getFirst();
        assertEquals(Apply.Action.UPDATED, first.getAction());
        assertEquals(Set.of("/query", "/options/thresholds/critical"), first.getDiff().stream().map(MonitorDefinitions.Change::getField).collect(Collectors.toSet()));
        var body = server.requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow().json();
        assertEquals(Map.of("thresholds", Map.of("critical", 80, "warning", 70), "include_tags", true), body.get("options"));
        assertFalse(body.containsKey("id"));
        existing(body);
        var second = apply.run(runContext()).getResults().getFirst();
        assertEquals(Apply.Action.UNCHANGED, second.getAction());
        assertEquals(1, server.requests.stream().filter(r -> r.method().equals("PUT")).count());
        OpenApiContract.assertRequests(server.requests,
            "v1:ListMonitors", "v1:GetMonitor", "v1:ValidateExistingMonitor", "v1:UpdateMonitor");
    }

    @Test
    void serverNoiseAndNumericRepresentationsDoNotChangeTheMonitor() throws Exception {
        var noisy = definition();
        noisy.put("created", "2026-01-01T00:00:00Z");
        noisy.put("modified", "2026-01-02T00:00:00Z");
        noisy.put("deleted", null);
        noisy.put("creator", Map.of("name", "Example", "email", "example@example.org"));
        noisy.put("multi", false);
        noisy.put("overall_state", "Alert");
        noisy.put("overall_state_modified", "2026-01-02T00:00:00Z");
        noisy.put("state", Map.of("groups", Map.of()));
        noisy.put("matching_downtimes", List.of());
        noisy.put("options", Map.of("thresholds", Map.of("critical", 80.0), "aggregation", Map.of("type", "count"), "device_ids", List.of("laptop_large")));
        existing(noisy);
        assertEquals(Apply.Action.UNCHANGED, task().build().run(runContext()).getResults().getFirst().getAction());
    }

    @Test
    void tagOrderCountsAsAChangeAndPlanUsesOnlyReads() throws Exception {
        var old = definition();
        old.put("tags", List.of("env:prod", TAG));
        existing(old);
        var output = task().plan(Property.ofValue(true)).build().run(runContext());
        assertTrue(output.getPlan());
        var result = output.getResults().getFirst();
        assertEquals(Apply.Action.UPDATED, result.getAction());
        assertEquals("/tags", result.getDiff().getFirst().getField());
        assertEquals(List.of("GET", "GET"), server.requests.stream().map(r -> r.method()).toList());
    }

    @Test
    void plansCreationWithoutAnIdAndSkipsRemoteValidation() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        var result = task().plan(Property.ofValue(true)).build().run(runContext()).getResults().getFirst();
        assertEquals(Apply.Action.CREATED, result.getAction());
        assertNull(result.getMonitorId());
        assertEquals("GET", server.only().method());
    }

    @Test
    void update404FailsWithoutRecreating() throws Exception {
        var old = definition();
        old.put("query", "avg(last_5m):avg:system.cpu.user{env:prod} > 90");
        existing(old);
        server.reply("PUT", "/api/v1/monitor/123", 404, "{\"errors\":[\"Monitor not found\"]}");
        var error = assertThrows(DatadogClient.DatadogException.class, () -> task().build().run(runContext()));
        assertEquals(404, error.getStatus());
        assertTrue(server.requests.stream().noneMatch(r -> r.method().equals("POST") && r.path().equals("/api/v1/monitor")));
    }

    @Test
    void refusesDuplicateRemoteKeys() throws Exception {
        var first = definition();
        first.put("id", 123);
        var second = definition();
        second.put("id", 124);
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(List.of(first, second)));
        assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
        assertEquals("GET", server.only().method());
    }

    @Test
    void validatesWholeBatchBeforeFirstWrite() throws Exception {
        var second = definition();
        second.put("tags", List.of("managed_by:kestra:other"));
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        server.reply("POST", "/api/v1/monitor/validate", 200, "{}");
        server.reply("POST", "/api/v1/monitor/validate", 400, "{\"errors\":[\"Invalid query\"]}");
        assertThrows(DatadogClient.DatadogException.class, () -> task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(List.of(definition(), second)))).build().run(runContext()));
        assertTrue(server.requests.stream().noneMatch(r -> r.method().equals("POST") && r.path().equals("/api/v1/monitor")));
    }

    @Test
    void rejectsInvalidInputsBeforeAnyRequest() throws Exception {
        for (var input : List.of("[]", "null", "[42]", "{broken", "---\na: b\n---\nc: d", "type: query alert", "query: test", "name: cpu\ntype: query alert\nquery: test", "name: cpu\ntype: wrong\nquery: test\ntags: [managed_by:kestra:a]")) {
            assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue(input)).build().run(runContext()), input);
        }
        var noName = definition();
        noName.remove("name");
        assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(noName))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(List.of(definition(), definition())))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().from(Property.ofValue("kestra:///input.yaml")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().definitions(null).from(Property.ofValue("https://example.org/input.yaml")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().definitions(null).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue("x".repeat(10 * 1024 * 1024 + 1))).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void validatesKeyShapeAndDefinitionCount() throws Exception {
        for (var tags : List.of(List.of("managed_by:kestra:"), List.of("managed_by:kestra:Upper"), List.of(TAG, "managed_by:kestra:second"))) {
            var invalid = definition();
            invalid.put("tags", tags);
            assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(invalid))).build().run(runContext()));
        }
        var tooMany = Collections.nCopies(1001, definition());
        assertThrows(IllegalArgumentException.class, () -> task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(tooMany))).build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void abortsWhenManagementTagChangesDuringDiscovery() throws Exception {
        var listed = definition();
        listed.put("id", 123);
        server.reply("GET", "/api/v1/monitor", 200, JacksonMapper.ofJson().writeValueAsString(List.of(listed)));
        var changed = definition();
        changed.put("id", 123);
        changed.put("tags", List.of("env:prod"));
        server.reply("GET", "/api/v1/monitor/123", 200, JacksonMapper.ofJson().writeValueAsString(changed));
        assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
        assertTrue(server.requests.stream().allMatch(r -> r.method().equals("GET")));
    }

    @Test
    void readsYamlFromInternalStorageAndStoresIonResults() throws Exception {
        var context = runContext();
        var file = context.workingDir().createTempFile(".yaml");
        JacksonMapper.ofYaml().writeValue(file.toFile(), List.of(definition()));
        var uri = context.storage().putFile(file.toFile());
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        var output = task().definitions(null).from(Property.ofValue(uri.toString())).plan(Property.ofValue(true)).fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertNull(output.getResults());
        assertEquals(1, output.getSize());
        try (var input = context.storage().getFile(output.getUri())) {
            Map<?, ?> row = JacksonMapper.ofIon().readValue(input, Map.class);
            assertEquals("CREATED", row.get("action"));
            assertEquals(TAG, row.get("key"));
        }
    }

    @Test
    void diffFiltersUrlsAndCredentialFields() throws Exception {
        var desired = definition();
        desired.put("message", "Notify https://hooks.example.org/private-token");
        desired.put("options", Map.of("password", "private-value"));
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        var output = task().definitions(Property.ofValue(JacksonMapper.ofJson().writeValueAsString(desired))).plan(Property.ofValue(true)).build().run(runContext());
        var json = JacksonMapper.ofJson().writeValueAsString(output);
        assertFalse(json.contains("private-token"));
        assertFalse(json.contains("private-value"));
        assertTrue(json.contains("REDACTED"));
    }

    @Test
    void partialDiffAndMergeIgnoreUnownedAndEmptyObjects() {
        var current = Map.<String, Object>of("options", Map.of("thresholds", Map.of("critical", 80, "warning", 70), "include_tags", true));
        assertTrue(MonitorDefinitions.diff(current, Map.of("options", Map.of())).isEmpty());
        assertEquals(current, MonitorDefinitions.merge(current, Map.of("options", Map.of())));
        assertEquals(1, MonitorDefinitions.diff(current, Map.of("options", Map.of("thresholds", Map.of("critical", 81)))).size());
    }
}
