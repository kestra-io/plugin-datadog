package io.kestra.plugin.datadog.ddsql;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest extends AbstractDatadogTest {
    private static final String EXECUTE = "/api/v2/ddsql/query/tabular";
    private static final String FETCH = EXECUTE + "/fetch";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private Query.QueryBuilder<?, ?> task() {
        return Query.builder().id(IdUtils.create()).type(Query.class.getName()).apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url())).query(Property.ofValue("SELECT 1"))
            .from(Property.ofValue(FROM)).to(Property.ofValue(FROM.plusSeconds(3600))).pollDelay(Property.ofValue(Duration.ofMillis(1)));
    }

    private static String response(String attributes) {
        return "{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000000\",\"type\":\"ddsql_query_response\",\"attributes\":" + attributes + "},\"meta\":{\"elapsed\":10,\"request_id\":\"req-fixture\"}}";
    }

    private static String running(String token) {
        return response("{\"state\":\"running\",\"query_id\":\"" + token + "\"}");
    }

    private static String completed() {
        return response("{\"state\":\"completed\",\"columns\":[{\"name\":\"host_count\",\"type\":\"BIGINT\",\"values\":[9007199254740993,null]}],\"warnings\":[\"Query result was truncated at the configured row_limit.\"]}");
    }

    @Test
    void followsChangingQueryTokensAndPreservesColumnTypes() throws Exception {
        server.reply("POST", EXECUTE, 200, running("first+/="));
        server.reply("POST", FETCH, 200, running("second+/="));
        server.reply("POST", FETCH, 200, completed());
        var output = task().rowLimit(Property.ofValue(2)).build().run(runContext());
        var data = (Map<?, ?>) server.requests.getFirst().json().get("data");
        var attributes = (Map<?, ?>) data.get("attributes");
        assertEquals("ddsql_query_request", data.get("type"));
        assertEquals("SELECT 1", attributes.get("query"));
        assertEquals(2, attributes.get("row_limit"));
        assertEquals(FROM.toEpochMilli(), ((Map<?, ?>) attributes.get("time")).get("from_timestamp"));
        assertEquals(FROM.plusSeconds(3600).toEpochMilli(), ((Map<?, ?>) attributes.get("time")).get("to_timestamp"));
        for (int i = 1; i <= 2; i++) {
            var fetchData = (Map<?, ?>) server.requests.get(i).json().get("data");
            assertEquals("ddsql_query_fetch_request", fetchData.get("type"));
            assertEquals(i == 1 ? "first+/=" : "second+/=", ((Map<?, ?>) fetchData.get("attributes")).get("query_id"));
        }
        assertEquals(Query.State.COMPLETED, output.getState());
        assertEquals(2, output.getFetchCount());
        assertEquals(2L, output.getRowCount());
        assertEquals(9007199254740993L, output.getColumns().getFirst().getValues().getFirst());
        assertNull(output.getColumns().getFirst().getValues().get(1));
        assertEquals(1, output.getWarnings().size());
        assertEquals(APP_KEY, server.requests.getFirst().header("DD-APPLICATION-KEY"));
    }

    @Test
    void synchronousResultCanBeStoredAsIon() throws Exception {
        server.reply("POST", EXECUTE, 200, completed());
        var context = runContext();
        var output = task().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        assertEquals(0, output.getFetchCount());
        assertNull(output.getColumns());
        assertEquals(1, server.requests.size());
        try (var input = context.storage().getFile(output.getUri())) {
            assertEquals("BIGINT", JacksonMapper.ofIon().readValue(input, Map.class).get("dataType"));
        }
    }

    @Test
    void sensitiveColumnsAreRedactedInInlineAndStoredOutputsUnlessOptedIn() throws Exception {
        server.reply("POST", EXECUTE, 200, response("{\"state\":\"completed\",\"columns\":[{\"name\":\"api_key\",\"type\":\"VARCHAR\",\"values\":[\"fixture-only-credential\",null]}]}"));
        var safe = task().build().run(runContext());
        assertEquals("[REDACTED]", safe.getColumns().getFirst().getValues().getFirst());
        assertNull(safe.getColumns().getFirst().getValues().get(1));
        assertEquals("fixture-only-credential", task().includeSecrets(Property.ofValue(true)).build().run(runContext()).getColumns().getFirst().getValues().getFirst());
        var context = runContext();
        var stored = task().fetchType(Property.ofValue(FetchType.STORE)).build().run(context);
        try (var input = context.storage().getFile(stored.getUri())) {
            assertFalse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("fixture-only-credential"));
        }
    }

    @Test
    void runningQueryStopsAtPollBound() {
        server.reply("POST", EXECUTE, 200, running("token"));
        server.reply("POST", FETCH, 200, running("token"));
        assertThrows(IllegalStateException.class, () -> task().maxPolls(Property.ofValue(2)).build().run(runContext()));
        assertEquals(3, server.requests.size());
    }

    @Test
    void malformedLifecycleOrColumnsFail() {
        for (String attributes : java.util.List.of("{\"state\":\"unknown\"}", "{\"state\":\"running\"}",
            "{\"state\":\"completed\"}", "{\"state\":\"completed\",\"columns\":[{\"name\":\"a\",\"type\":\"BIGINT\",\"values\":[1]},{\"name\":\"b\",\"type\":\"BIGINT\",\"values\":[]}]}",
            "{\"state\":\"completed\",\"columns\":[{}]}")) {
            server.reply("POST", EXECUTE, 200, response(attributes));
            assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
        }
    }

    @TestFactory
    Stream<DynamicTest> validatesBeforeCalling() {
        return Stream.<Map.Entry<String, org.junit.jupiter.api.function.Executable>>of(
            Map.entry("blank query", () -> task().query(Property.ofValue(" ")).build().run(runContext())),
            Map.entry("missing query", () -> task().query(null).build().run(runContext())),
            Map.entry("zero row limit", () -> task().rowLimit(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized row limit", () -> task().rowLimit(Property.ofValue(10001)).build().run(runContext())),
            Map.entry("zero poll limit", () -> task().maxPolls(Property.ofValue(0)).build().run(runContext())),
            Map.entry("oversized poll limit", () -> task().maxPolls(Property.ofValue(1001)).build().run(runContext())),
            Map.entry("zero delay", () -> task().pollDelay(Property.ofValue(Duration.ZERO)).build().run(runContext())),
            Map.entry("oversized delay", () -> task().pollDelay(Property.ofValue(Duration.ofMinutes(2))).build().run(runContext())),
            Map.entry("reversed window", () -> task().to(Property.ofValue(FROM)).build().run(runContext())),
            Map.entry("negative lookback", () -> task().lookback(Property.ofValue(Duration.ofSeconds(-1))).build().run(runContext()))
        ).map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            assertThrows(IllegalArgumentException.class, entry.getValue());
            assertTrue(server.requests.isEmpty());
        }));
    }

    @TestFactory
    Stream<DynamicTest> executeAndFetchPropagateHttpErrorClasses() {
        return Stream.of(400, 403, 404, 429, 500).flatMap(status -> Stream.of(EXECUTE, FETCH).map(path -> DynamicTest.dynamicTest(path + " HTTP " + status, () -> {
            if (path.equals(FETCH)) {
                server.reply("POST", EXECUTE, 200, running("token"));
            }
            server.reply("POST", path, status, "{\"errors\":[{\"status\":\"" + status + "\",\"title\":\"failed\",\"detail\":\"test fixture\"}]}");
            assertEquals(status, assertThrows(DatadogClient.DatadogException.class, () -> task().build().run(runContext())).getStatus());
        })));
    }
}
