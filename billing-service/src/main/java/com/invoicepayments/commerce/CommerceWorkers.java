package com.invoicepayments.commerce;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("billing")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${billing.role:combined}' != 'api' && ${billing.workers-enabled:true}")
@ConditionalOnProperty(name="commerce.hold-expiry-enabled",havingValue="true",matchIfMissing=true)
public class CommerceWorkers {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CommerceWorkers.class);
    private final CommerceCommands commands;
    public CommerceWorkers(CommerceCommands commands){this.commands=commands;}
    @Scheduled(fixedDelayString="${commerce.hold-expiry-delay-ms:60000}") public void expire(){try{commands.expireHolds(25);}catch(RuntimeException failure){log.warn("Commerce hold expiry deferred cause={}",failure.getClass().getSimpleName());}}
}
