package io.kestra.plugin.datadog.incidents;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Builder(toBuilder = true)
@Getter
public class ResponseOutput implements io.kestra.core.models.tasks.Output {
    @Schema(title = "Resource id", description = "The incident, page, case or status resource id returned by Datadog. Null for a dry run or an empty response.")
    private final String id;

    @Schema(title = "HTTP status code", description = "Null when no HTTP request was sent.")
    private final Integer statusCode;

    @Schema(title = "Dry run", description = "True when the request was only rendered.")
    private final boolean dryRun;

    @Schema(title = "Response body", description = "Datadog JSON response including data and included resources. Sensitive structured fields are redacted by default.")
    private final Map<String, Object> body;

    @Schema(title = "Rendered request", description = "Dry-run method, URL, query and JSON body. Authentication headers are never included. Sensitive structured fields are redacted unless includeSecrets is true.")
    private final Map<String, Object> request;
}
