package io.kestra.plugin.datadog.deployments;

import java.util.Map;

final class Deployments {
    private Deployments() {
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("`" + name + "` must not be blank");
        }
        return value;
    }

    static String id(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("`" + name + "` must be a nonblank Datadog identifier");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Datadog returned no object for " + name);
        }
        return (Map<String, Object>) value;
    }

    static Map<String, Object> body(String type, Map<String, Object> attributes) {
        return Map.of("data", Map.of("type", type, "attributes", attributes));
    }
}
