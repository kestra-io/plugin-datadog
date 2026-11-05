package io.kestra.plugin.datadog.catalog;

import io.kestra.plugin.datadog.keys.AccessApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validation and patch comparison for the ServiceDefinitionV2Dot2 schema. */
final class ServiceDefinitions {
    private static final Set<String> STRINGS = Set.of("schema-version", "dd-service", "application", "lifecycle", "team", "tier", "type");
    private static final Set<String> ARRAYS = Set.of("ci-pipeline-fingerprints", "languages", "tags");

    private ServiceDefinitions() {
    }

    static void validate(Map<String, Object> definition) {
        AccessApi.required(definition.get("dd-service"), "definition.dd-service");
        if (!"v2.2".equals(definition.get("schema-version"))) {
            throw new IllegalArgumentException("`definition.schema-version` must be v2.2");
        }
        definition.forEach((key, value) -> {
            if (STRINGS.contains(key)) {
                string(value, key);
            } else if (ARRAYS.contains(key)) {
                array(value, key).forEach(item -> string(item, key));
            } else if (key.equals("extensions")) {
                object(value, key);
            } else if (key.equals("contacts") || key.equals("links")) {
                for (Object item : array(value, key)) {
                    Map<String, Object> entry = object(item, key);
                    Set<String> required = key.equals("contacts") ? Set.of("type", "contact") : Set.of("name", "type", "url");
                    Set<String> allowed = key.equals("contacts") ? Set.of("type", "contact", "name") : Set.of("name", "type", "url", "provider");
                    required.forEach(field -> string(entry.get(field), key + "." + field));
                    entry.forEach((field, val) -> {
                        if (!allowed.contains(field)) { throw new IllegalArgumentException("Unknown field in definition." + key); }
                        string(val, key + "." + field);
                    });
                }
            } else if (key.equals("integrations")) {
                object(value, key).forEach((provider, item) -> {
                    if (!Set.of("opsgenie", "pagerduty").contains(provider)) {
                        throw new IllegalArgumentException("Unsupported service definition integration");
                    }
                    Map<String, Object> integration = object(item, provider);
                    if (provider.equals("opsgenie")) { string(integration.get("service-url"), "opsgenie.service-url"); }
                    integration.forEach((field, val) -> {
                        if (field.equals("service-url")) { string(val, field); }
                        else if (provider.equals("opsgenie") && field.equals("region") && Set.of("US", "EU").contains(val)) { /* valid region */ }
                        else { throw new IllegalArgumentException("Invalid service definition integration field"); }
                    });
                });
            } else {
                throw new IllegalArgumentException("Unknown service definition field");
            }
        });
    }

    private static void string(Object value, String name) {
        if (!(value instanceof String)) { throw new IllegalArgumentException("definition." + name + " must be a string"); }
    }

    private static List<?> array(Object value, String name) {
        if (!(value instanceof List<?> list)) { throw new IllegalArgumentException("definition." + name + " must be an array"); }
        return list;
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) { throw new IllegalArgumentException("definition." + name + " must be an object"); }
        return AccessApi.object(value);
    }

    static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> desired) {
        Map<String, Object> merged = new LinkedHashMap<>(current);
        desired.forEach((key, value) -> merged.put(key,
            value instanceof Map<?, ?> && current.get(key) instanceof Map<?, ?>
                ? merge(AccessApi.object(current.get(key)), AccessApi.object(value)) : value));
        return merged;
    }

    static void diff(String path, Map<String, Object> before, Map<String, Object> after, List<UpsertServiceDefinition.Change> changes, boolean include) {
        after.forEach((key, value) -> {
            Object old = before.get(key);
            String field = path + "/" + key.replace("~", "~0").replace("/", "~1");
            if (old instanceof Map<?, ?> && value instanceof Map<?, ?>) {
                diff(field, AccessApi.object(old), AccessApi.object(value), changes, include);
            } else if (!Objects.equals(old, value)) {
                Map<String, Object> oldWrapper = new LinkedHashMap<>();
                Map<String, Object> newWrapper = new LinkedHashMap<>();
                oldWrapper.put(key, old);
                newWrapper.put(key, value);
                // Include the full path so a credential nested below an arbitrary map name stays redacted.
                Map<String, Object> wrappedBefore = new LinkedHashMap<>();
                Map<String, Object> wrappedAfter = new LinkedHashMap<>();
                wrappedBefore.put(field, oldWrapper);
                wrappedAfter.put(field, newWrapper);
                Object safeBefore = AccessApi.object(AccessApi.redact(wrappedBefore, include)).get(field);
                Object safeAfter = AccessApi.object(AccessApi.redact(wrappedAfter, include)).get(field);
                changes.add(UpsertServiceDefinition.Change.builder().field(field)
                    .before(safeBefore instanceof Map<?, ?> map ? map.get(key) : safeBefore)
                    .after(safeAfter instanceof Map<?, ?> map ? map.get(key) : safeAfter).build());
            }
        });
    }
}
