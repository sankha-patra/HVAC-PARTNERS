package com.invoicepayments.billing.recovery;

/** The remote boundary is invoked only after local admission has committed. */
public interface RecoveryRemote {
    record Execution(String arn, String versionArn, String input, String status) {}
    Execution describe(String executionArn);
    String start(String versionArn, String name, String input);
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code) { super(code); this.code=code; }
        public String code() { return code; }
        public boolean missing() { return "ExecutionDoesNotExist".equals(code); }
    }
}
