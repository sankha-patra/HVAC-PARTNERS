package com.invoicepayments.billing.notification;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SmtpFinanceMailTransportTest {
    MockEnvironment settings(int port){return new MockEnvironment().withProperty("billing.demo-enabled","true").withProperty("billing.notifications.smtp-host","127.0.0.1").withProperty("billing.notifications.smtp-port",Integer.toString(port)).withProperty("billing.notifications.test-mode","true").withProperty("billing.notifications.from","lab@example.test").withProperty("billing.public-url","http://localhost:4200");}
    Map<String,Object> notification(){return Map.of("id",UUID.randomUUID().toString(),"eventType","REFUND_REQUESTED","audience","FINANCE","recipient","finance@example.test","intentId",UUID.randomUUID().toString(),"operationId",UUID.randomUUID().toString());}
    @Test void actualSmtpWireHasStableIdentityAndNoApprovalToken() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(8000);var executor=Executors.newSingleThreadExecutor();
            try{
                var mail=executor.submit(()->receive(server));var n=notification();new SmtpFinanceMailTransport(settings(server.getLocalPort())).send(n);
                String wire=mail.get(8,TimeUnit.SECONDS);
                assertThat(wire).contains("Message-ID: <"+n.get("id")+"@example.test>","To: finance@example.test");
                var parsed=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
                assertThat(parsed.getSubject()).isEqualTo("[TEST] Carrier shop · Refund approval requested");
                String text=content(parsed,"text/plain"),html=content(parsed,"text/html");
                assertThat(text).contains("A refund was requested","different authorized colleague","http://localhost:4200/finance").doesNotContain("approve?","token=","Fictional return",n.get("operationId").toString(),"Event:","Transition version:");
                assertThat(html).contains("<html lang=\"en\">","Review in finance","href=\"http://localhost:4200/finance\"").doesNotContain("<script","<img",n.get("intentId").toString());
            }finally{executor.shutdownNow();}
        }
    }
    @Test void rejectsRecipientHeaderInjection(){assertThatThrownBy(()->SmtpFinanceMailTransport.address("safe@example.test\r\nBcc: other@example.test")).isInstanceOf(IllegalArgumentException.class);}
    @Test void testAndLocalCaptureAreRejectedBeforeWorkersCanStartOutsideDemo(){
        assertThatThrownBy(()->new SmtpFinanceMailTransport(settings(1025).withProperty("billing.demo-enabled","false"))).isInstanceOf(IllegalArgumentException.class);
        var prod=settings(1025);prod.setActiveProfiles("prod");
        assertThatThrownBy(()->new SmtpFinanceMailTransport(prod)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SmtpFinanceMailTransport(prod.withProperty("billing.notifications.test-mode","false"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptedDataIsNotResentBecauseTheServerDropsTheQuitReply() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(8000);var executor=Executors.newSingleThreadExecutor();try{
                var mail=executor.submit(()->receive(server,true,new ArrayList<>()));
                assertThatCode(()->new SmtpFinanceMailTransport(settings(server.getLocalPort())).send(notification())).doesNotThrowAnyException();
                assertThat(mail.get(8,TimeUnit.SECONDS)).contains("Message-ID:");
            }finally{executor.shutdownNow();}
        }
    }
    @Test void customerReceiptUsesConfirmedMinorUnitsAndDoesNotPromiseOrderOrBankSettlement() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(8000);var executor=Executors.newSingleThreadExecutor();try{
                var mail=executor.submit(()->receive(server));var n=new HashMap<>(notification());n.put("eventType","CUSTOMER_PAYMENT_RECEIVED");n.put("audience","CUSTOMER");n.put("amountMinor",10000L);n.put("currency","INR");n.put("orderNumber",10042L);
                new SmtpFinanceMailTransport(settings(server.getLocalPort())).send(n);String wire=mail.get(8,TimeUnit.SECONDS);
                var parsed=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
                assertThat(parsed.getSubject()).contains("Payment received","Order #10042");
                assertThat(content(parsed,"text/plain")).contains("We received your payment of INR 100.00","View orders: http://localhost:4200/orders").doesNotContain("colleague","order is confirmed","Event:","Operation:",n.get("id").toString());
                assertThat(content(parsed,"text/html")).contains("INR 100.00","View orders").doesNotContain("<script","<img");
            }finally{executor.shutdownNow();}
        }
    }
    @Test void refundConfirmationDoesNotInventBankPostingDate() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(8000);var executor=Executors.newSingleThreadExecutor();try{
                var mail=executor.submit(()->receive(server));var n=new HashMap<>(notification());n.put("eventType","CUSTOMER_REFUND_CONFIRMED");n.put("audience","CUSTOMER");n.put("amountMinor",125L);n.put("currency","USD");
                new SmtpFinanceMailTransport(settings(server.getLocalPort())).send(n);
                var parsed=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),new ByteArrayInputStream(mail.get(8,TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
                assertThat(content(parsed,"text/plain")).contains("issued a refund of USD 1.25","Your bank may take additional time","Visit shop: http://localhost:4200/shop").doesNotContain("days","approved","Event:","View orders");
            }finally{executor.shutdownNow();}
        }
    }
    @Test void mismatchedAudienceAndInvalidCustomerContentAreRejectedBeforeAnySmtpConnection() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(150);var transport=new SmtpFinanceMailTransport(settings(server.getLocalPort()));
            var n=new HashMap<>(notification());n.put("audience","CUSTOMER");
            assertThatThrownBy(()->transport.send(n)).isInstanceOf(FinanceMailTransport.RejectedNotification.class);
            n.put("eventType","CUSTOMER_PAYMENT_RECEIVED");
            assertThatThrownBy(()->transport.send(n)).isInstanceOf(FinanceMailTransport.RejectedNotification.class);
            n.put("amountMinor",100L);n.put("currency","JPY");
            assertThatThrownBy(()->transport.send(n)).isInstanceOf(FinanceMailTransport.RejectedNotification.class);
            n.put("currency","INR");n.put("orderNumber",0L);
            assertThatThrownBy(()->transport.send(n)).isInstanceOf(FinanceMailTransport.RejectedNotification.class);
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }
    static String content(jakarta.mail.Part part,String type) throws Exception {
        if(part.isMimeType(type))return part.getContent().toString();
        if(part.getContent() instanceof jakarta.mail.Multipart multipart){StringBuilder result=new StringBuilder();for(int i=0;i<multipart.getCount();i++)result.append(content(multipart.getBodyPart(i),type));return result.toString();}
        return "";
    }
    @Test void remoteTestTransportRequiresExplicitOptIn(){assertThatThrownBy(()->new SmtpFinanceMailTransport(settings(587).withProperty("billing.notifications.smtp-host","smtp.example.test"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void implicitTlsPortIsRejectedRatherThanSilentlyTimingOutEveryDelivery(){
        var config=settings(465).withProperty("billing.notifications.smtp-host","smtp.example.test").withProperty("billing.notifications.allow-remote-test","true");
        assertThatThrownBy(()->new SmtpFinanceMailTransport(config)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STARTTLS");
    }
    @Test void remoteSubmissionCannotFallBackToPlaintextWhenStartTlsIsMissing() throws Exception {
        // 127.0.0.2 is loopback, deliberately treated as a remote relay by the transport's strict allowlist.
        try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.2"))){
            server.setSoTimeout(8000);var executor=Executors.newSingleThreadExecutor();try{
                var commands=new ArrayList<String>();var mail=executor.submit(()->receive(server,false,commands));var config=settings(server.getLocalPort()).withProperty("billing.notifications.smtp-host","127.0.0.2")
                    .withProperty("billing.notifications.allow-remote-test","true").withProperty("billing.notifications.smtp-username","synthetic-user").withProperty("billing.notifications.smtp-password","synthetic-password");
                assertThatThrownBy(()->new SmtpFinanceMailTransport(config).send(notification())).isInstanceOf(RuntimeException.class);
                assertThat(mail.get(8,TimeUnit.SECONDS)).isEmpty();
                assertThat(commands).noneMatch(command->command.startsWith("AUTH")||command.startsWith("DATA"));
            }finally{executor.shutdownNow();}
        }
    }
    @Test void rejectsUntrustedPortalAndUnknownTemplate(){assertThatThrownBy(()->new SmtpFinanceMailTransport(settings(587).withProperty("billing.public-url","http://user@evil.test"))).isInstanceOf(IllegalArgumentException.class);var n=new HashMap<>(notification());n.put("eventType","FREE_TEXT");assertThatThrownBy(()->new SmtpFinanceMailTransport(settings(587)).send(n)).isInstanceOf(IllegalArgumentException.class);}
    static String receive(ServerSocket server) throws Exception {
        return receive(server,false,new ArrayList<>());
    }
    static String receive(ServerSocket server,boolean dropQuit,List<String> commands) throws Exception {
        try(var socket=server.accept();var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var output=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII),true)){
            socket.setSoTimeout(8000);output.print("220 localhost test SMTP\r\n");output.flush();StringBuilder body=new StringBuilder();String line;boolean data=false;
            while((line=input.readLine())!=null){
                if(!data)commands.add(line);
                if(data){if(line.equals(".")){data=false;output.print("250 accepted\r\n");}else{body.append(line).append("\r\n");continue;}}
                else if(line.startsWith("DATA")){data=true;output.print("354 send\r\n");}
                else if(line.startsWith("QUIT")){if(dropQuit){socket.setSoLinger(true,0);break;}output.print("221 closing\r\n");output.flush();break;}
                else output.print("250 OK\r\n");output.flush();
            }
            return body.toString();
        }
    }
}
