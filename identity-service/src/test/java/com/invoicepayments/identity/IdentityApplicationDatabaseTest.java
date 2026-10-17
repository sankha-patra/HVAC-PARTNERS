package com.invoicepayments.identity;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/invoice_payments_test")
@SpringBootTest(classes=IdentityApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=billing","app.demo.users-enabled=true","spring.datasource.hikari.maximum-pool-size=3",
    "spring.datasource.hikari.minimum-idle=0","management.server.port=0","app.jwt.cookie-secure=false",
    "app.jwt.secret=identity-integration-test-secret-is-not-a-live-credential-123456",
    "spring.cloud.discovery.enabled=false","eureka.client.enabled=false","management.health.redis.enabled=false"
})
class IdentityApplicationDatabaseTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("BILLING_TEST_JDBC_URL"));
        registry.add("spring.datasource.username",()->System.getenv("BILLING_TEST_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("BILLING_TEST_DB_PASSWORD"));
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Test void isolatedIdentityStartsWithOnlyItsOwnEntity() {
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('identity.app_users')",Object.class));
        assertEquals(HttpStatus.NOT_FOUND,http.getForEntity("/api/v1/products",String.class).getStatusCode()==HttpStatus.UNAUTHORIZED
            ? authenticatedGet("/api/v1/products").getStatusCode():http.getForEntity("/api/v1/products",String.class).getStatusCode());
    }
    @Test void loginReturnsHttpOnlyCookieWithoutExposingCredentials() {
        var response=login("demo","demo123");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        String cookie=response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(Objects.requireNonNull(response.getBody()).contains("password"));
        assertFalse(response.getBody().contains("passwordHash"));assertFalse(response.getBody().contains("eyJ"));
    }
    @Test void wrongPasswordIsUnauthorizedRatherThanAnInternalError() {
        var response=login("demo","incorrect");assertEquals(HttpStatus.UNAUTHORIZED,response.getStatusCode());
        assertFalse(Objects.requireNonNull(response.getBody()).contains("passwordHash"));
    }
    @Test void unknownAndExistingUsersHaveTheSameFailureMessage() {
        assertEquals(login("unknown-user","incorrect").getBody(),login("demo","incorrect").getBody());
    }
    @Test void authenticatedProfileDoesNotExposePersistenceFields() {
        var response=authenticatedGet("/api/v1/auth/me");assertEquals(HttpStatus.OK,response.getStatusCode());
        assertFalse(Objects.requireNonNull(response.getBody()).contains("password"));
    }
    @Test void missingSessionCannotReadIdentity() {
        assertEquals(HttpStatus.UNAUTHORIZED,http.getForEntity("/api/v1/auth/me",String.class).getStatusCode());
    }
    @Test void reviewerAndOtherCustomerCanSignInSeparately() {
        assertEquals(HttpStatus.OK,login("reviewer","reviewer123").getStatusCode());
        assertEquals(HttpStatus.OK,login("other","other123").getStatusCode());
    }
    @Test void logoutExpiresTheAuthenticationCookie() {
        var response=http.postForEntity("/api/v1/auth/logout",null,String.class);
        assertEquals(HttpStatus.NO_CONTENT,response.getStatusCode());
        assertTrue(Objects.requireNonNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0"));
    }
    private ResponseEntity<String> login(String username,String password) {
        return http.postForEntity("/api/v1/auth/login",Map.of("username",username,"password",password),String.class);
    }
    private ResponseEntity<String> authenticatedGet(String path) {
        String cookie=Objects.requireNonNull(login("demo","demo123").getHeaders().getFirst(HttpHeaders.SET_COOKIE)).split(";",2)[0];
        var headers=new HttpHeaders();headers.add(HttpHeaders.COOKIE,cookie);
        return http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),String.class);
    }
}
