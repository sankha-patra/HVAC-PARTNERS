package com.invoicepayments.billing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class BillingAdviceTest {
    // A busy refusal tells the browser when to try again; nothing was saved, so trying again is safe.
    @Test void busyRefusalSaysWhenToTryAgain() {
        var response = new BillingAdvice(null).problem(
            new BillingProblem(503, "PROVIDER_BUSY", "Nothing was charged"), new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        var conflict = new BillingAdvice(null).problem(BillingProblem.conflict("Already handled"), new MockHttpServletRequest());
        assertThat(conflict.getHeaders().getFirst("Retry-After")).isNull();
    }
}
