package com.invoicepayments.billing;

import com.invoicepayments.billing.provider.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.util.UUID;

/** Actual child application JVM used only by the isolated process-failure test. */
public final class BillingCrashChildMain {
    public static void main(String[] args) throws Exception {
        if(!"jdbc:postgresql://127.0.0.1:15432/invoice_payments_test".equals(System.getenv("BILLING_TEST_JDBC_URL"))
            ||!"billing_test".equals(System.getenv("BILLING_TEST_DB_USER")))throw new IllegalStateException("Disposable database only");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("BILLING_TEST_JDBC_URL"));
        config.setUsername("billing_test");config.setPassword(System.getenv("BILLING_TEST_DB_PASSWORD"));
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2000);
        config.addDataSourceProperty("ApplicationName","billing-crash-child");
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);var db=new BillingStore(jdbc);
            var provider=StripeCrashProviderClient.create(URI.create(args[1]));
            var payments=BillingTestWiring.payments(db,provider,new MockEnvironment(),false,"http://localhost:4200",BillingFinancialDatabaseTest.MERCHANT.toString());
            UUID intent=UUID.fromString(args[2]);
            if("submit".equals(args[0]))payments.submit(BillingFinancialDatabaseTest.PAYER,intent);
            else {
                var refunds=BillingTestWiring.refunds(db,payments,provider);
                var recovery=BillingTestWiring.recovery(db,payments,refunds,provider);
                var claims=recovery.claimPayments(1);
                if(claims.size()!=1||!intent.toString().equals(claims.get(0).get("id")))throw new IllegalStateException("Expected original saved payment claim");
                recovery.recoverPayment(claims.get(0));
            }
            BillingTestWiring.closeContexts();
        }
    }
}
