package com.invoicepayments.billing.recovery;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/** Immutable campaign identity prevents a process restart from resetting a test allocation. */
public record RecoverySettings(UUID campaign, String versionArn, int maxStarts, int maxSubmissions,
        int maxDescribes, int maxInFlight, Instant deadline) {
    private static final Pattern VERSION=Pattern.compile("arn:aws:states:ap-southeast-2:[0-9]{12}:stateMachine:[A-Za-z0-9_-]{1,80}:[1-9][0-9]*");
    public RecoverySettings {
        if(campaign==null||versionArn==null||!VERSION.matcher(versionArn).matches())
            throw new IllegalArgumentException("A campaign and numeric Sydney Standard workflow version are required");
        if(maxStarts<1||maxStarts>100||maxSubmissions<maxStarts||maxSubmissions>200||maxDescribes<1||maxDescribes>10000
                ||maxInFlight<1||maxInFlight>2||deadline==null)
            throw new IllegalArgumentException("Recovery campaign limits are invalid");
    }
    public static RecoverySettings from(Environment e) {
        if(e.getProperty("billing.jobs.recovery-enabled",Boolean.class,true))
            throw new IllegalArgumentException("AWS recovery requires billing.jobs.recovery-enabled=false; inbox, expiry and event jobs remain local");
        String p="billing.aws-recovery.";
        return new RecoverySettings(UUID.fromString(e.getRequiredProperty(p+"campaign")),e.getRequiredProperty(p+"workflow-version-arn"),
            e.getRequiredProperty(p+"max-starts",Integer.class),e.getRequiredProperty(p+"max-submissions",Integer.class),
            e.getRequiredProperty(p+"max-describes",Integer.class),e.getProperty(p+"max-in-flight",Integer.class,1),
            Instant.parse(e.getRequiredProperty(p+"deadline")));
    }
    public String executionArn(String name) {
        String[] p=versionArn.split(":");
        return String.join(":",p[0],p[1],p[2],p[3],p[4],"execution",p[6],name);
    }
}
