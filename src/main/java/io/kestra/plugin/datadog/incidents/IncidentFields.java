package io.kestra.plugin.datadog.incidents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field payloads of CreateIncident and UpdateIncident. */
public final class IncidentFields {
    private IncidentFields() {
    }

    public enum Severity {
        UNKNOWN, SEV_0, SEV_1, SEV_2, SEV_3, SEV_4, SEV_5;

        public String api() {
            return name().replace('_', '-');
        }
    }

    public enum State {
        ACTIVE("active"), STABLE("stable"), RESOLVED("resolved"), COMPLETED("completed");

        private final String api;

        State(String api) {
            this.api = api;
        }

        public String api() {
            return api;
        }
    }

    static Map<String, Object> fields(Map<String, Object> extra, Severity severity, State state) {
        var result = new LinkedHashMap<String, Object>(extra);
        for (var entry : extra.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> field)) {
                throw new IllegalArgumentException("Incident field `" + entry.getKey() + "` must contain type and value");
            }
            var type = String.valueOf(field.get("type"));
            var value = field.get("value");
            var single = Set.of("dropdown", "textbox").contains(type);
            var multiple = Set.of("multiselect", "textarray", "metrictag", "autocomplete").contains(type);
            if ((!single && !multiple) || (single && value != null && !(value instanceof String))
                || (multiple && value != null && (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String))))) {
                throw new IllegalArgumentException("Incident field `" + entry.getKey() + "` has an invalid type or value");
            }
        }
        if (severity != null) {
            if (extra.containsKey("severity")) {
                throw new IllegalArgumentException("Set severity either as a typed property or in fields");
            }
            result.put("severity", Map.of("type", "dropdown", "value", severity.api()));
        }
        if (state != null) {
            if (extra.containsKey("state")) {
                throw new IllegalArgumentException("Set state either as a typed property or in fields");
            }
            result.put("state", Map.of("type", "dropdown", "value", state.api()));
        }
        return result;
    }
}
