package com.invoicepayments.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.invoicepayments.billing.provider.InvalidProviderWebhookException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestControllerAdvice(assignableTypes=BillingController.class)
@Profile("billing")
public class BillingAdvice {
    private final BillingStore db;
    public BillingAdvice(BillingStore db) { this.db=db; }
    @ExceptionHandler(BillingProblem.class) ResponseEntity<?> problem(BillingProblem failure,HttpServletRequest request) {
        if(failure.status()==403) recordDenied(failure,request);
        var response=ResponseEntity.status(failure.status());
        // Nothing was saved, so trying again later is safe; say when.
        if("PROVIDER_BUSY".equals(failure.code())) response.header("Retry-After","60");
        return response.body(Map.of("code",failure.code(),"message",failure.getMessage()));
    }
    /** The refused action already rolled back; record the refusal in its own statement so the audit survives. */
    private void recordDenied(BillingProblem failure,HttpServletRequest request) {
        try {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            if(auth==null || !auth.isAuthenticated() || auth.getName()==null) return;
            String actor=auth.getName(), path=request.getRequestURI();
            db.update("INSERT INTO billing.denied_actions(id,actor,method,path,code) VALUES(?,?,?,?,?)",UUID.randomUUID(),
                actor.length()>160?actor.substring(0,160):actor,request.getMethod(),path.length()>500?path.substring(0,500):path,failure.code());
        } catch(RuntimeException unrecorded) {
            // The refusal stands either way; a lost audit row must be visible to operators.
            org.slf4j.LoggerFactory.getLogger(BillingAdvice.class).error("Could not record a denied action code={}",failure.code(),unrecorded);
        }
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
