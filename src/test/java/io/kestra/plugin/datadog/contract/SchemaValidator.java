package io.kestra.plugin.datadog.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** OpenAPI 3 schema assertions for the JSON subset used by the vendored contracts. */
final class SchemaValidator {
    private final JsonNode document;

    SchemaValidator(JsonNode document) { this.document = document; }

    JsonNode resolve(JsonNode schema) {
        if (!schema.has("$ref")) { return schema; }
        String ref = schema.get("$ref").asText();
        require(ref.startsWith("#/"), "external schema reference");
        JsonNode found = document.at(ref.substring(1));
        require(!found.isMissingNode(), "unresolved schema reference " + ref);
        return resolve(found);
    }

    void validate(JsonNode schema, JsonNode value, String path, boolean request) {
        if (schema.has("$ref")) {
            validate(resolve(schema), value, path, request);
        }
        if (schema.isBoolean()) { require(schema.asBoolean(), path + ": forbidden schema"); return; }
        if ((value == null || value.isNull()) && schema.path("nullable").asBoolean()) { return; }
        for (JsonNode part : schema.path("allOf")) { validate(part, value, path, request); }
        for (String keyword : List.of("anyOf", "oneOf")) {
            if (!schema.has(keyword)) { continue; }
            int valid = 0;
            for (JsonNode part : schema.get(keyword)) {
                try { validate(part, value, path, request); valid++; } catch (AssertionError ignored) { }
            }
            require(keyword.equals("oneOf") ? valid == 1 : valid > 0, path + ": does not match " + keyword + " (matched " + valid + ")");
        }
        if (schema.has("not")) {
            boolean matches;
            try { validate(schema.get("not"), value, path, request); matches = true; } catch (AssertionError ignored) { matches = false; }
            require(!matches, path + ": matches forbidden schema");
        }
        if (schema.has("enum")) {
            boolean allowed = false;
            for (JsonNode option : schema.get("enum")) {
                if (option.equals(value) || option.isNumber() && value != null && value.isNumber() && option.decimalValue().compareTo(value.decimalValue()) == 0) { allowed = true; }
            }
            require(allowed, path + ": value is outside enum");
        }
        String type = schema.path("type").asText();
        if (type.isEmpty()) { return; }
        require(value != null && !value.isNull(), path + ": null is not allowed");
        switch (type) {
            case "object" -> {
                require(value.isObject(), path + ": expected object");
                JsonNode properties = schema.path("properties");
                for (JsonNode field : schema.path("required")) {
                    JsonNode property = resolve(properties.path(field.asText()));
                    if (request && property.path("readOnly").asBoolean() || !request && property.path("writeOnly").asBoolean()) { continue; }
                    require(value.has(field.asText()), path + "/" + field.asText() + ": required field is missing");
                }
                var fields = value.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (properties.has(field.getKey())) {
                        validate(properties.get(field.getKey()), field.getValue(), path + "/" + field.getKey(), request);
                    } else if (schema.has("additionalProperties")) {
                        validate(schema.get("additionalProperties"), field.getValue(), path + "/" + field.getKey(), request);
                    }
                }
                size(schema, value.size(), "minProperties", "maxProperties", path);
            }
            case "array" -> {
                require(value.isArray(), path + ": expected array");
                size(schema, value.size(), "minItems", "maxItems", path);
                HashSet<JsonNode> seen = new HashSet<>();
                for (int i = 0; i < value.size(); i++) {
                    if (schema.path("uniqueItems").asBoolean()) { require(seen.add(value.get(i)), path + ": duplicate array item"); }
                    validate(schema.path("items"), value.get(i), path + "/" + i, request);
                }
            }
            case "string" -> {
                require(value.isTextual(), path + ": expected string");
                String text = value.asText();
                size(schema, text.codePointCount(0, text.length()), "minLength", "maxLength", path);
                if (schema.has("pattern")) { require(Pattern.compile(schema.get("pattern").asText()).matcher(text).find(), path + ": pattern mismatch"); }
                try {
                    if (schema.path("format").asText().equals("date-time")) { java.time.OffsetDateTime.parse(text); }
                    if (schema.path("format").asText().equals("date")) { java.time.LocalDate.parse(text); }
                } catch (java.time.format.DateTimeParseException e) { throw new AssertionError(path + ": invalid date format"); }
            }
            case "integer", "number" -> {
                require(value.isNumber(), path + ": expected number");
                BigDecimal number = value.decimalValue();
                if (type.equals("integer")) { require(number.stripTrailingZeros().scale() <= 0, path + ": expected integer"); }
                if (schema.has("minimum")) { require(number.compareTo(schema.get("minimum").decimalValue()) >= (schema.path("exclusiveMinimum").asBoolean() ? 1 : 0), path + ": below minimum"); }
                if (schema.has("maximum")) { require(number.compareTo(schema.get("maximum").decimalValue()) <= (schema.path("exclusiveMaximum").asBoolean() ? -1 : 0), path + ": above maximum"); }
                if (schema.has("multipleOf")) { require(number.remainder(schema.get("multipleOf").decimalValue()).compareTo(BigDecimal.ZERO) == 0, path + ": invalid multiple"); }
            }
            case "boolean" -> require(value.isBoolean(), path + ": expected boolean");
            default -> throw new AssertionError(path + ": unsupported schema type " + type);
        }
    }

    private void size(JsonNode schema, int value, String min, String max, String path) {
        if (schema.has(min)) { require(value >= schema.get(min).asInt(), path + ": below " + min); }
        if (schema.has(max)) { require(value <= schema.get(max).asInt(), path + ": above " + max); }
    }

    static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
