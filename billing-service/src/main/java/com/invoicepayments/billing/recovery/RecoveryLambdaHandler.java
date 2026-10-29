package com.invoicepayments.billing.recovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.*;
import java.util.function.Supplier;

/** Immutable-version Lambda entry. Input carries saved operation identity, never amounts, credentials or payment claims. */
public final class RecoveryLambdaHandler implements RequestHandler<Map<String,Object>,Map<String,Object>> {
    private static RecoveryLambdaRuntime runtime;
    private final Runnable bootstrap;
    private final Supplier<RecoveryLambdaRuntime> runtimeSupplier;
    public RecoveryLambdaHandler(){this(RecoveryBootstrap::ensureConnected,RecoveryLambdaHandler::runtime);}
    // Package-private seam for the separately packaged fault entrypoint. The public runtime remains fixed.
    RecoveryLambdaHandler(Runnable bootstrap,Supplier<RecoveryLambdaRuntime> runtimeSupplier){
        this.bootstrap=Objects.requireNonNull(bootstrap);this.runtimeSupplier=Objects.requireNonNull(runtimeSupplier);
    }
    private static synchronized RecoveryLambdaRuntime runtime(){if(runtime==null)runtime=new RecoveryLambdaRuntime();return runtime;}
    public Map<String,Object> handleRequest(Map<String,Object> input,Context context){
        if(input==null||!input.keySet().equals(Set.of("kind","operationId")))throw new IllegalArgumentException("Only a saved recovery operation identity is accepted");
        String kind=String.valueOf(input.get("kind"));if(!Set.of("PAYMENT","REFUND").contains(kind))throw new IllegalArgumentException("Unsupported recovery kind");
        UUID operation=UUID.fromString(String.valueOf(input.get("operationId")));
        bootstrap.run();
        // Pre-claim admission only; never discard already-received financial evidence when a lease expires.
        return runtimeSupplier.get().run(kind,operation,()->context==null||context.getRemainingTimeInMillis()>=20000);
    }
}
