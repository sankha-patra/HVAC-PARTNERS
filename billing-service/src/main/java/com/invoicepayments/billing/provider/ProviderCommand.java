package com.invoicepayments.billing.provider;

import org.json.JSONObject;

public record ProviderCommand(String intentId, String idempotencyKey, long amountMinor,
                              String currency, String method, String returnUrl) {
    public String toJson() {
        return new JSONObject().put("intentId", intentId).put("idempotencyKey", idempotencyKey)
                .put("amountMinor", amountMinor).put("currency", currency).put("method", method)
                .put("returnUrl", returnUrl).toString();
    }
    public static ProviderCommand fromJson(String json) {
        JSONObject value = new JSONObject(json);
        return new ProviderCommand(value.getString("intentId"), value.getString("idempotencyKey"),
                value.getBigDecimal("amountMinor").longValueExact(), value.getString("currency"),
                value.getString("method"), value.getString("returnUrl"));
    }
}
