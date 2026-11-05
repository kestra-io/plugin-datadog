package io.kestra.plugin.datadog.deployments;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Datadog operationId: v2:CreateDORADeployment. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(title = "Record a Datadog DORA deployment", description = "Records a completed deployment for delivery performance metrics. Needs only an API key. Acceptance does not mean asynchronous DORA processing has completed.")
@Plugin(examples = @Example(title = "Record a completed release", full = true, code = """
    id: record_deployment
    namespace: company.delivery
    tasks:
      - id: record
        type: io.kestra.plugin.datadog.deployments.Record
        apiKey: "{{ secret('DATADOG_API_KEY') }}"
        service: checkout
        env: production
        deploymentVersion: v1.2.3
        startedAt: "2026-11-05T10:00:00Z"
        finishedAt: "2026-11-05T10:02:00Z"
    """))
public class Record extends AbstractDatadogTask implements RunnableTask<Record.Output> {
    @NotNull
    @Schema(title = "Service")
    @PluginProperty(group = "main")
    private Property<String> service;
    @NotNull
    @Schema(title = "Deployment start", description = "ISO-8601 instant. Sent as Unix nanoseconds.")
    @PluginProperty(group = "main")
    private Property<Instant> startedAt;
    @NotNull
    @Schema(title = "Deployment finish", description = "ISO-8601 instant at or after startedAt. Sent as Unix nanoseconds.")
    @PluginProperty(group = "main")
    private Property<Instant> finishedAt;
    @Schema(title = "Environment")
    @PluginProperty(group = "main")
    private Property<String> env;
    @Schema(title = "Version")
    @PluginProperty(group = "main")
    private Property<String> deploymentVersion;
    @Schema(title = "Team")
    @PluginProperty(group = "main")
    private Property<String> team;
    @Schema(title = "Deployment ID", description = "Optional Datadog ID, 16 to 128 alphanumeric characters or hyphens, underscores, periods and colons. No client-side upsert guarantee.")
    @PluginProperty(group = "main")
    private Property<String> deploymentId;
    @Schema(title = "Git repository URL", description = "Requires commitSha when set.")
    @ToString.Exclude
    @PluginProperty(group = "main")
    private Property<String> repositoryUrl;
    @Schema(title = "Git commit SHA", description = "Requires repositoryUrl when set.")
    @PluginProperty(group = "main")
    private Property<String> commitSha;
    @Schema(title = "Custom tags")
    @PluginProperty(group = "main")
    private Property<List<String>> customTags;

    @Override
    public Output run(RunContext context) throws Exception {
        try (var client = client(context, false)) {
            var rName = Deployments.required(context.render(service).as(String.class).orElse(null), "service");
            var rStart = context.render(startedAt).as(Instant.class).orElseThrow(() -> new IllegalArgumentException("`startedAt` is required"));
            var rFinish = context.render(finishedAt).as(Instant.class).orElseThrow(() -> new IllegalArgumentException("`finishedAt` is required"));
            if (rStart.isAfter(rFinish)) {
                throw new IllegalArgumentException("`finishedAt` must be at or after startedAt");
            }
            var attributes = new LinkedHashMap<String, Object>();
            attributes.put("service", rName);
            attributes.put("started_at", nanos(rStart));
            attributes.put("finished_at", nanos(rFinish));
            context.render(env).as(String.class).ifPresent(value -> attributes.put("env", value));
            context.render(deploymentVersion).as(String.class).ifPresent(value -> attributes.put("version", value));
            context.render(team).as(String.class).ifPresent(value -> attributes.put("team", value));
            var rId = context.render(deploymentId).as(String.class).orElse(null);
            if (rId != null) {
                if (!rId.matches("[A-Za-z0-9_.:-]{16,128}")) {
                    throw new IllegalArgumentException("`deploymentId` must contain 16 to 128 allowed characters");
                }
                attributes.put("id", rId);
            }
            var rRepo = context.render(repositoryUrl).as(String.class).orElse(null);
            var rSha = context.render(commitSha).as(String.class).orElse(null);
            if (rRepo != null || rSha != null) {
                attributes.put("git", Map.of("repository_url", Deployments.required(rRepo, "repositoryUrl"), "commit_sha", Deployments.required(rSha, "commitSha")));
            }
            var rTags = context.render(customTags).asList(String.class);
            if (!rTags.isEmpty()) {
                attributes.put("custom_tags", rTags);
            }
            var answer = client.post("/api/v2/dora/deployment", Map.of("data", Map.of("attributes", attributes)));
            var data = Deployments.object(answer.map().get("data"), "deployment data");
            return Output.builder().deploymentId(Deployments.required((String) data.get("id"), "deployment id returned by Datadog"))
                .httpStatus(answer.status()).build();
        }
    }

    private static long nanos(Instant instant) {
        try {
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Deployment time is outside the Unix nanosecond range", e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Accepted deployment ID")
        private final String deploymentId;
        @Schema(title = "HTTP acceptance status")
        private final Integer httpStatus;
    }
}
