package com.invoicepayments.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthGatewayFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;

    public JwtAuthGatewayFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
            .headers(headers -> headers.remove(JwtService.INTERNAL_TOKEN_HEADER))
            .build();
        ServerWebExchange forwarded = exchange.mutate().request(stripped).build();

        if (isPublic(forwarded)) {
            return chain.filter(forwarded);
        }

        String token = extractToken(forwarded.getRequest());
        if (token == null || !jwtService.isValid(token)) {
            forwarded.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return forwarded.getResponse().setComplete();
        }
        return chain.filter(forwarded);
    }

    private boolean isPublic(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method == HttpMethod.OPTIONS) {
            return true;
        }
        String path = exchange.getRequest().getURI().getPath();
        return path.startsWith("/actuator/")
            || path.equals("/actuator")
            || path.startsWith("/fallback/")
            || path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/auth/logout")
            || path.equals("/api/v1/billing/webhooks/stripe")
            || path.equals("/api/v1/billing/webhooks/razorpay");
    }

    private String extractToken(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(JwtService.COOKIE_NAME);
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
