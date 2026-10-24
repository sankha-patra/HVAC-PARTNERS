package com.invoicepayments.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/** Per-statement timeout without mutating a shared JdbcTemplate's settings per request. */
final class BillingJdbcTemplate extends JdbcTemplate {
    BillingJdbcTemplate(DataSource source) { super(source); setQueryTimeout(15); }
    @Override protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);
        BillingExecutionBudget.requireTime();
        Duration left = BillingExecutionBudget.remaining();
        if (left != null) {
            int seconds = (int) Math.max(1, Math.min(Integer.MAX_VALUE, left.toSeconds()));
            int existing = statement.getQueryTimeout();
            statement.setQueryTimeout(existing <= 0 ? seconds : Math.min(existing, seconds));
        }
    }
}
