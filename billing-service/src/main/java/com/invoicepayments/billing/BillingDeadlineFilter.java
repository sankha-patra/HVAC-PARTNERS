package com.invoicepayments.billing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;

/** Starts before authentication and database acquisition; does not interrupt a committing writer. */
@Component
@Profile("billing")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class BillingDeadlineFilter extends OncePerRequestFilter {
    private final Duration budget;
    public BillingDeadlineFilter(@Value("${billing.request-budget:25s}") String configuredBudget) {
        Duration budget = org.springframework.boot.convert.DurationStyle.detectAndParse(configuredBudget);
        if (budget.compareTo(Duration.ofSeconds(10)) < 0 || budget.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Billing request budget must be between 10 and 30 seconds");
        this.budget = budget;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/v1/billing/") || request.getRequestURI().startsWith("/api/v1/commerce/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try (var ignored = BillingExecutionBudget.limit(budget)) { chain.doFilter(request, response); }
    }
}
