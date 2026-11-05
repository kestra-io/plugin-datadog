package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.DatadogClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TelemetrySearchTest extends AbstractDatadogTest {
    record Endpoint(String path, String resourceType, Supplier<AbstractSearch.AbstractSearchBuilder<?, ?>> factory) {
        boolean spans() {
            return resourceType.equals("spans");
        }
    }

    private List<Endpoint> endpoints() {
        return List.of(
            new Endpoint("/api/v2/spans/events/search", "spans", io.kestra.plugin.datadog.spans.Search::builder),
            new Endpoint("/api/v2/rum/events/search", "rum", io.kestra.plugin.datadog.rum.Search::builder),
            new Endpoint("/api/v2/audit/events/search", "audit", io.kestra.plugin.datadog.audit.Search::builder),
            new Endpoint("/api/v2/ci/pipelines/events/search", "cipipeline", io.kestra.plugin.datadog.civisibility.SearchPipelines::builder),
            new Endpoint("/api/v2/ci/tests/events/search", "citest", io.kestra.plugin.datadog.civisibility.SearchTests::builder),
            new Endpoint("/api/v2/events/search", "event", io.kestra.plugin.datadog.events.Search::builder)
        );
    }

    private AbstractSearch.AbstractSearchBuilder<?, ?> builder(Endpoint endpoint) {
        var builder = endpoint.factory().get();
        return builder.id(IdUtils.create()).type(builder.build().getClass().getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(AbstractSearch.AbstractSearchBuilder<?, ?> builder, RunContext context) throws Exception {
        return JacksonMapper.ofJson().convertValue(((RunnableTask<?>) builder.build()).run(context), Map.class);
    }

    private String page(Endpoint endpoint, String id, String after) throws Exception {
        // Resource types and attributes follow the corresponding v2 response schemas.
        Map<String, Object> resource = Map.of("id", id, "type", endpoint.resourceType(), "attributes", Map.of("attributes", Map.of("n", 1)));
        return JacksonMapper.ofJson().writeValueAsString(Map.of("data", List.of(resource), "meta", after == null ? Map.of("status", "done") : Map.of("status", "done", "page", Map.of("after", after))));
    }

    private Map<?, ?> attributes(Map<String, Object> body, Endpoint endpoint) {
        if (endpoint.spans()) {
            assertEquals("search_request", ((Map<?, ?>) body.get("data")).get("type"));
            return (Map<?, ?>) ((Map<?, ?>) body.get("data")).get("attributes");
        }
        return body;
    }

    @TestFactory
    Stream<DynamicTest> happyPathAndPagingForEverySearch() {
        return endpoints().stream().map(endpoint -> DynamicTest.dynamicTest(endpoint.resourceType() + " paging", () -> {
            server.requests.clear();
            server.reply("POST", endpoint.path(), 200, page(endpoint, "one", "cursor-one"));
            server.reply("POST", endpoint.path(), 200, page(endpoint, "two", null));
            Map<String, Object> output = run(builder(endpoint).query(Property.ofValue("service:orders"))
                .from(Property.ofValue("2026-11-05T10:00:00+02:00")).to(Property.ofValue("now-1m"))
                .sort(Property.ofValue(AbstractSearch.Sort.ASCENDING)).maxResults(Property.ofValue(3)), runContext());
            assertEquals(2, output.get("size"));
            assertEquals(2, server.requests.size());
            var first = server.requests.getFirst();
            assertEquals(API_KEY, first.header("DD-API-KEY"));
            assertEquals(APP_KEY, first.header("DD-APPLICATION-KEY"));
            assertEquals(endpoint.path(), first.path());
            Map<?, ?> attrs = attributes(first.json(), endpoint);
            assertEquals(Map.of("query", "service:orders", "from", "2026-11-05T10:00:00+02:00", "to", "now-1m"), attrs.get("filter"));
            assertEquals("timestamp", attrs.get("sort"));
            assertEquals(Map.of("limit", 2, "cursor", "cursor-one"), attributes(server.requests.get(1).json(), endpoint).get("page"));
            List<?> rows = (List<?>) output.get(endpoint.resourceType().equals("log") ? "logs" : "events");
            assertEquals("one", ((Map<?, ?>) rows.getFirst()).get("id"));
            assertEquals("two", ((Map<?, ?>) rows.get(1)).get("id"));
        }));
    }

    @TestFactory
    Stream<DynamicTest> storesEverySearchAsIon() {
        return endpoints().stream().map(endpoint -> DynamicTest.dynamicTest(endpoint.resourceType() + " ION", () -> {
            server.reply("POST", endpoint.path(), 200, page(endpoint, "one", "cursor"));
            server.reply("POST", endpoint.path(), 200, page(endpoint, "two", null));
            RunContext context = runContext();
            Map<String, Object> output = run(builder(endpoint).store(Property.ofValue(true)), context);
            assertNull(output.get(endpoint.resourceType().equals("log") ? "logs" : "events"));
            assertEquals(2, output.get("size"));
            List<Object> rows = new ArrayList<>();
            try (var input = context.storage().getFile(URI.create(output.get("uri").toString()))) {
                FileSerde.read(input, rows::add);
            }
            assertEquals(2, rows.size());
            assertEquals("two", ((Map<?, ?>) rows.get(1)).get("id"));
        }));
    }

    @TestFactory
    Stream<DynamicTest> validationForEverySearch() {
        return endpoints().stream().map(endpoint -> DynamicTest.dynamicTest(endpoint.resourceType() + " validation", () -> {
            int before = server.requests.size();
            for (int cap : List.of(0, 10_001)) {
                assertThrows(IllegalArgumentException.class, () -> run(builder(endpoint).maxResults(Property.ofValue(cap)), runContext()));
            }
            for (String field : List.of("from", "to")) {
                var builder = builder(endpoint);
                if (field.equals("from")) {
                    builder.from(Property.ofValue("2026-99-01T00:00:00Z"));
                } else {
                    builder.to(Property.ofValue("tomorrow"));
                }
                var error = assertThrows(IllegalArgumentException.class, () -> run(builder, runContext()));
                assertTrue(error.getMessage().contains(field));
            }
            assertThrows(IllegalArgumentException.class, () -> run(builder(endpoint).appKey(Property.ofValue("")), runContext()));
            assertEquals(before, server.requests.size());
        }));
    }

    @TestFactory
    Stream<DynamicTest> errorsForEverySearch() {
        return endpoints().stream().flatMap(endpoint -> List.of(400, 403, 404, 429, 503).stream()
            .map(status -> DynamicTest.dynamicTest(endpoint.resourceType() + " HTTP " + status, () -> {
                server.reply("POST", endpoint.path(), status, "{\"errors\":[\"request rejected\"]}");
                var error = assertThrows(DatadogClient.DatadogException.class, () -> run(builder(endpoint), runContext()));
                assertEquals(status, error.getStatus());
                assertFalse(error.getMessage().contains(API_KEY));
                assertFalse(error.getMessage().contains(APP_KEY));
            })));
    }

    @TestFactory
    Stream<DynamicTest> capsAndRepeatedCursorsForEverySearch() {
        return endpoints().stream().map(endpoint -> DynamicTest.dynamicTest(endpoint.resourceType() + " cursor guards", () -> {
            server.requests.clear();
            server.reply("POST", endpoint.path(), 200, page(endpoint, "one", "again"));
            assertEquals(1, run(builder(endpoint).maxResults(Property.ofValue(1)), runContext()).get("size"));
            assertEquals(1, server.requests.size());
            assertThrows(IllegalStateException.class, () -> run(builder(endpoint), runContext()));
            server.reply("POST", endpoint.path(), 200, "{\"data\":[],\"meta\":{\"page\":{\"after\":\"empty\"}}}");
            assertThrows(IllegalStateException.class, () -> run(builder(endpoint), runContext()));
        }));
    }

    @Test
    void refusesIncompleteAndMalformedResponses() throws Exception {
        var endpoint = endpoints().getFirst();
        for (String body : List.of("{}", "{\"data\":[42]}", "{\"data\":[],\"meta\":{\"status\":\"timeout\"}}",
            "{\"data\":[],\"meta\":{\"warnings\":[{\"code\":\"unknown_index\",\"title\":\"Unknown index\"}]}}")) {
            server.reply("POST", endpoint.path(), 200, body);
            assertThrows(IllegalStateException.class, () -> run(builder(endpoint), runContext()));
        }
    }

    @Test
    void redactsNestedCredentialsAndHonorsOptInForInlineAndStoredRows() throws Exception {
        var endpoint = endpoints().getFirst();
        String body = "{\"data\":[{\"id\":\"event-id\",\"type\":\"log\",\"attributes\":{\"attributes\":{\"api_key\":\"fixture-key\",\"nested\":[{\"password\":\"fixture-password\",\"webhook_url\":\"https://example.com/hook\"}],\"service\":\"orders\"}}}]}";
        server.reply("POST", endpoint.path(), 200, body);
        var redacted = run(builder(endpoint), runContext());
        assertFalse(redacted.toString().contains("fixture-key"));
        assertFalse(redacted.toString().contains("fixture-password"));
        assertFalse(redacted.toString().contains("https://example.com/hook"));
        assertTrue(redacted.toString().contains("orders"));
        assertTrue(run(builder(endpoint).includeSecrets(Property.ofValue(true)), runContext()).toString().contains("fixture-key"));
        RunContext context = runContext();
        var stored = run(builder(endpoint).store(Property.ofValue(true)), context);
        List<Object> rows = new ArrayList<>();
        try (var input = context.storage().getFile(URI.create(stored.get("uri").toString()))) {
            FileSerde.read(input, rows::add);
        }
        assertFalse(rows.toString().contains("fixture-key"));
        assertTrue(rows.toString().contains("[REDACTED]"));
    }

    @Test
    void acceptsDocumentedTimestampFormatsWithoutTranslation() {
        for (String time : List.of("now", "now-15m", "now-1d/d", "now+1h", "1793872800000", "2026-11-05T10:00:00Z", "2026-11-05T10:00:00.123+02:00")) {
            assertEquals(time, Telemetry.timestamp("from", time));
        }
        for (String time : List.of("", "now-", "now-1q", "2026-11-05", "2026-11-05T10:00:00", "now-15m ")) {
            assertThrows(IllegalArgumentException.class, () -> Telemetry.timestamp("from", time));
        }
    }
}
