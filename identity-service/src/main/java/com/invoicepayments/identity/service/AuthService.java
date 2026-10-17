package com.invoicepayments.identity.service;

import com.invoicepayments.identity.dto.LoginRequest;
import com.invoicepayments.identity.dto.LoginResponse;
import com.invoicepayments.identity.dto.UserResponse;
import com.invoicepayments.identity.entity.AppUser;
import com.invoicepayments.identity.repository.AppUserRepository;
import com.invoicepayments.identity.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${app.jwt.cookie-secure:false}")
    private boolean cookieSecure;

    public LoginResponse login(LoginRequest request, HttpServletResponse response) {
        AppUser user = appUserRepository.findByUsername(request.getUsername().trim())
            .or(() -> appUserRepository.findByEmail(request.getUsername().trim()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())
            || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        String token = jwtService.createToken(
            String.valueOf(user.getId()),
            user.getUsername(),
            user.getRole()
        );
        writeAuthCookie(response, token, jwtService.ttlSeconds());

        return new LoginResponse(toDto(user), "Login successful");
    }

    public UserResponse me(String userId) {
        AppUser user = appUserRepository.findById(Long.parseLong(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        return toDto(user);
    }

    public void logout(HttpServletResponse response) {
        writeAuthCookie(response, "", 0);
    }

    private void writeAuthCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(JwtService.COOKIE_NAME, token == null ? "" : token)
            .httpOnly(true)
            .secure(cookieSecure)
            .path("/")
            .maxAge(Duration.ofSeconds(Math.max(maxAgeSeconds, 0)))
            .sameSite("Lax")
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private UserResponse toDto(AppUser user) {
        return new UserResponse(
            String.valueOf(user.getId()),
            user.getUsername(),
            user.getEmail(),
            user.getDisplayName(),
            user.getRole()
        );
    }
}
