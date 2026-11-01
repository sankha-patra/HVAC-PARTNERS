package com.invoicepayments.billing.recovery;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

/** Runtime-only parameter reads. Neither build tools nor deployment metadata obtain decrypted values. */
final class RecoveryBootstrap {
    static final String TAILNET="/recovery-lab/tailscale-auth-key",DATABASE="/recovery-lab/database-auth",PROVIDER="/recovery-lab/razorpay-auth",STRIPE="/recovery-lab/stripe-auth";
    private static final Path DIRECTORY=Path.of("/tmp/payments-recovery-tailscale");
    private static final String SOCKET=DIRECTORY.resolve("tailscaled.sock").toString();
    private static Process daemon;
    private RecoveryBootstrap(){}
    static void validate(Map<String,String> env){
        if(!"ap-southeast-2".equals(env.get("AWS_REGION")))throw new IllegalArgumentException("Sydney recovery runtime required");
        if(!env.getOrDefault("AWS_LAMBDA_FUNCTION_VERSION","").matches("[1-9][0-9]*"))throw new IllegalArgumentException("An immutable numeric Lambda version is required");
        if(!"true".equals(env.get("RECOVERY_PRIVATE_ROUTE_READY")))throw new IllegalArgumentException("Reviewed private route required");
        UUID.fromString(env.getOrDefault("RECOVERY_CAMPAIGN_ID",""));
        String ip=env.getOrDefault("RECOVERY_LAPTOP_IP","");String database=env.getOrDefault("RECOVERY_DATABASE","");
        if(!ip.matches("100\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")||!database.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Exact private database route required");
        int[] parts=Arrays.stream(ip.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if(parts[1]<64||parts[1]>127||parts[2]>255||parts[3]>255||!("jdbc:postgresql://"+ip+":5432/"+database).equals(env.get("RECOVERY_JDBC_URL")))throw new IllegalArgumentException("Exact tailnet database route required");
    }
    static synchronized void ensureConnected(){
        validate(System.getenv());
        try{
            Files.createDirectories(DIRECTORY);Files.setPosixFilePermissions(DIRECTORY,PosixFilePermissions.fromString("rwx------"));
            if(daemon!=null&&daemon.isAlive()&&statusRunning())return;
            if(daemon!=null){daemon.destroyForcibly();daemon.waitFor(1,TimeUnit.SECONDS);}
            Files.deleteIfExists(Path.of(SOCKET));
            daemon=new ProcessBuilder("/opt/bin/tailscaled","--tun=userspace-networking","--state=mem:","--socket="+SOCKET,
                "--socks5-server=127.0.0.1:1055").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(!Files.exists(Path.of(SOCKET))&&System.nanoTime()<until&&daemon.isAlive())Thread.sleep(50);
            if(!Files.exists(Path.of(SOCKET)))throw new RecoveryTunnelUnavailableException("TUNNEL_START_FAILED");
            Path key=Files.createTempFile(DIRECTORY,"auth-",".key",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try{Files.writeString(key,read(TAILNET));login(List.of("/opt/bin/tailscale","--socket="+SOCKET,"up","--auth-key=file:"+key,
                "--advertise-tags=tag:recovery-lab","--hostname=payments-recovery-"+UUID.randomUUID().toString().substring(0,12),"--accept-dns=false","--accept-routes=false"));}
            finally{Files.deleteIfExists(key);}
            if(!statusRunning())throw new RecoveryTunnelUnavailableException("TUNNEL_LOGIN_FAILED");
            String laptop=System.getenv("RECOVERY_LAPTOP_IP");
            awaitPeer(()->ping(laptop),PEER_BUDGET,Duration.ofMillis(250));
        }catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();if(daemon!=null)daemon.destroyForcibly();
            throw failure instanceof RecoveryTunnelUnavailableException typed?typed:new RecoveryTunnelUnavailableException("TUNNEL_START_FAILED");
        }
    }
    static Map<String,String> runtimeConfiguration(){
        validate(System.getenv());var config=new HashMap<>(System.getenv());
        var database=new JSONObject(read(DATABASE));
        config.put("RECOVERY_DB_USER",database.getString("username"));config.put("RECOVERY_DB_PASSWORD",database.getString("password"));
        // Only the configured provider's credentials are read.
        if("STRIPE".equals(provider(config))){config.put("BILLING_STRIPE_SECRET_KEY",new JSONObject(read(STRIPE)).getString("secretKey"));return config;}
        var provider=new JSONObject(read(PROVIDER));
        config.put("BILLING_RAZORPAY_KEY_ID",provider.getString("keyId"));config.put("BILLING_RAZORPAY_KEY_SECRET",provider.getString("keySecret"));return config;
    }
    static String provider(Map<String,String> env){
        String provider=env.getOrDefault("BILLING_PROVIDER","RAZORPAY");
        if(!RecoverySettings.PROVIDERS.contains(provider))throw new IllegalArgumentException("Unsupported recovery provider");
        return provider;
    }
    private static String read(String name){
        if(!Set.of(TAILNET,DATABASE,PROVIDER,STRIPE).contains(name))throw new IllegalArgumentException("Unapproved recovery parameter");
        try(var client=SsmClient.builder().region(Region.AP_SOUTHEAST_2).credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(5)).retryPolicy(RetryPolicy.none()).build()).build()){
            String value=client.getParameter(GetParameterRequest.builder().name(name).withDecryption(true).build()).parameter().value();
            if(value==null||value.isBlank())throw new IllegalStateException("Runtime parameter unavailable");return value;
        }catch(RuntimeException failure){throw new RecoveryTunnelUnavailableException("PARAMETERS_UNAVAILABLE");}
    }
    static final Duration PEER_BUDGET=Duration.ofSeconds(8);
    /** "Running" means the control plane accepted the node, not that a WireGuard path to the laptop exists yet. */
    static void awaitPeer(BooleanSupplier reachable,Duration budget,Duration pause)throws InterruptedException{
        long until=System.nanoTime()+budget.toNanos();
        while(!reachable.getAsBoolean()){
            if(System.nanoTime()+pause.toNanos()>=until)throw new RecoveryTunnelUnavailableException("PEER_UNREACHABLE");
            Thread.sleep(pause.toMillis());
        }
    }
    // TSMP crosses WireGuard to the peer's tailscaled; a disco pong alone does not prove the data path.
    private static boolean ping(String ip){
        try{run(List.of("/opt/bin/tailscale","--socket="+SOCKET,"ping","--tsmp","--until-direct=false","--c=1","--timeout=2s",ip),3,false);return true;}
        catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();return false;}
    }
    private static void login(List<String> args)throws Exception{
        try{run(args,10,false);}catch(IllegalStateException failure){throw new RecoveryTunnelUnavailableException("TUNNEL_LOGIN_FAILED");}
    }
    private static boolean statusRunning()throws Exception{return "Running".equals(new JSONObject(run(List.of("/opt/bin/tailscale","--socket="+SOCKET,"status","--json"),2,true)).optString("BackendState"));}
    private static String run(List<String> args,int seconds,boolean capture)throws Exception{
        Path output=capture?Files.createTempFile(DIRECTORY,"status-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))):null;
        var builder=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);builder.redirectOutput(capture?ProcessBuilder.Redirect.to(output.toFile()):ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        try{if(!process.waitFor(seconds,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Bounded tunnel command failed");if(capture&&Files.size(output)>1048576)throw new IllegalStateException("Tunnel status output exceeds bound");return capture?Files.readString(output):"";}
        finally{if(process.isAlive())process.destroyForcibly();if(output!=null)Files.deleteIfExists(output);}
    }
}
