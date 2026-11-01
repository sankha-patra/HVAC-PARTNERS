package com.invoicepayments.billing.recovery;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * Immutable campaign identity prevents a process restart from resetting an allocation.
 * With autoEnroll every due operation of the scoped merchant and provider joins the campaign by itself; without it only
 * explicitly enrolled operations are recovered (test cohorts). maxAttempts is how many workflows one operation may get
 * before a person owns it: an infrastructure failure (database, tunnel, laptop asleep) starts a later workflow instead.
 */
public record RecoverySettings(UUID campaign, String versionArn, int maxStarts, int maxSubmissions,
        int maxDescribes, int maxInFlight, Instant deadline, boolean autoEnroll, String provider, UUID merchant, int maxAttempts) {
    private static final Pattern VERSION=Pattern.compile("arn:aws:states:ap-southeast-2:[0-9]{12}:stateMachine:[A-Za-z0-9_-]{1,80}:[1-9][0-9]*");
    static final Set<String> PROVIDERS=Set.of("RAZORPAY","STRIPE");
    public RecoverySettings {
        if(campaign==null||versionArn==null||!VERSION.matcher(versionArn).matches())
            throw new IllegalArgumentException("A campaign and numeric Sydney Standard workflow version are required");
        if(maxStarts<1||maxStarts>100000||maxSubmissions<maxStarts||maxSubmissions>200000||maxDescribes<1||maxDescribes>10000000
                ||maxInFlight<1||maxInFlight>8||deadline==null||maxAttempts<1||maxAttempts>10)
            throw new IllegalArgumentException("Recovery campaign limits are invalid");
        // The Lambda accepts only PROVIDERS; the scope here just has to name the provider the payments were made with.
        if(autoEnroll&&(merchant==null||provider==null||!provider.matches("[A-Z][A-Z_]{2,29}")))
            throw new IllegalArgumentException("Automatic enrollment needs the merchant and provider scope");
    }
    /** Explicit-enrollment campaign with one workflow per operation. */
    public RecoverySettings(UUID campaign,String versionArn,int maxStarts,int maxSubmissions,int maxDescribes,int maxInFlight,Instant deadline) {
        this(campaign,versionArn,maxStarts,maxSubmissions,maxDescribes,maxInFlight,deadline,false,null,null,1);
    }
    public static RecoverySettings from(Environment e) {
        if(e.getProperty("billing.jobs.recovery-enabled",Boolean.class,true))
            throw new IllegalArgumentException("AWS recovery requires billing.jobs.recovery-enabled=false; inbox, expiry and event jobs remain local");
        String p="billing.aws-recovery.";
        boolean auto=e.getProperty(p+"auto-enroll",Boolean.class,true);
        return new RecoverySettings(UUID.fromString(e.getRequiredProperty(p+"campaign")),e.getRequiredProperty(p+"workflow-version-arn"),
            e.getRequiredProperty(p+"max-starts",Integer.class),e.getRequiredProperty(p+"max-submissions",Integer.class),
            e.getRequiredProperty(p+"max-describes",Integer.class),e.getProperty(p+"max-in-flight",Integer.class,auto?4:1),
            Instant.parse(e.getRequiredProperty(p+"deadline")),auto,
            auto?e.getProperty("billing.provider","").toUpperCase(java.util.Locale.ROOT):null,
            auto?UUID.fromString(e.getRequiredProperty("billing.provider-merchant-id")):null,
            e.getProperty(p+"max-attempts",Integer.class,auto?6:1));
    }
    public String executionArn(String name) {
        String[] p=versionArn.split(":");
        return String.join(":",p[0],p[1],p[2],p[3],p[4],"execution",p[6],name);
    }
}
