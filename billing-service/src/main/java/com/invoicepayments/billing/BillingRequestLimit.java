package com.invoicepayments.billing;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

@Configuration(proxyBeanMethods=false)
@Profile("billing")
public class BillingRequestLimit {
    @Bean FilterRegistrationBean<OncePerRequestFilter> billingBodyLimit() {
        OncePerRequestFilter filter=new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/api/v1/billing/") || java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
            }
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
                if(request.getContentLengthLong()>262144) {response.sendError(413,"Request too large");return;}
                byte[] body=request.getInputStream().readNBytes(262145);
                if(body.length>262144) {response.sendError(413,"Request too large");return;}
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        var bytes=new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override public int read() {return bytes.read();}
                            @Override public boolean isFinished() {return bytes.available()==0;}
                            @Override public boolean isReady() {return true;}
                            @Override public void setReadListener(ReadListener listener) {throw new UnsupportedOperationException("Synchronous bounded billing requests only");}
                        };
                    }
                    @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
                },response);
            }
        };
        var registration=new FilterRegistrationBean<>(filter);registration.setOrder(Ordered.HIGHEST_PRECEDENCE+10);return registration;
    }
}
