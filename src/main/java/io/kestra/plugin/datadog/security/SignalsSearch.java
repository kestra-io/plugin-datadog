package io.kestra.plugin.datadog.security;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.keys.AccessApi;
import io.kestra.plugin.datadog.users.PagedTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operation: v2 SearchSecurityMonitoringSignals. */
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(title = "Search Datadog security signals", description = "Searches security signals within an explicit UTC window and follows page cursors. Needs security_monitoring_signals_read. Secret fields are redacted before output or storage. A repeated cursor or partial Datadog response fails rather than returning an incomplete result as complete.")
@Plugin(examples = @Example(title = "Read high-severity security signals", full = true, code = """
    id: datadog_security_signals
    namespace: company.security
    tasks:
      - id: signals
        type: io.kestra.plugin.datadog.security.SignalsSearch
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        appKey: "{{ secret('DATADOG_APP_KEY') }}"
        from: "2026-01-01T00:00:00Z"
        to: "2026-01-02T00:00:00Z"
        query: "status:high"
        store: true
    """))
public class SignalsSearch extends PagedTask {
    @NotNull
    @Schema(title = "Window start", description = "RFC 3339 timestamp with an offset. Must precede to.")
    private Property<String> from;
    @NotNull
    @Schema(title = "Window end", description = "RFC 3339 timestamp with an offset.")
    private Property<String> to;
    @Schema(title = "Signal query", description = "Datadog security signal query. Use severity and rule tags in this query.", defaultValue = "*")
    private Property<String> query;
    @Schema(title = "Sort order", description = "OLDEST maps to timestamp and NEWEST maps to -timestamp.", defaultValue = "OLDEST")
    private Property<Sort> sort;

    public enum Sort {
        OLDEST("timestamp"), NEWEST("-timestamp");
        private final String value;
        Sort(String value) { this.value = value; }
    }

    @Override
    public Output run(RunContext context) throws Exception {
        String start = AccessApi.required(context, from, "from");
        String end = AccessApi.required(context, to, "to");
        try {
            if (!Instant.parse(start).isBefore(Instant.parse(end))) {
                throw new IllegalArgumentException("`from` must precede `to`");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("`from` and `to` must be RFC 3339 timestamps");
        }
        int pageSize = AccessApi.range(context, getPageSize(), 100, 100, "pageSize");
        int max = AccessApi.range(context, getMaxResults(), 1000, 10000, "maxResults");
        String query = context.render(this.query).as(String.class).orElse("*");
        String sort = context.render(this.sort).as(Sort.class).orElse(Sort.OLDEST).value;
        DatadogClient client = client(context, true);
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        boolean truncated;
        do {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("limit", Math.min(pageSize, max - result.size()));
            if (cursor != null) { page.put("cursor", cursor); }
            Map<String, Object> body = Map.of("filter", Map.of("from", start, "to", end, "query", query), "sort", sort, "page", page);
            Map<String, Object> response = AccessApi.json(AccessApi.request(client, "POST", "/api/v2/security_monitoring/signals/search", Map.of(), body));
            Map<String, Object> meta = AccessApi.optionalObject(response.get("meta"));
            if (meta.get("status") != null && !"done".equals(meta.get("status"))) {
                throw new IllegalStateException("Datadog signal search did not complete");
            }
            List<Map<String, Object>> rows = AccessApi.rows(response);
            int remaining = max - result.size();
            result.addAll(rows.subList(0, Math.min(rows.size(), remaining)));
            Object next = AccessApi.optionalObject(meta.get("page")).get("after");
            cursor = next instanceof String text && !text.isBlank() ? text : null;
            if (cursor != null && !cursors.add(cursor)) {
                throw new IllegalStateException("Datadog signal search repeated a cursor");
            }
            if (cursor != null && rows.isEmpty()) {
                throw new IllegalStateException("Datadog signal search returned an empty page with a cursor");
            }
            truncated = rows.size() > remaining || cursor != null;
        } while (cursor != null && result.size() < max);
        return output(context, new AccessApi.Page(result, truncated));
    }
}
