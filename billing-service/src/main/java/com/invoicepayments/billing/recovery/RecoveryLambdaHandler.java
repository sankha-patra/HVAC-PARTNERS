package com.invoicepayments.billing.recovery;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.*;
import java.util.function.Supplier;

/** Immutable-version Lambda entry. Input carries saved operation identity, never amounts, credentials or payment claims. */
public final class RecoveryLambdaHandler implements RequestHandler<Map<String,Object>,Map<String,Object>> {
    // Before any logger exists: INFO logging without HTTP wire output (recovery-logback.xml explains why).
    static {if(System.getProperty("logback.configurationFile")==null)System.setProperty("logback.configurationFile","recovery-logback.xml");}
    private static RecoveryLambdaRuntime runtime;
    private final Runnable bootstrap;
    private final Supplier<RecoveryLambdaRuntime> runtimeSupplier;
    public RecoveryLambdaHandler(){this(RecoveryBootstrap::ensureConnected,RecoveryLambdaHandler::runtime);prewarm(System.getenv());}
    // Package-private seam for the separately packaged fault entrypoint. The public runtime remains fixed.
    RecoveryLambdaHandler(Runnable bootstrap,Supplier<RecoveryLambdaRuntime> runtimeSupplier){
        this.bootstrap=Objects.requireNonNull(bootstrap);this.runtimeSupplier=Objects.requireNonNull(runtimeSupplier);
    }
    // Lambda constructs the handler in its init phase. Starting the tunnel and Spring there keeps cold-start cost out of the
    // invocation budget that claim admission measures. Best-effort: each invocation repeats both and reports any failure.
    void prewarm(Map<String,String> env){
        if(!env.containsKey("AWS_LAMBDA_FUNCTION_NAME"))return;
        try{bootstrap.run();runtimeSupplier.get();}catch(RuntimeException ignored){}
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
