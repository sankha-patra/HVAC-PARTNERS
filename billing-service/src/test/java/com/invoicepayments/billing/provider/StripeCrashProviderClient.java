package com.invoicepayments.billing.provider;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only transport: every Stripe request is redirected to the parent JVM's loopback fake. */
public final class StripeCrashProviderClient {
    private StripeCrashProviderClient(){}
    public static BillingProvider create(URI base) {
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost()))throw new IllegalArgumentException("Loopback test server only");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new StripeBillingProvider("sk_test_processcrash",request->{
            if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())
                throw new IllegalStateException("Stripe I/O observed an application transaction");
            if(!"api.stripe.com".equals(request.uri().getHost()))throw new IllegalArgumentException("Unexpected adapter destination");
            String path=request.uri().getRawPath()+(request.uri().getRawQuery()==null?"":"?"+request.uri().getRawQuery());
            var forwarded=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .method(request.method(),request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            for(String header:new String[]{"Idempotency-Key","Content-Type","Stripe-Version"})request.headers().firstValue(header).ifPresent(value->forwarded.header(header,value));
            var response=client.send(forwarded.build(),HttpResponse.BodyHandlers.ofString());
            return new StripeBillingProvider.Response(response.statusCode(),response.body(),response.headers().map());
        });
    }
}
