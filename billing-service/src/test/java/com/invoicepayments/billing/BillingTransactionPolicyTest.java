package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.BillingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.*;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** Enumerates effective production attributes so a missing label/override cannot silently lose a deadline. */
class BillingTransactionPolicyTest {
    @Test void everyCommandAndFinancialParticipantHasTheRequiredEffectivePolicy() throws Exception {
        var env=new MockEnvironment().withProperty("billing.demo-enabled","true");env.setActiveProfiles("billing");
        var scanner=new ClassPathScanningCandidateComponentProvider(false);scanner.setEnvironment(env);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.invoicepayments\\.billing\\.(Billing.*(Commands|Participants|Locks)|BillingStore|BillingDemoSeeder)")));
        var source=new AnnotationTransactionAttributeSource();int entries=0,classes=0;
        var storeWrites=Set.of("requireCommandSlot","event","journal","publish","caseFor","escalate","resolveCases","caseAction");
        for(var definition:scanner.findCandidateComponents("com.invoicepayments.billing")) {
            Class<?> type=Class.forName(definition.getBeanClassName());boolean participant=type==BillingStore.class||type.getSimpleName().endsWith("Locks")||type.getSimpleName().endsWith("Participants");
            assertThat(Modifier.isFinal(type.getModifiers())).as(type.getName()).isFalse();classes++;
            if(type.getSimpleName().endsWith("Commands"))for(var field:type.getDeclaredFields()) {
                assertThat(BillingProvider.class.isAssignableFrom(field.getType())).as("provider transport in "+type).isFalse();
                assertThat(Set.of(BillingService.class,BillingRefundService.class,BillingCaseService.class,BillingInbox.class,BillingRecovery.class,BillingWorkers.class))
                    .as("flow dependency in "+type).doesNotContain(field.getType());
            }
            for(var method:type.getDeclaredMethods()) {
                if(!Modifier.isPublic(method.getModifiers())||Modifier.isStatic(method.getModifiers())||method.isSynthetic())continue;
                if(type==BillingStore.class&&!storeWrites.contains(method.getName()))continue;
                var attribute=source.getTransactionAttribute(method,type);
                assertThat(attribute).as(method.toString()).isNotNull();
                assertThat(attribute.getQualifier()).isEqualTo("transactionManager");assertThat(attribute.getLabels()).contains("billing");
                assertThat(attribute.getPropagationBehavior()).isEqualTo((participant?Propagation.MANDATORY:Propagation.REQUIRED).value());
                assertThat(attribute.getTimeout()).isEqualTo(participant?-1:10);
                assertThat(attribute.rollbackOn(new IOException())).isTrue();assertThat(attribute.rollbackOn(new AssertionError())).isTrue();
                assertThat(Modifier.isFinal(method.getModifiers())).isFalse();
                for(Class<?> parameter:method.getParameterTypes())assertThat(parameter.getName()).doesNotStartWith("java.util.function.");
                entries++;
            }
        }
        assertThat(classes).isGreaterThanOrEqualTo(10);assertThat(entries).isGreaterThanOrEqualTo(51);
    }
    @Test void productionStorageNoLongerOwnsATransactionTemplate() {
        assertThat(Arrays.stream(BillingStore.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("tx");
        assertThat(Arrays.stream(BillingStore.class.getDeclaredFields()).map(field->field.getType().getName())).doesNotContain("org.springframework.transaction.support.TransactionTemplate");
        assertThat(Arrays.stream(com.invoicepayments.billing.provider.SimulatorBillingProvider.class.getDeclaredFields()).map(field->field.getType().getName())).doesNotContain("org.springframework.transaction.support.TransactionTemplate");
    }
}
