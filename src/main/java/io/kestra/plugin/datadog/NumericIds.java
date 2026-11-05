package io.kestra.plugin.datadog;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Exact conversion for flow arrays whose elements can be Integer or Long after deserialization. */
public final class NumericIds {
    private NumericIds() {
    }

    public static List<Long> render(RunContext context, Property<List<Long>> property, String name) throws Exception {
        var result = new ArrayList<Long>();
        // Property.asList can retain Integer elements even when its requested element type is Long.
        for (Object value : context.render(property).asList(Object.class)) {
            try {
                if (value instanceof Number number) {
                    result.add(new BigDecimal(number.toString()).longValueExact());
                } else if (value instanceof String text) {
                    result.add(Long.parseLong(text));
                } else {
                    throw new IllegalArgumentException("`" + name + "` must contain non-null 64-bit integers");
                }
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("`" + name + "` must contain non-null 64-bit integers", e);
            }
        }
        return result;
    }
}
