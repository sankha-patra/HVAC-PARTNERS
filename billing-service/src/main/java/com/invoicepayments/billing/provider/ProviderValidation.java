package com.invoicepayments.billing.provider;

import java.util.UUID;

final class ProviderValidation {
    private ProviderValidation() {}

    static void payment(ProviderCommand command) {
        if (command == null) throw new IllegalArgumentException("Payment command is required");
        uuid(command.intentId());
        key(command.idempotencyKey());
        amount(command.amountMinor(), command.currency());
        if (!"CARD".equals(command.method()) && !"ACH".equals(command.method())) {
            throw new IllegalArgumentException("Payment method must be CARD or ACH");
        }
    }

    static void refund(RefundCommand command) {
        if (command == null) throw new IllegalArgumentException("Refund command is required");
        uuid(command.refundId());
        uuid(command.intentId());
        key(command.idempotencyKey());
        amount(command.amountMinor(), command.currency());
        if (command.providerPaymentId() == null || command.providerPaymentId().isBlank()) {
            throw new IllegalArgumentException("Original provider payment is required");
        }
    }

    static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("A canonical UUID is required");
        }
    }

    static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("A stable printable idempotency key of at most 255 characters is required");
        }
    }

    static void amount(long value, String currency) {
        if (value <= 0 || value > 99_999_999L || !"USD".equals(currency)) {
            throw new IllegalArgumentException("A positive USD amount of at most 99999999 cents is required");
        }
    }
}
