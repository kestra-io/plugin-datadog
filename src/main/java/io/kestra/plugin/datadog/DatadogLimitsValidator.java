package io.kestra.plugin.datadog;

import io.kestra.core.models.property.Property;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.lang.reflect.AnnotatedParameterizedType;
import java.math.BigDecimal;

/** Kestra 1.3 keeps YAML literals as expressions until rendering. */
@Singleton
public class DatadogLimitsValidator implements ConstraintValidator<ValidDatadogLimits, Object> {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;
        var valid = true;
        for (var type = value.getClass(); type != null && type.getName().startsWith("io.kestra.plugin.datadog."); type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getType() != Property.class || !(field.getAnnotatedType() instanceof AnnotatedParameterizedType parameterized)) continue;
                var argument = parameterized.getAnnotatedActualTypeArguments()[0];
                var min = argument.getAnnotation(Min.class);
                var max = argument.getAnnotation(Max.class);
                if (min == null || max == null) continue;
                try {
                    field.setAccessible(true);
                    var property = (Property<?>) field.get(value);
                    if (property == null) continue;
                    var literal = property.toString().trim();
                    if (literal.contains("{{") || literal.contains("{%")) continue;
                    var number = new BigDecimal(literal);
                    if (number.stripTrailingZeros().scale() <= 0 && number.compareTo(BigDecimal.valueOf(min.value())) >= 0
                        && number.compareTo(BigDecimal.valueOf(max.value())) <= 0) continue;
                } catch (NumberFormatException ignored) {
                    // Invalid numeric literals are rejected without rendering expressions.
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot inspect Datadog limit " + field.getName(), e);
                }
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("`" + field.getName() + "` must be an integer between " + min.value() + " and " + max.value())
                    .addPropertyNode(field.getName()).addConstraintViolation();
                valid = false;
            }
        }
        return valid;
    }
}
