package io.kestra.plugin.datadog.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Detects paging from catalog parameters and uses the spec's response paths. */
record Paging(Style style, String position, String limit, String results, String cursor) {
    enum Style { CURSOR, OFFSET, NUMBER }

    static Paging detect(Catalog.Operation op) {
        var hint = op.definition().path("pagination");
        if (op.parameter("query", "page[cursor]") != null) {
            var limit = op.parameter("query", "page[limit]") != null ? "page[limit]" : "page[size]";
            return new Paging(Style.CURSOR, "page[cursor]", limit, hint.path("resultsPath").asText("data"), hint.path("cursorPath").asText("meta.page.after"));
        }
        if (op.parameter("query", "page[offset]") != null && op.parameter("query", "page[limit]") != null) {
            return new Paging(Style.OFFSET, "page[offset]", "page[limit]", hint.path("resultsPath").asText("data"), null);
        }
        if (op.parameter("query", "page[number]") != null && op.parameter("query", "page[size]") != null) {
            return new Paging(Style.NUMBER, "page[number]", "page[size]", hint.path("resultsPath").asText("data"), null);
        }
        return null;
    }

    static JsonNode at(JsonNode root, String dotted) {
        var value = root;
        if (dotted == null || dotted.isBlank()) { return value; }
        for (var part : dotted.split("\\.")) { value = value.path(part); }
        return value;
    }
}
