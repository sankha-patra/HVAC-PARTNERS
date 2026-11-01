package com.invoicepayments.billing.recovery;

/** The remote boundary is invoked only after local admission has committed. */
public interface RecoveryRemote {
    /** error is the closed execution's error name (for example States.Timeout or RecoveryConnectionUnavailableException). */
    record Execution(String arn, String versionArn, String input, String status, String error) {
        public Execution(String arn,String versionArn,String input,String status){this(arn,versionArn,input,status,null);}
    }
    Execution describe(String executionArn);
    String start(String versionArn, String name, String input);
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code) { super(code); this.code=code; }
        public String code() { return code; }
        public boolean missing() { return "ExecutionDoesNotExist".equals(code); }
    }
}
