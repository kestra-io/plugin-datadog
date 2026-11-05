package io.kestra.plugin.datadog;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DatadogLimitsValidator.class)
public @interface ValidDatadogLimits {
    String message() default "Invalid Datadog result limit";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
