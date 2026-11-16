package fr.citeenjeux.cej;

import fr.citeenjeux.cej.infrastructure.BggHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BggHttpTest {
    @Test void sendsBearerAndNeverFollowsRedirects() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();
        server.createContext("/start",exchange->{assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer dummy");exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{count.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {var response=new BggHttp().get(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/start"),"dummy",1024);assertThat(response.status()).isEqualTo(302);assertThat(count.get()).isZero();}
        finally{server.stop(0);}
    }
    @Test void capsStreamedBodiesAndOmitsTokenForImages() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var header=new java.util.concurrent.atomic.AtomicReference<String>();var called=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/image",exchange->{called.set(true);header.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write("1234567890".getBytes());}});server.start();
        try {assertThatThrownBy(()->new BggHttp().get(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/image"),null,5)).isInstanceOf(java.io.IOException.class);assertThat(called.get()).isTrue();assertThat(header.get()).isNull();}
        finally{server.stop(0);}
    }
}
