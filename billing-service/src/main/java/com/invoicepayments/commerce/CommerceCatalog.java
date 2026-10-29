package com.invoicepayments.commerce;

import com.invoicepayments.billing.*;
import org.json.JSONObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.invoicepayments.billing.BillingStore.*;

/** Cache entries are addressed by the committed database revision. Redis is never checkout authority. */
@Service @Profile("billing")
public class CommerceCatalog {
    public interface Cache {String get(String key);void put(String key,String value,Duration ttl);}
    private final BillingStore db;private final BillingService payments;private final Cache cache;
    private final Semaphore fallback=new Semaphore(4,true);
    private final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> loading=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public CommerceCatalog(BillingStore db,BillingService payments,ObjectProvider<StringRedisTemplate> redis,@Value("${commerce.cache-enabled:true}") boolean enabled){
        this(db,payments,enabled?new Cache(){
            public String get(String key){var client=redis.getIfAvailable();return client==null?null:client.opsForValue().get(key);}
            public void put(String key,String value,Duration ttl){var client=redis.getIfAvailable();if(client!=null)client.opsForValue().set(key,value,ttl);}
        }:null);
    }
    public CommerceCatalog(BillingStore db,BillingService payments,Cache cache){this.db=db;this.payments=payments;this.cache=cache;}
    public Map<String,Object> catalog(String user,UUID account,String category,int page){
        requireNoTransaction();payments.authorize(user,account);
        if(category==null || category.length()>80 || page<0 || page>1000)throw BillingProblem.invalid("A valid category and page are required");
        // COUNT of append-only committed rows changes even when transactions commit out of sequence.
        // MAX(sequence) would miss a lower-numbered invalidation that commits after a higher one.
        var scope=db.one("SELECT p.id,p.currency,p.merchant_id,(SELECT COUNT(*) FROM commerce.catalog_versions v WHERE v.merchant_id=p.merchant_id) AS revision FROM commerce.account_price_lists a JOIN commerce.price_lists p ON p.id=a.price_list_id WHERE a.account_id=?",account);
        String key="cat:v2:"+scope.get("merchantId")+":"+scope.get("id")+":"+scope.get("currency")+":"+scope.get("revision")+":"+hash(category)+":"+page;
        if(cache!=null)try{String hit=cache.get(key);if(hit!=null)return new JSONObject(hit).toMap();}catch(RuntimeException ignored){/* A failed cache cannot decide availability or checkout. */}
        var mine=new CompletableFuture<Map<String,Object>>();var existing=loading.putIfAbsent(key,mine);
        if(existing!=null)try{return existing.get(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw busy();}catch(ExecutionException|TimeoutException failure){throw busy();}
        boolean permit=false;
        try{
            permit=fallback.tryAcquire(50,TimeUnit.MILLISECONDS);if(!permit)throw busy();
            var items=db.rows("SELECT p.id,p.sku,p.name,p.category,r.unit_price_minor,r.version AS price_version,GREATEST(s.saleable_qty-s.reserved_qty,0) AS available_qty FROM commerce.prices r JOIN commerce.products p ON p.id=r.product_id JOIN commerce.stock s ON s.product_id=p.id WHERE r.price_list_id=? AND p.active AND (?='' OR p.category=?) ORDER BY p.name,p.id LIMIT 24 OFFSET ?",id(scope.get("id")),category,category,page*24);
            Map<String,Object> result=Map.of("currency",scope.get("currency"),"items",items,"page",page,"pageSize",24);
            if(cache!=null)try{cache.put(key,new JSONObject(result).toString(),Duration.ofSeconds(300+ThreadLocalRandom.current().nextInt(61)));}catch(RuntimeException ignored){}
            mine.complete(result);return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();mine.completeExceptionally(interrupted);throw busy();}
        catch(RuntimeException failure){mine.completeExceptionally(failure);throw failure;}
        finally{if(permit)fallback.release();loading.remove(key,mine);}
    }
    private static BillingProblem busy(){return new BillingProblem(503,"CATALOG_BUSY","The catalog is busy. Please try again shortly");}
}
