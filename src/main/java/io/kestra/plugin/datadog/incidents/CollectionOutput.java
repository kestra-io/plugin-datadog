package io.kestra.plugin.datadog.incidents;

import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Builder
@Getter
public class CollectionOutput implements Output {
    @Schema(title = "First resource", description = "Present with FETCH_ONE.")
    private final Map<String, Object> row;
    @Schema(title = "Results", description = "JSON resources. Null with STORE.")
    private final List<Map<String, Object>> items;

    @Schema(title = "Number of results")
    private final int count;

    @Schema(title = "Stored results", description = "Internal storage URI of the ION file with STORE.")
    private final URI uri;

    @Schema(title = "Result limit reached", description = "True when maxResults was reached. More results can exist.")
    private final boolean limitReached;
}
