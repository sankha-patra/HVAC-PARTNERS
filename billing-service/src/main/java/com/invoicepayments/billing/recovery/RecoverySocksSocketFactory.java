package com.invoicepayments.billing.recovery;
import javax.net.SocketFactory;
import java.net.*;
import java.io.IOException;
/** Only PostgreSQL uses the loopback SOCKS proxy; public Razorpay/SSM HTTPS use their normal TLS route. */
public final class RecoverySocksSocketFactory extends SocketFactory {
    @Override public Socket createSocket(){return new Socket(new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",1055)));}
    @Override public Socket createSocket(String host,int port)throws IOException{Socket s=createSocket();s.connect(new InetSocketAddress(host,port),3000);return s;}
    @Override public Socket createSocket(InetAddress host,int port)throws IOException{return createSocket(host.getHostAddress(),port);}
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(local,localPort));s.connect(new InetSocketAddress(host,port),3000);return s;}
    @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return createSocket(host.getHostAddress(),port,local,localPort);}
}
