package io.kestra.plugin.datadog.metrics;

import io.kestra.plugin.datadog.DatadogClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Request and response shapes for QueryScalarData and QueryTimeseriesData. */
final class FormulaQueries {
    private FormulaQueries() {
    }

    static Map<String, Object> body(Map<String, String> queries, List<String> formulas, Instant from, Instant to,
                                    DataSource source, Aggregator aggregator, Long interval) {
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("`queries` must contain at least one named query");
        }
        if (from.toEpochMilli() >= to.toEpochMilli()) {
            throw new IllegalArgumentException("`from` must be before `to` at millisecond precision");
        }
        var rendered = new ArrayList<Map<String, Object>>();
        queries.forEach((name, query) -> {
            if (name == null || name.isBlank() || query == null || query.isBlank()) {
                throw new IllegalArgumentException("Query names and expressions must not be blank");
            }
            var item = DatadogClient.query();
            item.put("name", name);
            item.put("query", query);
            item.put("data_source", source.apiValue());
            if (aggregator != null) {
                item.put("aggregator", aggregator.apiValue());
            }
            rendered.add(item);
        });
        var attributes = DatadogClient.query();
        attributes.put("queries", rendered);
        attributes.put("from", from.toEpochMilli());
        attributes.put("to", to.toEpochMilli());
        if (!formulas.isEmpty()) {
            attributes.put("formulas", formulas.stream().map(formula -> {
                if (formula == null || formula.isBlank()) {
                    throw new IllegalArgumentException("Formulas must not be blank");
                }
                return Map.of("formula", formula);
            }).toList());
        }
        if (interval != null) {
            if (interval < 1) {
                throw new IllegalArgumentException("`queryInterval` must be positive");
            }
            attributes.put("interval", interval);
        }
        return Map.of("data", Map.of("type", aggregator == null ? "timeseries_request" : "scalar_request", "attributes", attributes));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attributes(DatadogClient.Response response) {
        var answer = response.map();
        if (answer.get("errors") != null && !answer.get("errors").toString().isBlank()) {
            throw new IllegalStateException("Datadog could not run the formula query: " + answer.get("errors"));
        }
        if (!(answer.get("data") instanceof Map<?, ?> data) || !(data.get("attributes") instanceof Map<?, ?> attributes)) {
            throw new IllegalStateException("Datadog formula response has no data.attributes object");
        }
        return (Map<String, Object>) attributes;
    }

    static List<Map<String, Object>> columns(Map<String, Object> attributes) {
        if (!(attributes.get("columns") instanceof List<?> columns)) {
            throw new IllegalStateException("Datadog scalar response has no columns array");
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var column : columns) {
            if (!(column instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Datadog scalar column must be an object");
            }
            @SuppressWarnings("unchecked")
            var value = (Map<String, Object>) map;
            result.add(value);
        }
        return result;
    }

    static BigDecimal scalar(Map<String, Object> attributes) {
        var columns = columns(attributes);
        List<Map<String, Object>> numbers = columns.stream().filter(c -> "number".equals(c.get("type"))).toList();
        if (columns.isEmpty()) {
            return null;
        }
        if (numbers.size() != 1 || !(numbers.getFirst().get("values") instanceof List<?> values)) {
            throw new IllegalStateException("A gate needs exactly one numeric scalar column");
        }
        if (values.size() > 1) {
            throw new IllegalStateException("A gate needs one scalar value; remove group-by or reduce the query");
        }
        if (values.isEmpty() || values.getFirst() == null) {
            return null;
        }
        if (!(values.getFirst() instanceof Number number)) {
            throw new IllegalStateException("Datadog scalar value must be a number or null");
        }
        return new BigDecimal(number.toString());
    }

}
