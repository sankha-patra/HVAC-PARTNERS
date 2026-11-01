package com.invoicepayments.billing.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import com.amazonaws.services.lambda.runtime.Context;
import com.invoicepayments.billing.provider.BillingProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryInputTest {
    @Test void invalidInjectedInputCannotReachBootstrapOrRuntime(){
        var boot=new AtomicInteger();var runtimes=new AtomicInteger();
        var handler=new RecoveryLambdaHandler(boot::incrementAndGet,()->{runtimes.incrementAndGet();return mock(RecoveryLambdaRuntime.class);});
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","PAYMENT","operationId","bad"),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","PAYMENT","operationId",UUID.randomUUID().toString(),"fault","EXIT"),null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(boot).hasValue(0);assertThat(runtimes).hasValue(0);
    }
    @Test void injectedRuntimeStillRequiresBootstrapAndReceivesOnlySavedIdentity(){
        var boot=new AtomicInteger();var runtime=mock(RecoveryLambdaRuntime.class);var operation=UUID.randomUUID();
        var handler=new RecoveryLambdaHandler(boot::incrementAndGet,()->{assertThat(boot).hasValue(1);return runtime;});
        when(runtime.run(eq("PAYMENT"),eq(operation),any(BooleanSupplier.class))).thenAnswer(call->{assertThat(call.getArgument(2,BooleanSupplier.class).getAsBoolean()).isTrue();return Map.of("recoverable",false);});
        assertThat(handler.handleRequest(Map.of("kind","PAYMENT","operationId",operation.toString()),null)).containsEntry("recoverable",false);
        verify(runtime).run(eq("PAYMENT"),eq(operation),any(BooleanSupplier.class));
    }
    @Test void bootstrapFailureCannotReachTheInjectedRuntime(){
        var runtimes=new AtomicInteger();var handler=new RecoveryLambdaHandler(()->{throw new IllegalStateException("route unavailable");},()->{runtimes.incrementAndGet();return mock(RecoveryLambdaRuntime.class);});
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","PAYMENT","operationId",UUID.randomUUID().toString()),null)).isInstanceOf(IllegalStateException.class);
        assertThat(runtimes).hasValue(0);
    }
    @Test void remainingLambdaBudgetStillDefersClaimAdmission(){
        var runtime=mock(RecoveryLambdaRuntime.class);var operation=UUID.randomUUID();var lambda=mock(Context.class);
        when(lambda.getRemainingTimeInMillis()).thenReturn(19999);
        new RecoveryLambdaHandler(()->{},()->runtime).handleRequest(Map.of("kind","REFUND","operationId",operation.toString()),lambda);
        verify(runtime).run(eq("REFUND"),eq(operation),argThat((BooleanSupplier admit)->!admit.getAsBoolean()));verifyNoMoreInteractions(runtime);
    }
    @Test void testInjectionCannotChangeTheProviderProtocol(){
        var provider=mock(BillingProvider.class);when(provider.name()).thenReturn("SIMULATOR");
        assertThatThrownBy(()->new RecoveryLambdaRuntime(Map.of(),provider)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Razorpay or Stripe protocol");
    }
    @Test void malformedLambdaInputIsRejectedBeforeAnyDatabaseOrProviderSetup(){
        var handler=new RecoveryLambdaHandler();
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","PAYMENT","operationId","bad"),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","PAYMENT","operationId",UUID.randomUUID().toString(),"amountMinor",100),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->handler.handleRequest(Map.of("kind","ANYTHING","operationId",UUID.randomUUID().toString()),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unversionedOrOtherRegionWorkflowCannotBeSelected(){
        for(String arn:List.of("arn:aws:states:ap-southeast-2:123456789012:stateMachine:lab","arn:aws:states:us-east-1:123456789012:stateMachine:lab:1","arn:aws:states:ap-southeast-2:123456789012:stateMachine:lab:alias"))
            assertThatThrownBy(()->new RecoverySettings(UUID.randomUUID(),arn,8,16,100,1,Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void localProviderLoopMustBeDisabledForCloudRecoveryOwnership(){assertThatThrownBy(()->RecoverySettings.from(new MockEnvironment())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recovery-enabled=false");}
    @Test void executionIdentityIsTheSameNameWithoutTheStateMachineVersion(){
        var settings=new RecoverySettings(UUID.randomUUID(),"arn:aws:states:ap-southeast-2:123456789012:stateMachine:lab:7",8,16,100,1,Instant.now());
        assertThat(settings.executionArn("same-name")).isEqualTo("arn:aws:states:ap-southeast-2:123456789012:execution:lab:same-name");
    }
    @Test void prewarmRunsOnlyInsideLambdaAndNeverFailsInit(){
        var boot=new AtomicInteger();var runtimes=new AtomicInteger();
        var handler=new RecoveryLambdaHandler(boot::incrementAndGet,()->{runtimes.incrementAndGet();return mock(RecoveryLambdaRuntime.class);});
        handler.prewarm(Map.of());assertThat(boot).hasValue(0);assertThat(runtimes).hasValue(0);
        handler.prewarm(Map.of("AWS_LAMBDA_FUNCTION_NAME","recovery-lab-recover"));assertThat(boot).hasValue(1);assertThat(runtimes).hasValue(1);
        var failing=new RecoveryLambdaHandler(()->{throw new IllegalStateException("tunnel down");},()->{runtimes.incrementAndGet();return mock(RecoveryLambdaRuntime.class);});
        assertThatCode(()->failing.prewarm(Map.of("AWS_LAMBDA_FUNCTION_NAME","recovery-lab-recover"))).doesNotThrowAnyException();
        assertThat(runtimes).hasValue(1);
    }
    @Test void peerWaitReturnsOnceTheLaptopAnswers() throws Exception{
        var attempts=new AtomicInteger();
        RecoveryBootstrap.awaitPeer(()->attempts.incrementAndGet()>=3,java.time.Duration.ofSeconds(2),java.time.Duration.ofMillis(10));
        assertThat(attempts).hasValue(3);
    }
    @Test void peerWaitIsBoundedWhenTheLaptopNeverAnswers(){
        var attempts=new AtomicInteger();long start=System.nanoTime();
        assertThatThrownBy(()->RecoveryBootstrap.awaitPeer(()->{attempts.incrementAndGet();return false;},java.time.Duration.ofMillis(200),java.time.Duration.ofMillis(20)))
            .isInstanceOf(RecoveryTunnelUnavailableException.class).hasMessage("PEER_UNREACHABLE");
        assertThat(System.nanoTime()-start).isLessThan(java.time.Duration.ofSeconds(1).toNanos());assertThat(attempts.get()).isBetween(2,11);
    }
    @Test void bootstrapAllowsOnlyTheExactReviewedTailnetDatabaseRoute(){
        var env=new HashMap<>(Map.of("AWS_REGION","ap-southeast-2","AWS_LAMBDA_FUNCTION_VERSION","2","RECOVERY_PRIVATE_ROUTE_READY","true","RECOVERY_CAMPAIGN_ID",UUID.randomUUID().toString(),"RECOVERY_LAPTOP_IP","100.100.20.30","RECOVERY_DATABASE","commerce_lab","RECOVERY_JDBC_URL","jdbc:postgresql://100.100.20.30:5432/commerce_lab"));
        assertThatCode(()->RecoveryBootstrap.validate(env)).doesNotThrowAnyException();
        for(var bad:Map.of("AWS_REGION","us-east-1","AWS_LAMBDA_FUNCTION_VERSION","$LATEST","RECOVERY_JDBC_URL","jdbc:postgresql://100.100.20.30:5432/commerce_lab?socketFactory=bad","RECOVERY_LAPTOP_IP","1.2.3.4","RECOVERY_DATABASE","billing/other").entrySet()){
            var candidate=new HashMap<>(env);candidate.put(bad.getKey(),bad.getValue());assertThatThrownBy(()->RecoveryBootstrap.validate(candidate)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
