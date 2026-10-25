package com.invoicepayments.billing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Upgrade real pre-reference rows, not just a clean latest schema. The new database lives only inside the
 * allowlisted disposable PostgreSQL server and is removed with that test container. No application database.
 */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
class BillingPaymentReferenceMigrationDatabaseTest {
    @Test void existingPaymentsKeepTheirReferenceAndNewRowsStayWithinTheirAccount() throws Exception {
        String base=System.getenv("BILLING_TEST_JDBC_URL");
        assertThat(base).isEqualTo("jdbc:postgresql://127.0.0.1:15432/invoice_payments_test");
        assertThat(System.getenv("BILLING_TEST_DB_USER")).isEqualTo("billing_test");
        var server=new DriverManagerDataSource(base,"billing_test",System.getenv("BILLING_TEST_DB_PASSWORD"));
        String database="invoice_payments_upgrade_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        assertThat(database).matches("invoice_payments_upgrade_[0-9a-f]{16}");
        try(var connection=server.getConnection(); var statement=connection.createStatement()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            try(var result=statement.executeQuery("SELECT current_database(),current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("invoice_payments_test");
                assertThat(result.getString(2)).isEqualTo("billing_test");
            }
            statement.execute("CREATE DATABASE "+database);
        }
        String url=base.substring(0,base.lastIndexOf('/')+1)+database;
        var source=new DriverManagerDataSource(url,"billing_test",System.getenv("BILLING_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
            .locations("classpath:db/billing").target("21").cleanDisabled(true).load().migrate();
        var jdbc=new JdbcTemplate(source);
        UUID merchant=UUID.randomUUID(),account=UUID.randomUUID(),other=UUID.randomUUID(),legacy=UUID.randomUUID();
        jdbc.update("INSERT INTO billing.merchants(id,name) VALUES(?,'Upgrade proof')",merchant);
        jdbc.update("INSERT INTO billing.accounts(id,merchant_id,external_reference,name) VALUES(?,?,?,'Before upgrade')",account,merchant,"upgrade-one");
        jdbc.update("INSERT INTO billing.accounts(id,merchant_id,external_reference,name) VALUES(?,?,?,'Other account')",other,merchant,"upgrade-two");
        insert(jdbc,legacy,account);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='billing' AND table_name='intents' AND column_name='payment_reference'",Integer.class)).isZero();
        Flyway.configure().dataSource(source).schemas("billing").defaultSchema("billing")
            .locations("classpath:db/billing").cleanDisabled(true).load().migrate();
        assertThat(jdbc.queryForObject("SELECT payment_reference FROM billing.intents WHERE id=?",UUID.class,legacy)).isEqualTo(legacy);
        assertThat(jdbc.queryForObject("SELECT retry_of FROM billing.intents WHERE id=?",UUID.class,legacy)).isNull();
        UUID fresh=UUID.randomUUID(),foreign=UUID.randomUUID();
        insert(jdbc,fresh,account); insert(jdbc,foreign,other);
        assertThat(jdbc.queryForObject("SELECT payment_reference FROM billing.intents WHERE id=?",UUID.class,fresh)).isEqualTo(fresh);
        assertThatThrownBy(()->jdbc.update("UPDATE billing.intents SET payment_reference=? WHERE id=?",foreign,fresh))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE billing.intents SET retry_of=? WHERE id=?",foreign,fresh))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    static void insert(JdbcTemplate jdbc,UUID intent,UUID account) {
        jdbc.update("INSERT INTO billing.intents(id,account_id,idempotency_key,request_hash,amount_minor,currency,method,status,provider) VALUES(?,?,?,? ,10000,'USD','CARD','CREATED','SIMULATOR')",
            intent,account,UUID.randomUUID().toString(),"a".repeat(64));
    }
}
