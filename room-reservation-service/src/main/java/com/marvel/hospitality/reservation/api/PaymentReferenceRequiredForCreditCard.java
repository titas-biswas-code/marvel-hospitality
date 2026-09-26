package com.marvel.hospitality.reservation.api;

import com.marvel.hospitality.reservation.domain.PaymentMode;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code paymentReference} must be non-blank when {@code paymentMode} is {@code CREDIT_CARD} (rest-api.md). A
 * cross-field rule, so it sits on the type; the violation is reported against the {@code paymentReference} field so
 * the {@code 400 VALIDATION_FAILED} body names it like any other field error.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PaymentReferenceRequiredForCreditCard.Validator.class)
@interface PaymentReferenceRequiredForCreditCard {

    String message() default "is required when paymentMode is CREDIT_CARD";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PaymentReferenceRequiredForCreditCard, CreateReservationRequest> {

        @Override
        public boolean isValid(CreateReservationRequest request, ConstraintValidatorContext context) {
            if (request == null || request.paymentMode() != PaymentMode.CREDIT_CARD) {
                return true;
            }
            String reference = request.paymentReference();
            if (reference != null && !reference.isBlank()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("paymentReference")
                    .addConstraintViolation();
            return false;
        }
    }
}
