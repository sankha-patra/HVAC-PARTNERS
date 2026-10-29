package com.invoicepayments.billing.recovery;

import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;
import static com.invoicepayments.billing.BillingStore.requireNoTransaction;

/** SDK calls are outside PostgreSQL transactions and bounded by the client's request timeouts. */
public final class StepFunctionsRecoveryRemote implements RecoveryRemote {
    private final SfnClient client;
    public StepFunctionsRecoveryRemote(SfnClient client){this.client=client;}
    public Execution describe(String arn) {
        requireNoTransaction();
        try {var e=client.describeExecution(DescribeExecutionRequest.builder().executionArn(arn).build());
            return new Execution(e.executionArn(),e.stateMachineVersionArn(),e.input(),e.statusAsString());
        }catch(SfnException failure){throw new Failure(failure.awsErrorDetails()==null?"SFN_UNCERTAIN":failure.awsErrorDetails().errorCode());}
    }
    public String start(String version,String name,String input) {
        requireNoTransaction();
        try{return client.startExecution(StartExecutionRequest.builder().stateMachineArn(version).name(name).input(input).build()).executionArn();}
        catch(SfnException failure){throw new Failure(failure.awsErrorDetails()==null?"SFN_UNCERTAIN":failure.awsErrorDetails().errorCode());}
    }
}
