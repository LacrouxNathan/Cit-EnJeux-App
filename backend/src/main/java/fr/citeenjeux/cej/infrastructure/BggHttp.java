package fr.citeenjeux.cej.infrastructure;

import java.io.*;
import java.net.*;
import org.springframework.stereotype.Component;

/** Transport borné, sans redirection ; les URI sont construites/validées par BggImportService. */
@Component
public class BggHttp {
    public record Reply(int status, byte[] body) { }
    public Reply get(URI uri, String token, int limit) throws IOException {
        var connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent","AppCeJ-BGG/1.0");
        connection.setRequestProperty("Accept",token==null?"image/png,image/jpeg":"application/xml");
        if(token!=null) connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            int status=connection.getResponseCode();
            if(status!=200)return new Reply(status,new byte[0]);
            if(connection.getContentLengthLong()>limit)throw new IOException("Response exceeds limit");
            try(var stream=connection.getInputStream()) {
                byte[] bytes=stream.readNBytes(limit+1);
                if(bytes.length>limit)throw new IOException("Response exceeds limit");
                return new Reply(status,bytes);
            }
        } finally {connection.disconnect();}
    }
}
