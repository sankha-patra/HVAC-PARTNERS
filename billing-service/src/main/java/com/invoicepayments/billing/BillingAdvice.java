package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.invoicepayments.billing.provider.InvalidProviderWebhookException;
import java.util.*;

@RestControllerAdvice(assignableTypes=BillingController.class)
@Profile("billing")
public class BillingAdvice {
    @ExceptionHandler(BillingProblem.class) ResponseEntity<?> problem(BillingProblem failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code",failure.code(),"message",failure.getMessage()));
    }
    @ExceptionHandler({MissingRequestHeaderException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class,InvalidProviderWebhookException.class})
    ResponseEntity<?> invalid(Exception failure) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","The request or authenticated provider evidence is invalid"));
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> uncertain(Exception failure) {
        String reference=UUID.randomUUID().toString();
        org.slf4j.LoggerFactory.getLogger(BillingAdvice.class).error("Billing request failed reference={}",reference,failure);
        return ResponseEntity.internalServerError().body(Map.of("code","OUTCOME_UNCERTAIN","reference",reference,
            "message","The operation could not be confirmed. Refresh its status before retrying."));
    }
}
