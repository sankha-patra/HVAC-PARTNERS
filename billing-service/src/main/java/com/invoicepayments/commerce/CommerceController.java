package com.invoicepayments.commerce;

import com.invoicepayments.billing.BillingProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Profile("billing")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${billing.role:combined}' != 'worker'")
@RequestMapping("/api/v1/commerce")
public class CommerceController {
    private final CommerceCommands commands;private final CommerceCatalog catalog;
    public CommerceController(CommerceCommands commands,CommerceCatalog catalog){this.commands=commands;this.catalog=catalog;}
    public record Lines(List<CommerceCommands.Line> lines){}
    public record Cart(long version,List<CommerceCommands.Line> lines){}
    @GetMapping("/accounts/{account}/catalog") public Object catalog(Authentication auth,@PathVariable UUID account,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="0") int page){return catalog.catalog(user(auth),account,category,page);}
    @GetMapping("/accounts/{account}/cart") public Object cart(Authentication auth,@PathVariable UUID account){return commands.cart(user(auth),account);}
    @PutMapping("/accounts/{account}/cart") public Object save(Authentication auth,@PathVariable UUID account,@RequestBody Cart request){if(request==null)throw BillingProblem.invalid("A basket is required");return commands.saveCart(user(auth),account,request.version(),request.lines());}
    @PostMapping("/accounts/{account}/quote") public Object quote(Authentication auth,@PathVariable UUID account,@RequestBody Lines request){if(request==null)throw BillingProblem.invalid("Choose products before requesting a quote");return commands.quote(user(auth),account,request.lines());}
    @PostMapping("/accounts/{account}/checkout") public Object checkout(Authentication auth,@PathVariable UUID account,@RequestHeader("Checkout-Key") String key,@RequestBody CommerceCommands.Checkout request){return commands.checkout(user(auth),account,key,request);}
    @GetMapping("/accounts/{account}/orders") public Object orders(Authentication auth,@PathVariable UUID account){return commands.orders(user(auth),account);}
    @GetMapping("/orders/{order}") public Object order(Authentication auth,@PathVariable UUID order){return commands.order(user(auth),order);}
    @PostMapping("/orders/{order}/cancel") public Object cancel(Authentication auth,@PathVariable UUID order){return commands.cancel(user(auth),order);}
    private static String user(Authentication auth){if(auth==null || !auth.isAuthenticated() || "internal".equals(auth.getName()))throw new BillingProblem(401,"UNAUTHENTICATED","Sign in to continue");return auth.getName();}
}
