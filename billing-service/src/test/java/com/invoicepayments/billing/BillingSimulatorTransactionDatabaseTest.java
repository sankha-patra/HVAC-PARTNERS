package com.invoicepayments.billing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import java.io.IOException;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingSimulatorTransactionDatabaseTest {
    @BeforeAll static void migrate() throws Exception {BillingFinancialDatabaseTest.migrateOnlyIsolatedDatabase();}
    @Test void simulatedProviderWritesUseIndependentUnlabelledTransactionProxies() throws Exception {
        try(var pool=BillingAnnotationHarnessDatabaseTest.pool();var context=BillingAnnotationHarnessDatabaseTest.context(pool)) {
            Class<?> type=Class.forName("com.invoicepayments.billing.provider.SimulatorOperationStore");
            context.registerBean(type);
            assertThat(AopUtils.isAopProxy(context.getBean(type))).isTrue();
            var source=context.getBean(TransactionAttributeSource.class);int writes=0;
            for(var method:type.getDeclaredMethods()) if(Set.of("submit","refund","closePayment","simulateState").contains(method.getName())) {
                var attribute=source.getTransactionAttribute(method,type);
                assertThat(attribute).isNotNull();assertThat(attribute.getPropagationBehavior()).isEqualTo(Propagation.REQUIRES_NEW.value());
                assertThat(attribute.getTimeout()).isEqualTo(10);assertThat(attribute.getLabels()).doesNotContain("billing");
                assertThat(attribute.rollbackOn(new IOException())).isTrue();writes++;
            }
            assertThat(writes).isEqualTo(4);
        }
    }
}
