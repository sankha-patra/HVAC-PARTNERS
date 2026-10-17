package com.invoicepayments.identity.dto;

/**
 * Login API response — user DTO only. JWT is set as an httpOnly cookie (not in JSON).
 */
public class LoginResponse {

    private UserResponse user;
    private String message;

    public LoginResponse() {
    }

    public LoginResponse(UserResponse user, String message) {
        this.user = user;
        this.message = message;
    }

    public UserResponse getUser() {
        return user;
    }

    public void setUser(UserResponse user) {
        this.user = user;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
