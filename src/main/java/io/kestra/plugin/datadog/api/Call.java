package io.kestra.plugin.datadog.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.kestra.core.models.tasks.common.FetchType;
import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls any operationId in the pinned catalog. Use api.Operations to discover qualified IDs. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Call a Datadog API operation", description = "Calls an operation from the pinned OpenAPI catalog. Use `v1:operationId` or `v2:operationId` when the ID exists in both versions. Validates parameters before sending. The selected operation can change or delete data. Credential fields and URLs are redacted unless includeSecrets is true. Redaction pointers are applied in every mode.")
@Plugin(examples = @Example(title = "Read a monitor through the API catalog", full = true, code = """
    id: datadog_api_monitor
    namespace: company.observability
    tasks:
      - id: monitor
        type: io.kestra.plugin.datadog.api.Call
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        operationId: GetMonitor
        pathParams:
          monitor_id: 12345
    """))
public class Call extends AbstractDatadogTask implements RunnableTask<Call.Output> {
    @NotNull
    @Schema(title = "Operation ID", description = "The exact OpenAPI operationId. Qualify duplicate IDs with `v1:` or `v2:`.")
    private Property<String> operationId;

    @Schema(title = "Path parameters", description = "Values for path placeholders. Values are URL encoded as single segments.")
    private Property<Map<String, Object>> pathParams;

    @Schema(title = "Query parameters", description = "Exact API query names. Arrays use the serialization defined by the catalog.")
    private Property<Map<String, Object>> query;

    @ToString.Exclude
    @PluginProperty(dynamic = true, group = "main")
    @Schema(title = "Request body", description = "A JSON map or array, a raw string or a `kestra://` URI containing the body bytes. Multipart bodies are maps whose file values are internal storage URIs.")
    private Object body;

    @ToString.Exclude
    @Schema(title = "Request headers", description = "Additional headers including Content-Type. Connection key headers cannot be replaced. Gzip and deflate Content-Encoding compress the body.")
    private Property<Map<String, String>> headers;

    @Schema(title = "Paginate", description = "Follow catalog query pagination: cursor, offset or page number. Unsupported operations fail before a call. Default false.")
    private Property<Boolean> paginate;

    @Schema(title = "Maximum items", description = "Maximum rows collected with pagination, from 1 to 10000. Default 1000.")
    private Property<@Min(1) @Max(10000) Integer> maxItems;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Preserve credential fields and URLs. Required for binary output because its fields cannot be inspected. False by default.")
    private Property<Boolean> includeSecrets;

    @Schema(title = "Redaction pointers", description = "RFC 6901 pointers relative to each complete response document, such as `/data/attributes/key`. An empty pointer redacts the entire document. Applied before output or storage. Non-JSON responses cannot be redacted and fail if this is set.")
    private Property<List<String>> redact;

    @Schema(title = "Maximum retries", description = "Retries on 429 or 5xx for GET, HEAD, PUT, DELETE, OPTIONS and TRACE only. From 0 to 10. Default 2. POST and PATCH are never retried.")
    private Property<Integer> maxRetries;

    @Schema(title = "Maximum retry wait", description = "Maximum seconds to wait for X-RateLimit-Reset. A longer reset fails without an early retry. From 0 to 3600. Default 60.")
    private Property<Integer> maxRetryDelay;

    @Override
    public Output run(RunContext context) throws Exception {
        Catalog.Operation op = Catalog.get().operation(context.render(operationId).as(String.class).orElse(null));
        Map<String, Object> paths = context.render(pathParams).asMap(String.class, Object.class);
        Map<String, Object> params = new LinkedHashMap<>(context.render(query).asMap(String.class, Object.class));
        Map<String, String> requestHeaders = context.render(headers).asMap(String.class, String.class);
        Object renderedBody = body == null ? null : context.render(Map.of("value", body)).get("value");
        String site = context.render(getSite()).as(String.class).orElse(null);
        String base = context.render(getApiUrl()).as(String.class).orElse(null);
        boolean paging = context.render(paginate).as(Boolean.class).orElse(false);
        if (!paging && maxItems != null) {
            throw op.invalid("maxItems requires paginate: true");
        }
        var rFetchType = context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
        int maximum = bounded(op, "maxItems", context.render(maxItems).as(Integer.class).orElse(1000), 1, 10_000);
        int retries = bounded(op, "maxRetries", context.render(maxRetries).as(Integer.class).orElse(2), 0, 10);
        int delay = bounded(op, "maxRetryDelay", context.render(maxRetryDelay).as(Integer.class).orElse(60), 0, 3600);
        var pointers = Redaction.pointers(op, context.render(redact).asList(String.class));
        Paging pagination = paging ? Paging.detect(op) : null;
        if (paging && pagination == null) { throw op.invalid("pagination is not supported by catalog query parameters"); }
        int pageSize = 0;
        if (pagination != null) {
            JsonNode limit = op.parameter("query", pagination.limit());
            if (limit == null) { throw op.invalid("pagination has no page size parameter"); }
            pageSize = params.containsKey(pagination.limit()) ? Integer.parseInt(String.valueOf(params.get(pagination.limit()))) :
                Math.min(maximum, limit.path("default").asInt(100));
            if (limit.has("maximum")) { pageSize = Math.min(pageSize, limit.get("maximum").asInt()); }
            if (pageSize < 1) { throw op.invalid("pagination page size must be positive"); }
            params.put(pagination.limit(), pageSize);
            if (pagination.style() != Paging.Style.CURSOR) {
                JsonNode position = op.parameter("query", pagination.position());
                params.putIfAbsent(pagination.position(), position.path("default").asLong(position.path("minimum").asLong(0)));
            }
        }
        RequestPlan plan = RequestPlan.prepare(op, site, base, paths, params, requestHeaders, renderedBody);
        String key = context.render(getApiKey()).as(String.class).orElse("").trim();
        String app = context.render(getAppKey()).as(String.class).orElse("").trim();
        if (key.isBlank()) { throw op.invalid("apiKey must not be blank"); }
        boolean appRequired = false;
        for (JsonNode security : op.definition().path("security")) { if (security.has("appKeyAuth")) { appRequired = true; } }
        if (appRequired && app.isBlank() && !plan.headers().containsKey("authorization")) { throw op.invalid("appKey is required by this operation"); }
        Transport.Body bytes = Transport.body(context, op, plan, renderedBody);
        if (op.deprecated()) { context.logger().warn("Datadog operation {} is deprecated in the pinned catalog", op.key()); }

        int count = 0;
        int pages = 0;
        HashSet<String> cursors = new HashSet<>();
        if (pagination != null && params.get(pagination.position()) != null) { cursors.add(String.valueOf(params.get(pagination.position()))); }
        try (var client = Transport.client(context);
             var sink = new DatadogFetch.Sink<Object>(context, rFetchType)) {
            while (true) {
                Transport.Answer response = Transport.send(client, context, op, plan, bytes, key, app, retries, delay);
                pages++;
                if (response.bytes().length == 0) { return Output.builder().statusCode(response.status()).count(0).pages(pages).build(); }
                if (!response.contentType().toLowerCase(java.util.Locale.ROOT).contains("json")) {
                    if (!pointers.isEmpty() || paging) { throw op.invalid("redaction and pagination require a JSON response"); }
                    if (response.contentType().startsWith("text/")) {
                        var text = AccessApi.redact(new String(response.bytes(), StandardCharsets.UTF_8), rIncludeSecrets);
                        sink.add(text);
                        var result = sink.result();
                        return Output.builder().statusCode(response.status()).body(result.rows() == null ? null : text)
                            .row(result.row()).uri(result.uri()).pages(pages).count(1).build();
                    }
                    if (rFetchType == FetchType.NONE) return Output.builder().statusCode(response.status()).pages(pages).count(1).build();
                    if (!rIncludeSecrets) throw op.invalid("includeSecrets must be true for binary output");
                    URI uri = context.storage().putFile(new ByteArrayInputStream(response.bytes()), "response.bin");
                    return Output.builder().statusCode(response.status()).uri(uri).pages(pages).count(1).build();
                }
                JsonNode json;
                try { json = JacksonMapper.ofJson().readTree(response.bytes()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw op.invalid("response is not valid JSON"); }
                if (json == null) { throw op.invalid("response is not valid JSON"); }
                JsonNode safe = JacksonMapper.ofJson().valueToTree(AccessApi.redact(
                    JacksonMapper.ofJson().convertValue(Redaction.apply(json, pointers), Object.class), rIncludeSecrets));
                if (!paging) {
                    Object result = JacksonMapper.ofJson().convertValue(safe, Object.class);
                    if (rFetchType == FetchType.FETCH) { return Output.builder().statusCode(response.status()).body(result).pages(pages).count(safe.isArray() ? safe.size() : 1).build(); }
                    if (safe.isArray()) {
                        for (JsonNode row : safe) { sink.add(JacksonMapper.ofJson().convertValue(row, Object.class)); count++; }
                    } else { sink.add(result); count = 1; }
                } else {
                    JsonNode rows = Paging.at(safe, pagination.results());
                    if (!rows.isArray()) { throw op.invalid("pagination response path " + pagination.results() + " must be an array after redaction"); }
                    for (JsonNode row : rows) {
                        if (count >= maximum) { break; }
                        Object item = JacksonMapper.ofJson().convertValue(row, Object.class);
                        sink.add(item);
                        count++;
                    }
                    boolean more = count < maximum && !rows.isEmpty();
                    if (pagination.style() == Paging.Style.CURSOR) {
                        String cursor = Paging.at(json, pagination.cursor()).asText("");
                        more &= !cursor.isBlank();
                        if (more && !cursors.add(cursor)) { throw op.invalid("pagination cursor repeated"); }
                        params.put(pagination.position(), cursor);
                    } else {
                        more &= rows.size() >= pageSize;
                        long position = Long.parseLong(String.valueOf(params.get(pagination.position())));
                        params.put(pagination.position(), Math.addExact(position, pagination.style() == Paging.Style.OFFSET ? pageSize : 1));
                    }
                    if (more) { plan = RequestPlan.prepare(op, site, base, paths, params, requestHeaders, renderedBody); continue; }
                }
                var result = sink.result();
                return Output.builder().statusCode(response.status()).items(paging ? result.rows() : null).row(result.row())
                    .uri(result.uri()).count(count).pages(pages).build();
            }
        }
    }

    private static int bounded(Catalog.Operation op, String name, int value, int min, int max) {
        if (value < min || value > max) { throw op.invalid(name + " must be between " + min + " and " + max); }
        return value;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code")
        private final Integer statusCode;
        @Schema(title = "Response body", description = "JSON or text for a single call. Null when stored or paginated.")
        private final Object body;
        @Schema(title = "Collected items", description = "Rows from paginated responses. Null when stored.")
        private final List<Object> items;
        @Schema(title = "First result", description = "Present with FETCH_ONE.")
        private final Object row;
        @Schema(title = "Stored response URI", description = "ION rows for JSON responses or unchanged bytes for non-JSON responses.")
        private final URI uri;
        @Schema(title = "Output row count")
        private final Integer count;
        @Schema(title = "Response page count")
        private final Integer pages;
    }
}
