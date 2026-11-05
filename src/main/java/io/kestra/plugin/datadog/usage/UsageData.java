package io.kestra.plugin.datadog.usage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogClient;
import io.kestra.plugin.datadog.DatadogFetch;
import io.kestra.core.models.tasks.common.FetchType;

import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cursor paging for GetHourlyUsage. Never follows response URLs. */
final class UsageData {
    private UsageData() {
    }

    record Result(List<Map<String, Object>> rows, Map<String, Object> row, long count, String nextRecordId, URI uri) { }

    static Result collect(RunContext context, DatadogClient client, Map<String, Object> params, int pageSize, int max,
                          String initialCursor, FetchType fetchType) throws Exception {
        Set<String> seen = new HashSet<>();
        String cursor = initialCursor;
        long count = 0;
        try (var sink = new DatadogFetch.Sink<Map<String, Object>>(context, fetchType)) {
            do {
                if (cursor != null && !seen.add(cursor)) {
                    throw new IllegalStateException("Datadog hourly usage repeated a pagination cursor");
                }
                int requested = Math.min(pageSize, max - (int) count);
                params.put("page[limit]", requested);
                params.put("page[next_record_id]", cursor);
                Map<String, Object> answer = JacksonMapper.ofJson().readerFor(new TypeReference<Map<String, Object>>() { })
                    .with(DeserializationFeature.USE_LONG_FOR_INTS)
                    .readValue(client.get("/api/v2/usage/hourly_usage", params).body());
                if (!(answer.get("data") instanceof List<?> rows)) {
                    throw new IllegalStateException("Datadog hourly usage response has no data array");
                }
                if (rows.size() > requested) {
                    throw new IllegalStateException("Datadog hourly usage returned more rows than requested");
                }
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> map)) {
                        throw new IllegalStateException("Datadog hourly usage row must be an object");
                    }
                    @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) map;
                    sink.add(item);
                    count++;
                }
                Object next = answer.get("meta") instanceof Map<?, ?> meta && meta.get("pagination") instanceof Map<?, ?> pagination
                    ? pagination.get("next_record_id") : null;
                if (next != null && !(next instanceof String)) {
                    throw new IllegalStateException("Datadog hourly usage cursor must be a string");
                }
                cursor = next == null || next.toString().isBlank() ? null : next.toString();
                if (cursor != null && seen.contains(cursor)) {
                    throw new IllegalStateException("Datadog hourly usage repeated a pagination cursor");
                }
                if (rows.isEmpty() && cursor != null) {
                    throw new IllegalStateException("Datadog hourly usage returned an empty page with a cursor");
                }
            } while (cursor != null && count < max);
            var result = sink.result();
            return new Result(result.rows(), result.row(), count, cursor, result.uri());
        }
    }
}
