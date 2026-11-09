package org.scenarioservice.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * At most {@code value} code points (D8: every length counts code points, pattern item 18). Null is valid.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxCodePoints.Validator.class)
public @interface MaxCodePoints {

    int value();

    String message() default "must be at most {value} characters long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxCodePoints, CharSequence> {

        private int max;

        @Override
        public void initialize(MaxCodePoints constraint) {
            max = constraint.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            String text = value.toString();
            return text.codePointCount(0, text.length()) <= max;
        }
    }
}
