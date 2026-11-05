package io.kestra.plugin.datadog.api;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.DatadogFetch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.URI;

/** Lists catalog operationId entries without contacting Datadog. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Find Datadog API operations", description = "Searches the pinned OpenAPI catalog without credentials or an API call. Returns the qualified operation ID, parameters, body requirement, content types, security and server definitions.")
@Plugin(examples = @Example(title = "Find read operations for monitors", full = true, code = """
    id: datadog_find_operations
    namespace: company.observability
    tasks:
      - id: find
        type: io.kestra.plugin.datadog.api.Operations
        tag: Monitors
        method: GET
    """))
public class Operations extends Task implements RunnableTask<Operations.Output> {
    @Schema(title = "Tag", description = "Case-insensitive tag substring.")
    private Property<String> tag;
    @Schema(title = "HTTP method", description = "Only operations with this method.")
    private Property<Method> method;
    @Schema(title = "Search text", description = "Case-insensitive substring of the operation ID, tag or path.")
    private Property<String> text;

    @Schema(title = "Fetch type", description = "FETCH returns operations, FETCH_ONE returns row, STORE returns an ION uri and NONE returns only count.", defaultValue = "FETCH")
    private Property<FetchType> fetchType;

    public enum Method { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE }

    @Override
    public Output run(RunContext context) throws Exception {
        String filterTag = context.render(tag).as(String.class).orElse("").toLowerCase(Locale.ROOT);
        String filterText = context.render(text).as(String.class).orElse("").toLowerCase(Locale.ROOT);
        Method filterMethod = context.render(method).as(Method.class).orElse(null);
        List<Map<String, Object>> rows = Catalog.get().operations().stream()
            .filter(op -> op.tag().toLowerCase(Locale.ROOT).contains(filterTag))
            .filter(op -> filterMethod == null || op.method().equals(filterMethod.name()))
            .filter(op -> (op.id() + " " + op.tag() + " " + op.path()).toLowerCase(Locale.ROOT).contains(filterText))
            .map(Operations::row).toList();
        var result = DatadogFetch.apply(context, context.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH), rows);
        return Output.builder().operations(result.rows()).row(result.row()).uri(result.uri()).count(result.size()).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Catalog.Operation op) {
        Map<String, Object> row = JacksonMapper.ofJson().convertValue(op.definition(), Map.class);
        row.put("qualifiedId", op.key());
        return row;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Matching operation definitions")
        private final List<Map<String, Object>> operations;
        @Schema(title = "First operation", description = "Present with FETCH_ONE.")
        private final Map<String, Object> row;
        @Schema(title = "Stored ION operations", description = "Present with STORE.")
        private final URI uri;
        @Schema(title = "Number of matching operations")
        private final Integer count;
    }
}
