package com.marvel.hospitality.reservation.application;

import com.marvel.hospitality.reservation.domain.ReceivedPayment;
import com.marvel.hospitality.reservation.domain.Refund;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A received payment together with the refund it triggered, if any (rest-api.md: each payment row carries its
 * refund's status).
 */
public record PaymentWithRefund(ReceivedPayment payment, @Nullable Refund refund) {

    public PaymentWithRefund {
        Objects.requireNonNull(payment, "payment");
    }
}
