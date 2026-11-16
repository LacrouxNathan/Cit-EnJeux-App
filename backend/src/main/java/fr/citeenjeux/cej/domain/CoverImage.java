package fr.citeenjeux.cej.domain;

import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CoverImage {
    private CoverImage() { }
    public static String validate(byte[] bytes) {
        if(bytes.length==0||bytes.length>5*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"COVER_TOO_LARGE");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw invalid();
            var reader=readers.next();
            try {
                reader.setInput(stream,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!List.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>16000000L||reader.read(0)==null)throw invalid();
                return format.equals("png")?"image/png":"image/jpeg";
            } finally {reader.dispose();}
        } catch(IOException|IllegalArgumentException exception) {throw invalid();}
    }
    private static ResponseStatusException invalid() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_COVER");}
}
