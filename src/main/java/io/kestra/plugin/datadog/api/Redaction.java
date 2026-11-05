package io.kestra.plugin.datadog.api;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.List;

final class Redaction {
    static List<JsonPointer> pointers(Catalog.Operation op, List<String> values) {
        return values.stream().map(value -> {
            if (value == null || (!value.isEmpty() && !value.startsWith("/")) || value.matches(".*~([^01]|$).*")) {
                throw op.invalid("redact must contain RFC 6901 JSON pointers");
            }
            return JsonPointer.compile(value);
        }).toList();
    }

    static JsonNode apply(JsonNode input, List<JsonPointer> pointers) {
        JsonNode result = input.deepCopy();
        for (JsonPointer pointer : pointers) {
            if (pointer.matches()) { result = TextNode.valueOf("[REDACTED]"); continue; }
            JsonNode parent = result.at(pointer.head());
            String key = pointer.last().getMatchingProperty();
            if (parent instanceof ObjectNode map && map.has(key)) { map.put(key, "[REDACTED]"); }
            if (parent instanceof ArrayNode array) {
                int index = pointer.last().getMatchingIndex();
                if (index >= 0 && index < array.size()) { array.set(index, TextNode.valueOf("[REDACTED]")); }
            }
        }
        return result;
    }
}
