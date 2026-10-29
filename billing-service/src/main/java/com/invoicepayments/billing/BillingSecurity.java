package com.invoicepayments.billing;

import com.invoicepayments.security.JwtAuthFilter;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@Profile("billing")
@EnableMethodSecurity
public class BillingSecurity {
    @Bean SecurityFilterChain billingSecurityFilterChain(HttpSecurity http,JwtAuthFilter jwt,
        @Value("${billing.public-url:http://localhost:4200}") String origin,
        @Value("${app.jwt.cookie-secure:false}") boolean secure) throws Exception {
        var cookies=CookieCsrfTokenRepository.withHttpOnlyFalse();
        cookies.setCookieCustomizer(cookie->cookie.path("/").sameSite("Lax").secure(secure));
        http.csrf(csrf->csrf.csrfTokenRepository(cookies).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            // JWT authentication is re-established on each stateless request. Do not treat that
            // as a new login and expire the browser's CSRF cookie after every safe read.
            .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
            .requireCsrfProtectionMatcher(request -> {
                if(Set.of("GET","HEAD","OPTIONS","TRACE").contains(request.getMethod()) || Set.of("/api/v1/billing/webhooks/stripe","/api/v1/billing/webhooks/razorpay").contains(request.getRequestURI())) return false;
                if(request.getCookies()==null) return false;
                return Arrays.stream(request.getCookies()).anyMatch(cookie->"IP_AUTH".equals(cookie.getName()));
            }))
            .cors(cors->cors.configurationSource(cors(origin)))
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors->errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .requestMatchers("/error","/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/billing/webhooks/stripe","/api/v1/billing/webhooks/razorpay").permitAll()
                .requestMatchers("/api/v1/billing/**","/api/v1/commerce/**").authenticated()
                .requestMatchers("/actuator/**").hasAnyRole("ADMIN","INTERNAL")
                .anyRequest().denyAll())
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)
                    throws java.io.IOException,jakarta.servlet.ServletException {
                    Object token=request.getAttribute(CsrfToken.class.getName());
                    if(token instanceof CsrfToken csrfToken && !Set.of("/api/v1/billing/webhooks/stripe","/api/v1/billing/webhooks/razorpay").contains(request.getRequestURI())) csrfToken.getToken();
                    chain.doFilter(request,response);
                }
            },CsrfFilter.class);
        return http.build();
    }
    private CorsConfigurationSource cors(String origin) {
        var config=new CorsConfiguration();config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));config.setAllowedHeaders(List.of("Content-Type","Authorization","Idempotency-Key","Checkout-Key","X-XSRF-TOKEN"));config.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",config);return source;
    }
}
