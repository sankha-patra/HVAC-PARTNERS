package com.invoicepayments.billing.notification;

import com.invoicepayments.billing.BillingStore;
import jakarta.mail.internet.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.MimeMessageHelper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed customer/staff templates. Internal correlation stays in the Message-ID header. */
public final class SmtpFinanceMailTransport implements FinanceMailTransport {
    private final JavaMailSenderImpl sender;private final String from,portal;private final boolean testMode;
    public SmtpFinanceMailTransport(Environment env){
        sender=new JavaMailSenderImpl();String host=env.getRequiredProperty("billing.notifications.smtp-host");
        int port=env.getProperty("billing.notifications.smtp-port",Integer.class,587);
        testMode=env.getProperty("billing.notifications.test-mode",Boolean.class,false);
        boolean local=Set.of("localhost","127.0.0.1","::1").contains(host);
        boolean lab=testMode&&env.getProperty("billing.demo-enabled",Boolean.class,false)&&!env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if(testMode&&!lab||local&&!lab)throw new IllegalArgumentException("Local capture and test email require a non-production demo");
        if(!local&&testMode&&env.getProperty("billing.notifications.allow-remote-test",Boolean.class,false)==false)
            throw new IllegalArgumentException("Test email requires an explicitly allowed remote transport");
        if(port<1||port>65535)throw new IllegalArgumentException("SMTP port invalid");
        if(!local&&port==465)throw new IllegalArgumentException("This SMTP transport requires STARTTLS, normally on port 587");
        sender.setHost(host);sender.setPort(port);sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        from=address(env.getRequiredProperty("billing.notifications.from"));
        portal=env.getRequiredProperty("billing.public-url");
        var uri=java.net.URI.create(portal);
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()
            ||!("https".equals(uri.getScheme())||testMode&&"http".equals(uri.getScheme())))throw new IllegalArgumentException("Notification portal must be one exact trusted origin");
        var p=sender.getJavaMailProperties();p.setProperty("mail.smtp.connectiontimeout","3000");p.setProperty("mail.smtp.timeout","5000");p.setProperty("mail.smtp.writetimeout","5000");
        if(!local){
            sender.setUsername(env.getRequiredProperty("billing.notifications.smtp-username"));sender.setPassword(env.getRequiredProperty("billing.notifications.smtp-password"));
            p.setProperty("mail.smtp.auth","true");p.setProperty("mail.smtp.starttls.enable","true");p.setProperty("mail.smtp.starttls.required","true");p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        }
    }
    static String address(String value){
        try{if(value==null||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException();var a=new InternetAddress(value,true);a.validate();if(!value.equals(a.getAddress())||a.getAddress().indexOf('@')<1)throw new IllegalArgumentException();return value;}
        catch(Exception invalid){throw new IllegalArgumentException("One plain email address is required");}
    }
    @Override public void send(Map<String,Object> n){
        BillingStore.requireNoTransaction();
        String kind=Objects.toString(n.get("eventType"));
        String title=switch(kind){
            case "CUSTOMER_PAYMENT_RECEIVED"->"Payment received";
            case "CUSTOMER_REFUND_CONFIRMED"->"Refund issued";
            case "REFUND_REQUESTED"->"Refund approval requested";
            case "CASE_REQUESTED_RELEASE"->"Review approval requested";
            case "REFUND_APPROVED","CASE_APPROVED_RELEASE"->"Approval recorded";
            case "REFUND_REJECTED","CASE_REJECTED_RELEASE"->"Request rejected";
            case "REFUND_REFUNDED"->"Refund confirmed";
            case "REFUND_FAILED","REFUND_RELEASED","FINANCE_REVIEW","CASE_REOPENED"->"Finance review required";
            default->throw new RejectedNotification();};
        boolean customer=kind.startsWith("CUSTOMER_");
        if(!Objects.equals(n.get("audience"),customer?"CUSTOMER":"FINANCE") || !(n.get("recipient") instanceof String))throw new RejectedNotification();
        try{
            var message=sender.createMimeMessage();message.setFrom(new InternetAddress(from));message.setRecipient(jakarta.mail.Message.RecipientType.TO,new InternetAddress(address(n.get("recipient").toString())));
            String order="";if(n.get("orderNumber") instanceof Number number){long value=number.longValue();if(value<=0)throw new IllegalArgumentException("Invalid public order number");order="Order #"+value;}
            message.setSubject((testMode?"[TEST] ":"")+"Carrier shop · "+title+(order.isEmpty()?"":" · "+order),"UTF-8");message.setSentDate(new Date());
            String amount="";
            if(customer){if(!(n.get("amountMinor") instanceof Number minor)||minor.longValue()<=0||!(n.get("currency") instanceof String currency)||!Set.of("USD","INR").contains(currency))throw new RejectedNotification();amount=currency+" "+java.math.BigDecimal.valueOf(minor.longValue(),2).toPlainString();}
            String body=switch(kind){
                case "CUSTOMER_PAYMENT_RECEIVED"->"Thank you. We received your payment of "+amount+"."+(order.isEmpty()?"":" View your orders for the latest order status.");
                case "CUSTOMER_REFUND_CONFIRMED"->"We have issued a refund of "+amount+". Your bank may take additional time to show it in your account.";
                case "REFUND_REQUESTED"->"A refund was requested. Sign in to review its status and any required approval. Requests must be approved by a different authorized colleague.";
                case "CASE_REQUESTED_RELEASE"->"Approval was requested for a payment review. Sign in to check its status. Requests must be approved by a different authorized colleague.";
                case "REFUND_APPROVED"->"A refund request was approved. Sign in to check whether the refund has been issued.";
                case "CASE_APPROVED_RELEASE"->"A payment review was approved. Sign in to see the latest outcome.";
                case "REFUND_REJECTED","CASE_REJECTED_RELEASE"->"A review request was declined. Sign in to see the decision and any next steps.";
                case "REFUND_REFUNDED"->"A refund has been processed. Sign in to view the details.";
                default->"A payment or refund was flagged for review. Sign in to check its status before taking any action.";
            };
            if(!order.isEmpty())body=order+". "+body;
            if(!customer && n.get("displayAmountMinor") instanceof Number minor && minor.longValue()>0 && n.get("displayCurrency") instanceof String currency && Set.of("USD","INR").contains(currency))body+=" Amount: "+currency+" "+java.math.BigDecimal.valueOf(minor.longValue(),2).toPlainString()+".";
            String label=customer?(order.isEmpty()?"Visit shop":"View orders"):"Review in finance",url=portal+(customer?(order.isEmpty()?"/shop":"/orders"):"/finance");
            String footer=customer?"Sign in to view your account.":"Sign in for the latest status. Decisions are made in the app, never by replying to this email.";
            String test=testMode?"Test email · demonstration environment.":"";
            String text="Carrier shop\n\n"+title+"\n\n"+body+"\n\n"+label+": "+url+"\n\n"+footer+(test.isEmpty()?"":"\n"+test);
            String html="<!doctype html><html lang=\"en\"><body style=\"margin:0;background:#f4f6f8;color:#1d2939;font-family:Arial,sans-serif\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td style=\"padding:32px 16px\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;margin:auto;background:white;border:1px solid #e4e7ec;border-radius:12px\"><tr><td style=\"padding:32px\"><p style=\"color:#155e75;font-size:16px;font-weight:bold;margin:0 0 28px\">Carrier shop</p><h1 style=\"font-size:26px;line-height:1.3;margin:0 0 20px\">"+escape(title)+"</h1><p style=\"font-size:16px;line-height:1.7\">"+escape(body)+"</p><p style=\"margin:28px 0\"><a href=\""+escape(url)+"\" style=\"display:inline-block;background:#155e75;color:white;padding:14px 22px;border-radius:6px;text-decoration:none;font-weight:bold\">"+label+"</a></p><p style=\"color:#667085;font-size:13px;line-height:1.6\">"+escape(footer)+"</p>"+(test.isEmpty()?"":"<p style=\"color:#667085;font-size:12px;border-top:1px solid #e4e7ec;padding-top:16px\">"+escape(test)+"</p>")+"</td></tr></table></td></tr></table></body></html>";
            new MimeMessageHelper(message,true,"UTF-8").setText(text,html);
            message.saveChanges();message.setHeader("Auto-Submitted","auto-generated");message.setHeader("Message-ID","<"+UUID.fromString(n.get("id").toString())+"@"+from.substring(from.indexOf('@')+1)+">");
            submit(message);
        }catch(IllegalArgumentException rejected){throw new RejectedNotification();}
        catch(jakarta.mail.MessagingException failure){throw new IllegalStateException("SMTP message failed",failure);}
    }
    private void submit(MimeMessage message){
        try{
            // sendMessage returning means DATA was accepted. Closing is a separate outcome.
            var transport=sender.getSession().getTransport("smtp");
            try{
                transport.connect(sender.getHost(),sender.getPort(),sender.getUsername(),sender.getPassword());
                transport.sendMessage(message,message.getAllRecipients());
            }finally{
                try{transport.close();}catch(jakarta.mail.MessagingException closeFailure){
                    org.slf4j.LoggerFactory.getLogger(SmtpFinanceMailTransport.class).debug("SMTP connection close failed; submission outcome is unchanged");
                }
            }
        }catch(jakarta.mail.MessagingException|RuntimeException failure){
            // An invalid transport/configuration exception is not evidence of an invalid template.
            throw new IllegalStateException("SMTP submission was not confirmed",failure);
        }
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
