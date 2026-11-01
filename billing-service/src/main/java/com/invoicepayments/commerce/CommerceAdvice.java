package com.invoicepayments.commerce;

import com.invoicepayments.billing.BillingProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice(assignableTypes=CommerceController.class) @Profile("billing")
public class CommerceAdvice {
    @ExceptionHandler(BillingProblem.class) public ResponseEntity<?> problem(BillingProblem failure){return ResponseEntity.status(failure.status()).body(Map.of("code",failure.code(),"message",failure.getMessage()));}
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    public ResponseEntity<?> invalid(Exception failure){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Check the company, product quantities and saved basket version"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> uncertain(Exception failure){
        String reference=UUID.randomUUID().toString();org.slf4j.LoggerFactory.getLogger(CommerceAdvice.class).error("Commerce request failed reference={}",reference,failure);
        if(com.invoicepayments.billing.OperationalAlert.databaseUnavailable(failure))com.invoicepayments.billing.OperationalAlert.raiseAtMostEvery(java.time.Duration.ofMinutes(1),"shop","DATABASE_UNAVAILABLE",
            com.invoicepayments.billing.OperationalAlert.Severity.CRITICAL,"A shop request could not reach the database and failed safely; the buyer is told to check the saved order","source","shop","reference",reference);
        return ResponseEntity.internalServerError().body(Map.of("code","OUTCOME_UNCERTAIN","reference",reference,"message","The result could not be confirmed. Check the same saved request before placing another order."));
    }
}
