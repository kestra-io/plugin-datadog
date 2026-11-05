package io.kestra.plugin.datadog.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.plugin.datadog.ValidDatadogLimits;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Calls any operationId in the pinned catalog. Use api.Operations to discover qualified IDs. */
@ValidDatadogLimits
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
    @PluginProperty(group = "main")
    private Property<String> operationId;

    @Schema(title = "Path parameters", description = "Values for path placeholders. Values are URL encoded as single segments.")
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> pathParams;

    @Schema(title = "Query parameters", description = "Exact API query names. Arrays use the serialization defined by the catalog.")
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> query;

    @ToString.Exclude
    @PluginProperty(dynamic = true, group = "main")
    @Schema(title = "Request body", description = "A JSON map or array, a raw string or a `kestra://` URI containing the body bytes. Multipart bodies are maps whose file values are internal storage URIs.")
    private Object body;

    @ToString.Exclude
    @Schema(title = "Request headers", description = "Additional headers including Content-Type. Connection key headers cannot be replaced. Gzip and deflate Content-Encoding compress the body.")
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> headers;

    @Schema(title = "Paginate", description = "Follow catalog query pagination: cursor, offset or page number. Unsupported operations fail before a call. Default false.")
    @PluginProperty(group = "processing")
    private Property<Boolean> paginate;

    @Schema(title = "Maximum items", description = "Maximum rows collected with pagination, from 1 to 10000. Default 1000.")
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> maxItems;

    @Schema(title = "Fetch type", description = "FETCH returns rows, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only the count.", defaultValue = "FETCH")
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Include sensitive values", description = "Preserve credential fields and URLs. Required for binary output because its fields cannot be inspected. False by default.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> includeSecrets;

    @Schema(title = "Redaction pointers", description = "RFC 6901 pointers relative to each complete response document, such as `/data/attributes/key`. An empty pointer redacts the entire document. Applied before output or storage. Non-JSON responses cannot be redacted and fail if this is set.")
    @PluginProperty(group = "processing")
    private Property<List<String>> redact;

    @Schema(title = "Maximum retries", description = "Retries on 429 or 5xx for GET, HEAD, PUT, DELETE, OPTIONS and TRACE only. From 0 to 10. Default 2. POST and PATCH are never retried.")
    @PluginProperty(group = "reliability")
    private Property<Integer> maxRetries;

    @Schema(title = "Maximum retry wait", description = "Maximum seconds to wait for X-RateLimit-Reset. A longer reset fails without an early retry. From 0 to 3600. Default 60.")
    @PluginProperty(group = "reliability")
    private Property<Integer> maxRetryDelay;

    @Override
    public Output run(RunContext context) throws Exception {
        var rOp = Catalog.get().operation(context.render(operationId).as(String.class).orElse(null));
        var rPaths = context.render(pathParams).asMap(String.class, Object.class);
        var rParams = new LinkedHashMap<String, Object>(context.render(query).asMap(String.class, Object.class));
        var rRequestHeaders = context.render(headers).asMap(String.class, String.class);
        var rBody = body == null ? null : context.render(Map.of("value", body)).get("value");
        var rSite = context.render(getSite()).as(String.class).orElse(null);
        var rBase = context.render(getApiUrl()).as(String.class).orElse(null);
        var rPaging = context.render(paginate).as(Boolean.class).orElse(false);
        if (!rPaging && maxItems != null) {
            throw rOp.invalid("maxItems requires paginate: true");
        }
        var rFetchType = context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rIncludeSecrets = context.render(includeSecrets).as(Boolean.class).orElse(false);
        var rMaximum = bounded(rOp, "maxItems", context.render(maxItems).as(Integer.class).orElse(1000), 1, 10_000);
        var rRetries = bounded(rOp, "maxRetries", context.render(maxRetries).as(Integer.class).orElse(2), 0, 10);
        var rDelay = bounded(rOp, "maxRetryDelay", context.render(maxRetryDelay).as(Integer.class).orElse(60), 0, 3600);
        var rPointers = Redaction.pointers(rOp, context.render(redact).asList(String.class));
        var pagination = rPaging ? Paging.detect(rOp) : null;
        if (rPaging && pagination == null) { throw rOp.invalid("pagination is not supported by catalog query parameters"); }
        var pageSize = 0;
        if (pagination != null) {
            var limit = rOp.parameter("query", pagination.limit());
            if (limit == null) { throw rOp.invalid("pagination has no page size parameter"); }
            pageSize = rParams.containsKey(pagination.limit()) ? Integer.parseInt(String.valueOf(rParams.get(pagination.limit()))) :
                Math.min(rMaximum, limit.path("default").asInt(100));
            if (limit.has("maximum")) { pageSize = Math.min(pageSize, limit.get("maximum").asInt()); }
            if (pageSize < 1) { throw rOp.invalid("pagination page size must be positive"); }
            rParams.put(pagination.limit(), pageSize);
            if (pagination.style() != Paging.Style.CURSOR) {
                var position = rOp.parameter("query", pagination.position());
                rParams.putIfAbsent(pagination.position(), position.path("default").asLong(position.path("minimum").asLong(0)));
            }
        }
        var plan = RequestPlan.prepare(rOp, rSite, rBase, rPaths, rParams, rRequestHeaders, rBody);
        var rKey = context.render(getApiKey()).as(String.class).orElse("").trim();
        var rApp = context.render(getAppKey()).as(String.class).orElse("").trim();
        if (rKey.isBlank()) { throw rOp.invalid("apiKey must not be blank"); }
        var appRequired = false;
        for (var security : rOp.definition().path("security")) { if (security.has("appKeyAuth")) { appRequired = true; } }
        if (appRequired && rApp.isBlank() && !plan.headers().containsKey("authorization")) { throw rOp.invalid("appKey is required by this operation"); }
        var bytes = Transport.body(context, rOp, plan, rBody);
        if (rOp.deprecated()) { context.logger().warn("Datadog operation {} is deprecated in the pinned catalog", rOp.key()); }

        var count = 0;
        var pages = 0;
        var cursors = new HashSet<String>();
        if (pagination != null && rParams.get(pagination.position()) != null) { cursors.add(String.valueOf(rParams.get(pagination.position()))); }
        try (var client = Transport.client(context);
             var sink = new DatadogFetch.Sink<Object>(context, rFetchType)) {
            while (true) {
                var response = Transport.send(client, context, rOp, plan, bytes, rKey, rApp, rRetries, rDelay);
                pages++;
                if (response.bytes().length == 0) { return Output.builder().statusCode(response.status()).count(0).pages(pages).build(); }
                if (!response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
                    if (!rPointers.isEmpty() || rPaging) { throw rOp.invalid("redaction and pagination require a JSON response"); }
                    if (response.contentType().startsWith("text/")) {
                        var text = AccessApi.redact(new String(response.bytes(), StandardCharsets.UTF_8), rIncludeSecrets);
                        sink.add(text);
                        var result = sink.result();
                        return Output.builder().statusCode(response.status()).body(result.rows() == null ? null : text)
                            .row(result.row()).uri(result.uri()).pages(pages).count(1).build();
                    }
                    if (rFetchType == FetchType.NONE) return Output.builder().statusCode(response.status()).pages(pages).count(1).build();
                    if (!rIncludeSecrets) throw rOp.invalid("includeSecrets must be true for binary output");
                    var uri = context.storage().putFile(new ByteArrayInputStream(response.bytes()), "response.bin");
                    return Output.builder().statusCode(response.status()).uri(uri).pages(pages).count(1).build();
                }
                JsonNode json;
                try { json = JacksonMapper.ofJson().readTree(response.bytes()); }
                catch (JsonProcessingException e) { throw rOp.invalid("response is not valid JSON"); }
                if (json == null) { throw rOp.invalid("response is not valid JSON"); }
                var safe = JacksonMapper.ofJson().valueToTree(AccessApi.redact(
                    JacksonMapper.ofJson().convertValue(Redaction.apply(json, rPointers), Object.class), rIncludeSecrets));
                if (!rPaging) {
                    var result = JacksonMapper.ofJson().convertValue(safe, Object.class);
                    if (rFetchType == FetchType.FETCH) { return Output.builder().statusCode(response.status()).body(result).pages(pages).count(safe.isArray() ? safe.size() : 1).build(); }
                    if (safe.isArray()) {
                        for (var row : safe) { sink.add(JacksonMapper.ofJson().convertValue(row, Object.class)); count++; }
                    } else { sink.add(result); count = 1; }
                } else {
                    var rows = Paging.at(safe, pagination.results());
                    if (!rows.isArray()) { throw rOp.invalid("pagination response path " + pagination.results() + " must be an array after redaction"); }
                    for (var row : rows) {
                        if (count >= rMaximum) { break; }
                        var item = JacksonMapper.ofJson().convertValue(row, Object.class);
                        sink.add(item);
                        count++;
                    }
                    var more = count < rMaximum && !rows.isEmpty();
                    if (pagination.style() == Paging.Style.CURSOR) {
                        var cursor = Paging.at(json, pagination.cursor()).asText("");
                        more &= !cursor.isBlank();
                        if (more && !cursors.add(cursor)) { throw rOp.invalid("pagination cursor repeated"); }
                        rParams.put(pagination.position(), cursor);
                    } else {
                        more &= rows.size() >= pageSize;
                        var position = Long.parseLong(String.valueOf(rParams.get(pagination.position())));
                        rParams.put(pagination.position(), Math.addExact(position, pagination.style() == Paging.Style.OFFSET ? pageSize : 1));
                    }
                    if (more) { plan = RequestPlan.prepare(rOp, rSite, rBase, rPaths, rParams, rRequestHeaders, rBody); continue; }
                }
                var result = sink.result();
                return Output.builder().statusCode(response.status()).items(rPaging ? result.rows() : null).row(result.row())
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
