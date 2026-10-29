package com.invoicepayments.billing.notification;

import java.util.Map;
public interface FinanceMailTransport {
    void send(Map<String,Object> notification);
    /** Validation failed before SMTP submission; safe to distinguish from an uncertain send. */
    final class RejectedNotification extends IllegalArgumentException { public RejectedNotification(){super("Notification template rejected");} }
}
