package com.invoicepayments.billing.provider;

import org.json.JSONObject;

public record RefundCommand(String refundId, String intentId, String providerPaymentId,
                            String idempotencyKey, long amountMinor, String currency) {
    /** The saved refund request: recovery replays exactly these parameters with the same key. */
    public String toJson() {
        return new JSONObject().put("refundId", refundId).put("intentId", intentId)
                .put("providerPaymentId", providerPaymentId).put("idempotencyKey", idempotencyKey)
                .put("amountMinor", amountMinor).put("currency", currency).toString();
    }
    public static RefundCommand fromJson(String json) {
        JSONObject value = new JSONObject(json);
        return new RefundCommand(value.getString("refundId"), value.getString("intentId"),
                value.getString("providerPaymentId"), value.getString("idempotencyKey"),
                value.getBigDecimal("amountMinor").longValueExact(), value.getString("currency"));
    }
}
