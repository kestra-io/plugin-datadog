package io.kestra.plugin.datadog.downtimes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class DowntimeSupport {
    private DowntimeSupport() {
    }

    static String path(String id) {
        if (id == null || id.isBlank() || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("downtimeId must identify one downtime");
        }
        return "/api/v2/downtime/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }
}
