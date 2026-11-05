package io.kestra.plugin.datadog.keys;

/** Datadog v2 key resource types. Application key creation is for the authenticated user. */
public enum KeyKind {
    API("api_keys", "/api/v2/api_keys"),
    APPLICATION("application_keys", "/api/v2/current_user/application_keys");

    final String resourceType;
    final String createPath;

    KeyKind(String resourceType, String createPath) {
        this.resourceType = resourceType;
        this.createPath = createPath;
    }

    String resourcePath(String id) {
        return "/api/v2/" + resourceType + "/" + AccessApi.segment(id);
    }
}
