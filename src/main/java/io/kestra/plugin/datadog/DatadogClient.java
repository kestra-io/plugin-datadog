package io.kestra.plugin.datadog;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// The keys are sent as headers and never reach a log or an error message
public final class DatadogClient implements AutoCloseable {
    public static final String DEFAULT_SITE = "datadoghq.com";

    // a DNS name only, so that a rendered `site` cannot add a path, a port or credentials to the URL
    private static final Pattern SITE = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");

    private final RunContext runContext;
    private final String apiKey;
    private final String appKey;
    private final String site;
    private final String apiUrl;
    // one client, so one connection pool, for all the requests of a task or a poll
    private HttpClient httpClient;
    // the latest time Datadog gave in a Date header: Datadog's own clock, not the one of the worker
    private Instant serverTime;

    private DatadogClient(RunContext runContext, String apiKey, String appKey, String site, String apiUrl) {
        this.runContext = runContext;
        this.apiKey = apiKey;
        this.appKey = appKey;
        this.site = site;
        this.apiUrl = apiUrl;
    }

    // needsAppKey: Datadog opens the calls that read data or manage monitors to an application key only
    public static DatadogClient create(
        RunContext runContext,
        Property<String> apiKey,
        Property<String> appKey,
        Property<String> site,
        Property<String> apiUrl,
        boolean needsAppKey
    ) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElse("").trim();
        if (rApiKey.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `apiKey` must not be blank");
        }

        var rAppKey = runContext.render(appKey).as(String.class).orElse("").trim();
        if (needsAppKey && rAppKey.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `appKey` must not be blank: this endpoint needs an application key in addition to the API key");
        }

        var rSite = runContext.render(site).as(String.class).orElse("").trim();
        if (rSite.isEmpty()) {
            rSite = DEFAULT_SITE;
        }
        if (!SITE.matcher(rSite).matches()) {
            throw new IllegalArgumentException("The Datadog `site` must be a domain name such as `datadoghq.com` or `datadoghq.eu` but was '" + rSite + "'");
        }

        var rApiUrl = runContext.render(apiUrl).as(String.class).orElse("").trim();
        if (!rApiUrl.isEmpty()) {
            var uri = URI.create(rApiUrl);
            if (uri.getScheme() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getHost() == null) {
                throw new IllegalArgumentException("The Datadog `apiUrl` must be an http or https URL but was '" + rApiUrl + "'");
            }
        }

        return new DatadogClient(runContext, rApiKey, rAppKey, rSite, rApiUrl);
    }

    /** The base URL of the Datadog API: the override if given, otherwise {@code https://api.<site>}. */
    public String apiBase() {
        return trimSlash(apiUrl.isEmpty() ? "https://api." + site : apiUrl);
    }

    /** The base URL of the log intake: the override if given, otherwise {@code https://http-intake.logs.<site>}. */
    public String logsIntakeBase(String intakeUrl) {
        var override = intakeUrl == null ? "" : intakeUrl.trim();
        return trimSlash(override.isEmpty() ? "https://http-intake.logs." + site : override);
    }

    public Response get(String path, Map<String, ?> query) throws Exception {
        return exchange("GET", apiBase(), path, query, null, true);
    }

    public Response post(String path, Object body) throws Exception {
        return exchange("POST", apiBase(), path, Map.of(), body, true);
    }

    public Response delete(String path) throws Exception {
        return exchange("DELETE", apiBase(), path, Map.of(), null, true);
    }

    // failOnError false returns any status, so that a caller such as a trigger can skip a rate limit; a network failure still throws
    public Response exchange(String method, String base, String path, Map<String, ?> query, Object body, boolean failOnError) throws Exception {
        var uri = URI.create(trimSlash(base) + path + queryString(query));

        var request = HttpRequest.builder()
            .uri(uri)
            .method(method)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Accept", "application/json");
        if (!appKey.isEmpty()) {
            request.addHeader("DD-APPLICATION-KEY", appKey);
        }
        if (body != null) {
            request.body(HttpRequest.JsonRequestBody.of(body));
        }

        HttpResponse<String> response;
        try {
            response = http().request(request.build(), String.class);
        } catch (HttpClientException e) {
            // status -1: Datadog did not answer (DNS, TLS, reset, timeout)
            throw new DatadogException(-1, "Datadog could not be reached at " + uri.getScheme() + "://" + uri.getAuthority() + ": " + e.getMessage(), e);
        }

        var status = response.getStatus().getCode();
        var raw = response.getBody() == null ? "" : response.getBody();
        var retryAfter = response.getHeaders().firstValue("X-RateLimit-Reset").orElse(null);
        var date = response.getHeaders().firstValue("Date").map(DatadogClient::parseHttpDate).orElse(null);
        if (date != null) {
            serverTime = date;
        }

        if (failOnError && (status < 200 || status >= 300)) {
            throw new DatadogException(status, describe(method, uri, status, raw, retryAfter));
        }

        return new Response(status, raw, retryAfter, date);
    }

    /** The time of the latest answer according to Datadog's {@code Date} header or empty before the first answer. */
    public Optional<Instant> serverTime() {
        return Optional.ofNullable(serverTime);
    }

    private HttpClient http() throws IllegalVariableEvaluationException {
        if (httpClient == null) {
            // allowFailed: the status is handled here, with the message Datadog sends
            httpClient = HttpClient.builder()
                .runContext(runContext)
                .configuration(HttpConfiguration.builder().allowFailed(Property.ofValue(true)).build())
                .build();
        }
        return httpClient;
    }

    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
    }

    private static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String describe(String method, URI uri, int status, String raw, String retryAfter) {
        var message = new StringBuilder("Datadog answered ").append(method).append(' ').append(uri.getPath())
            .append(" with HTTP ").append(status);
        var detail = errors(raw);
        if (!detail.isEmpty()) {
            message.append(": ").append(detail);
        }
        switch (status) {
            case 401, 403 -> message.append(". Check the `apiKey` and the `appKey`, that they belong to the `site` '")
                .append(site).append("' and that the application key has the permission this call needs");
            case 404 -> message.append(". Check the `site` and the id in the request");
            case 429 -> message.append(". The rate limit is reached").append(retryAfter == null ? "" : ", it resets in " + retryAfter + " seconds");
            default -> {
            }
        }
        return message.toString();
    }

    /** Extracts the {@code errors} list of a Datadog error answer or the start of the body when the shape is different. */
    static String errors(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            var json = JacksonMapper.ofJson().readValue(raw, Object.class);
            if (json instanceof Map<?, ?> map && map.get("errors") instanceof List<?> list && !list.isEmpty()) {
                return String.join("; ", list.stream().map(String::valueOf).toList());
            }
        } catch (JsonProcessingException ignored) {
            // not JSON: show the start of it
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
    }

    static String queryString(Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        var out = new StringBuilder();
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            out.append(out.isEmpty() ? '?' : '&')
                .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    /** Builds the query map in a stable order. */
    public static Map<String, Object> query() {
        return new LinkedHashMap<>();
    }

    /** An answer of Datadog: the status, the raw body, the seconds before the rate limit resets when sent and the time Datadog gives in its {@code Date} header. */
    public record Response(int status, String body, String rateLimitReset, Instant date) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The body parsed as JSON or {@code null} when it is empty. */
        public Object json() {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                return JacksonMapper.ofJson().readValue(body, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Datadog returned an answer that is not valid JSON: " + e.getOriginalMessage(), e);
            }
        }

        /** The body as a JSON object. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> map() {
            var json = json();
            if (json == null) {
                return Map.of();
            }
            if (json instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalStateException("Datadog returned a " + json.getClass().getSimpleName() + " where a JSON object was expected");
        }
    }

    /** Raised when Datadog answers with an error status (or, with status -1, does not answer). */
    public static class DatadogException extends IllegalStateException {
        private final int status;

        public DatadogException(int status, String message) {
            super(message);
            this.status = status;
        }

        public DatadogException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        /** {@code true} when the call may work later: Datadog did not answer, is rate limiting or is failing. */
        public boolean isTransient() {
            return status == -1 || status == 429 || status >= 500;
        }

        public int getStatus() {
            return status;
        }
    }
}
