package io.kestra.plugin.datadog.statuspages;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.incidents.ResponseOutput;
import io.kestra.plugin.datadog.incidents.ResponseSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class DegradationTask extends ResponseSupport {
    @NotNull
    @Schema(title = "Status page id")
    private Property<String> pageId;

    @Schema(title = "Degradation title", description = "Required and nonblank for creation. Omitted on update unless supplied.")
    private Property<String> title;

    @Schema(title = "Update message", description = "The description published with this degradation update.")
    private Property<String> message;

    @Schema(title = "Degradation status", description = "Required for creation. RESOLVED closes the degradation.")
    private Property<Status> status;

    @Schema(title = "Component statuses", description = "Map of component UUID to status. Required for creation. An empty map sends an empty components_affected array.")
    private Property<Map<String, ComponentStatus>> components;

    @Schema(title = "Notify subscribers", description = "Explicitly controls subscriber notifications. False by default.")
    private Property<Boolean> notifySubscribers;

    @Schema(title = "Idempotency key", description = "Optional UUID forwarded to Datadog as meta.idempotency_key.")
    private Property<String> idempotencyKey;

    @Schema(title = "Dry run", description = "Render the request without publishing an update. True by default.")
    private Property<Boolean> dryRun;

    public enum Status {
        INVESTIGATING, IDENTIFIED, MONITORING, RESOLVED
    }

    public enum ComponentStatus {
        OPERATIONAL, DEGRADED, PARTIAL_OUTAGE, MAJOR_OUTAGE, MAINTENANCE
    }

    protected ResponseOutput publish(RunContext context, String id) throws Exception {
        String page = uuid(context, pageId, "pageId");
        boolean creating = id == null;
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (creating || title != null) {
            attributes.put("title", required(context, title, "title"));
        }
        if (creating || status != null) {
            attributes.put("status", required(context, status, Status.class, "status").name().toLowerCase(Locale.ROOT));
        }
        context.render(message).as(String.class).ifPresent(v -> attributes.put("description", v));
        if (creating && components == null) {
            throw new IllegalArgumentException("`components` is required for a degradation");
        }
        if (components != null) {
            java.util.List<Map<String, Object>> affected = new ArrayList<>();
            for (Map.Entry<String, ComponentStatus> entry : context.render(components).asMap(String.class, ComponentStatus.class).entrySet()) {
                String component = uuid(context, Property.ofValue(entry.getKey()), "component id");
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("A component status is required");
                }
                affected.add(Map.of("id", component, "status", entry.getValue().name().toLowerCase(Locale.ROOT)));
            }
            attributes.put("components_affected", affected);
        }
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("Set at least one degradation attribute");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", resource("degradations", id, attributes));
        if (idempotencyKey != null) {
            body.put("meta", Map.of("idempotency_key", uuid(context, idempotencyKey, "idempotencyKey")));
        }
        return send(context, creating ? "POST" : "PATCH", "/api/v2/statuspages/" + page + "/degradations" + (creating ? "" : "/" + id),
            Map.of("notify_subscribers", context.render(notifySubscribers).as(Boolean.class).orElse(false)), body, dryRun(context, dryRun));
    }
}
