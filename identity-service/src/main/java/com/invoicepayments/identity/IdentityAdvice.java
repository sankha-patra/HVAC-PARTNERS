package com.invoicepayments.identity;

import com.invoicepayments.identity.controller.AuthController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.Map;

@RestControllerAdvice(assignableTypes=AuthController.class)
public class IdentityAdvice {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> denied(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getStatusCode().value()==401?"Invalid credentials or expired session":"Request could not be completed"));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message","Provide a valid username and password"));
    }
}
