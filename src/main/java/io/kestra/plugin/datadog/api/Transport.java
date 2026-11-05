package io.kestra.plugin.datadog.api;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Kestra HTTP adapter with repeat attempts controlled here, including bodyless requests. */
final class Transport {
    record Body(byte[] bytes, String contentType) { }
    record Answer(int status, byte[] bytes, String contentType, String reset) { }

    static Body body(RunContext context, Catalog.Operation op, RequestPlan plan, Object body) throws Exception {
        String type = plan.contentType();
        byte[] bytes;
        if (body == null) {
            return new Body(new byte[0], "application/octet-stream");
        } else if (body instanceof String text && text.startsWith("kestra://")) {
            try (InputStream stream = context.storage().getFile(URI.create(text))) { bytes = stream.readAllBytes(); }
        } else if (type.startsWith("multipart/form-data")) {
            if (!(body instanceof Map<?, ?> map)) { throw op.invalid("multipart body must be a map of fields and file URIs"); }
            Map<String, Object> parts = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String text && text.startsWith("kestra://")) {
                    try (InputStream stream = context.storage().getFile(URI.create(text))) {
                        var path = context.workingDir().createTempFile(stream.readAllBytes(), ".upload");
                        value = path.toFile();
                    }
                }
                parts.put(String.valueOf(entry.getKey()), value);
            }
            var entity = HttpRequest.MultipartRequestBody.of(parts).to();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            entity.writeTo(out);
            bytes = out.toByteArray();
            type = entity.getContentType();
        } else if (body instanceof String text) {
            bytes = text.getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = JacksonMapper.ofJson().writeValueAsBytes(body);
        }
        if (type.contains("json")) {
            try {
                var parsed = JacksonMapper.ofJson().readTree(bytes);
                if (op.bodyRequired() && (parsed == null || parsed.isNull())) { throw op.invalid("missing required body"); }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw op.invalid("body is not valid JSON");
            }
        }
        String encoding = plan.headers().get("content-encoding");
        if (encoding != null && !encoding.equals("identity")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            switch (encoding) {
                case "gzip" -> { try (GZIPOutputStream compressed = new GZIPOutputStream(out)) { compressed.write(bytes); } }
                case "deflate" -> { try (DeflaterOutputStream compressed = new DeflaterOutputStream(out)) { compressed.write(bytes); } }
                default -> throw op.invalid("unsupported Content-Encoding");
            }
            bytes = out.toByteArray();
        }
        return new Body(bytes, type);
    }

    static HttpClient client(RunContext context) throws Exception {
        return HttpClient.builder().runContext(context).configuration(HttpConfiguration.builder()
            .allowFailed(Property.ofValue(true)).followRedirects(Property.ofValue(false)).build()).build();
    }

    static Answer send(HttpClient client, RunContext context, Catalog.Operation op, RequestPlan plan, Body body,
                       String apiKey, String appKey, int maxRetries, int maxRetryDelay) throws Exception {
            for (int attempt = 0; ; attempt++) {
                HttpRequest.HttpRequestBuilder request = HttpRequest.builder().uri(plan.uri()).method(plan.method());
                plan.headers().forEach((name, value) -> { if (!name.equals("content-type")) { request.addHeader(name, value); } });
                request.addHeader("DD-API-KEY", apiKey);
                if (!appKey.isBlank()) { request.addHeader("DD-APPLICATION-KEY", appKey); }
                if (!plan.headers().containsKey("accept")) { request.addHeader("Accept", "application/json"); }
                // A non-repeatable entity prevents Apache's implicit retries on 429/503.
                // A fresh stream is used only when this task explicitly retries an idempotent method.
                request.addHeader("Content-Type", body.contentType());
                request.body(HttpRequest.InputStreamRequestBody.builder().contentType(body.contentType().split(";", 2)[0])
                    .content(new ByteArrayInputStream(body.bytes())).build());
                Answer[] answer = new Answer[1];
                try {
                    client.request(request.build(), response -> {
                        try {
                            answer[0] = new Answer(response.getStatus().getCode(), response.getBody() == null ? new byte[0] : response.getBody().readAllBytes(),
                                response.getHeaders().firstValue("Content-Type").orElse("application/octet-stream"),
                                response.getHeaders().firstValue("X-RateLimit-Reset").orElse(null));
                        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    });
                } catch (Exception e) {
                    // Do not propagate a cause that can contain a query, header or body value.
                    throw new DatadogClient.DatadogException(-1, "Datadog " + op.key() + ": transport failed (" + e.getClass().getSimpleName() + ")");
                }
                Answer received = answer[0];
                if (received.status() >= 200 && received.status() < 300) { return received; }
                if (attempt < maxRetries && retryable(plan.method(), received.status())) {
                    long delay = retryDelay(received.reset(), attempt);
                    if (delay <= maxRetryDelay) {
                        context.logger().warn("Datadog {} returned HTTP {}; retrying in {} seconds", op.key(), received.status(), delay);
                        Thread.sleep(delay * 1000);
                        continue;
                    }
                }
                throw new DatadogClient.DatadogException(received.status(), "Datadog " + op.key() + " returned HTTP " + received.status());
            }
    }

    static boolean retryable(String method, int status) {
        return List.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE").contains(method) && (status == 429 || status >= 500 && status <= 599);
    }

    static long retryDelay(String reset, int attempt) {
        if (reset != null) {
            try { return Math.max(0, Long.parseLong(reset)); } catch (NumberFormatException ignored) { }
        }
        return 1L << Math.min(attempt, 6);
    }
}
