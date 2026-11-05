package io.kestra.plugin.datadog.incidents;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import java.net.URI;
import java.util.Map;

@Builder
@Getter
public class CollectionOutput implements io.kestra.core.models.tasks.Output {
    @Schema(title = "Results", description = "JSON resources. Null when store is true.")
    private final java.util.List<Map<String, Object>> items;

    @Schema(title = "Number of results")
    private final int count;

    @Schema(title = "Stored results", description = "Internal storage URI of the ION file when store is true.")
    private final URI uri;

    @Schema(title = "Result limit reached", description = "True when maxResults was reached. More results can exist.")
    private final boolean limitReached;
}
