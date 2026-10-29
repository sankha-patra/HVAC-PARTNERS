package com.invoicepayments.billing.provider;

import java.time.Instant;
import java.util.Objects;

/** External-provider emulator facade. Its injected store commits independently before a lost reply is simulated. */
public final class SimulatorBillingProvider implements BillingProvider {
    private final SimulatorOperationStore operations;
    public SimulatorBillingProvider(SimulatorOperationStore operations){this.operations=Objects.requireNonNull(operations);}
    @Override public String name(){return "SIMULATOR";}
    @Override public String paymentRequestVersion(){return "simulator:payment-v1";}
    @Override public String refundRequestVersion(){return "simulator:refund-v1";}
    @Override public ProviderResult submit(ProviderCommand command){return operations.submit(command);}
    @Override public ProviderResult refund(RefundCommand command){return operations.refund(command);}
    @Override public ProviderResult lookup(String key,String reference){return operations.lookup(key,reference);}
    @Override public ProviderResult lookupRefund(String key,String reference){return operations.lookupRefund(key,reference);}
    @Override public ProviderResult verifyPaymentReference(ProviderCommand expected,String reference){return operations.verifyPaymentReference(expected,reference);}
    @Override public ProviderResult verifyRefundReference(RefundCommand expected,String reference){return operations.verifyRefundReference(expected,reference);}
    @Override public ProviderResult closePayment(ProviderCommand saved,String version,Instant prepared,String reference){return operations.closePayment(saved,version,prepared,reference);}
    @Override public ProviderResult findRefund(RefundCommand saved,String reference){return operations.findRefund(saved,reference);}
    @Override public ProviderResult recoverPayment(ProviderCommand command,String version,Instant prepared,String reference){
        if(reference!=null)return lookup(command.idempotencyKey(),reference);
        if(!paymentRequestVersion().equals(version))throw new ProviderReplayUnsafeException();return submit(command);
    }
    @Override public ProviderResult recoverRefund(RefundCommand command,String version,Instant prepared,String reference){
        if(reference!=null)return lookupRefund(command.idempotencyKey(),reference);
        if(!refundRequestVersion().equals(version))throw new ProviderReplayUnsafeException("Saved refund cannot be safely replayed; reconciliation required");
        return refund(command);
    }
    public ProviderResult simulateResult(String intentId,String outcome){
        ProviderValidation.uuid(intentId);
        if(outcome==null)throw new IllegalArgumentException("Simulation outcome is required");
        String target=switch(outcome){case "SUCCESS","CAPTURED","TIMEOUT_AFTER_ACCEPT"->"CAPTURED";case "FAILED","RETURNED","PROCESSING"->outcome;default->throw new IllegalArgumentException("Unsupported simulation outcome");};
        var result=operations.simulateState(intentId,target);
        if("TIMEOUT_AFTER_ACCEPT".equals(outcome))throw new ProviderUncertainException("Simulated timeout after provider acceptance; reconcile this operation");
        return result;
    }
}
