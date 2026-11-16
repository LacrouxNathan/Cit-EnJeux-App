package fr.citeenjeux.cej.application;

import fr.citeenjeux.cej.infrastructure.BggHttp;
import fr.citeenjeux.cej.domain.CoverImage;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

@Service
public class BggImportService {
    public record ImportedCover(String contentType,String base64) {
        @Override public String toString(){return "ImportedCover[image]";}
    }
    public record Preview(long bggId,String sourceUrl,String name,String type,String publisher,String author,
        Integer publicationYear,Integer minPlayers,Integer maxPlayers,Integer durationMinutes,Integer minimumAge,
        String description,String categories,String mechanics,ImportedCover cover,List<String> warnings) { }
    private final MemberRepository members;
    private final BggHttp http;
    private final String token;
    private long nextRequestAt;
    private static final Pattern LINK=Pattern.compile("^/(?:boardgame|boardgameexpansion)/([1-9][0-9]{0,9})(?:/.*)?$");
    public BggImportService(MemberRepository members,BggHttp http,@Value("${cej.bgg.token:}") String token) {
        this.members=members;this.http=http;this.token=token.strip();
    }
    public static long idFromUrl(String link) {
        try {
            var uri=URI.create(link.strip());
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||!Set.of("boardgamegeek.com","www.boardgamegeek.com").contains(uri.getHost().toLowerCase(Locale.ROOT))
                ||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw invalidUrl();
            var matcher=LINK.matcher(uri.getRawPath());
            if(!matcher.matches())throw invalidUrl();
            return Long.parseLong(matcher.group(1));
        } catch(IllegalArgumentException|NullPointerException e) {throw invalidUrl();}
    }
    private static ResponseStatusException invalidUrl(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"BGG_INVALID_URL");}
    private synchronized void reserveRequest() {
        long now=System.nanoTime();
        if(nextRequestAt!=0&&now<nextRequestAt)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"BGG_RATE_LIMIT");
        nextRequestAt=now+5_000_000_000L;
    }
    public Preview preview(UUID actor,String url) {
        var member=members.findById(actor).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN));
        if(!member.canManageGames())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        long id=idFromUrl(url);
        if(token.isBlank()||token.chars().anyMatch(c->c<33||c>126))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"BGG_NOT_CONFIGURED");
        reserveRequest();
        BggHttp.Reply response;
        try {response=http.get(URI.create("https://boardgamegeek.com/xmlapi2/thing?id="+id),token,2*1024*1024);}
        catch(IOException e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"BGG_UNAVAILABLE");}
        switch(response.status()) {
            case 200 -> { }
            case 401,403 -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"BGG_AUTH_FAILED");
            case 202 -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"BGG_PENDING");
            case 429 -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"BGG_RATE_LIMIT");
            case 404 -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"BGG_GAME_NOT_FOUND");
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"BGG_UNAVAILABLE");
        }
        var data=parse(response.body(),id);
        var warnings=new ArrayList<String>();
        warnings.add("Vérifiez l’édition et les données importées : les informations BGG peuvent être en anglais.");
        if("EXPANSION".equals(data.type()))warnings.add("Vérifiez si l’extension est autonome et sélectionnez son jeu de base dans la ludothèque.");
        ImportedCover cover=null;
        if(data.image()!=null) {
            try {
                var imageUri=URI.create(data.image());
                if(!"https".equalsIgnoreCase(imageUri.getScheme())||imageUri.getUserInfo()!=null||imageUri.getHost()==null
                    ||!Set.of("cf.geekdo-images.com","cf.geekdo-static.com").contains(imageUri.getHost().toLowerCase(Locale.ROOT))
                    ||imageUri.getPort()!=-1&&imageUri.getPort()!=443)throw new IOException("Image host rejected");
                var image=http.get(imageUri,null,5*1024*1024);
                if(image.status()!=200)throw new IOException("Image unavailable");
                cover=new ImportedCover(CoverImage.validate(image.body()),Base64.getEncoder().encodeToString(image.body()));
            } catch(IOException|IllegalArgumentException|ResponseStatusException e){warnings.add("La couverture n’a pas pu être récupérée. Vous pourrez en téléverser une après création de la fiche.");}
        }
        return new Preview(id,"https://boardgamegeek.com/boardgame/"+id,data.name(),data.type(),data.publisher(),data.author(),data.year(),data.min(),data.max(),data.duration(),data.age(),data.description(),data.categories(),data.mechanics(),cover,List.copyOf(warnings));
    }
    private record Parsed(String name,String type,String publisher,String author,Integer year,Integer min,Integer max,Integer duration,Integer age,String description,String categories,String mechanics,String image) { }
    private Parsed parse(byte[] bytes,long id) {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(SAXParseException e)throws SAXException{throw e;}@Override public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            var root=builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            var items=children(root,"item");
            if(items.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"BGG_GAME_NOT_FOUND");
            var item=items.getFirst();
            if(!Long.toString(id).equals(item.getAttribute("id"))||!Set.of("boardgame","boardgameexpansion").contains(item.getAttribute("type")))throw new SAXException();
            var names=children(item,"name");String name=names.stream().filter(e->"primary".equals(e.getAttribute("type"))).findFirst().orElse(names.isEmpty()?item:names.getFirst()).getAttribute("value");
            if(name.isBlank())throw new SAXException();
            Integer min=number(item,"minplayers",false),max=number(item,"maxplayers",false);
            if(min!=null&&max!=null&&min>max){min=null;max=null;}
            String description=text(item,"description");
            if(description!=null)description=HtmlUtils.htmlUnescape(description).replaceAll("(?i)<br\\s*/?>|</p\\s*>","\n").replaceAll("<[^<>]*>","").strip();
            return new Parsed(name,"boardgameexpansion".equals(item.getAttribute("type"))?"EXPANSION":"BASE_GAME",
                links(item,"boardgamepublisher"),links(item,"boardgamedesigner"),number(item,"yearpublished",false),min,max,
                number(item,"playingtime",false),number(item,"minage",true),description,links(item,"boardgamecategory"),links(item,"boardgamemechanic"),text(item,"image"));
        } catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"BGG_INVALID_RESPONSE");}
    }
    private static List<Element> children(Element element,String tag) {
        var list=new ArrayList<Element>();for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling())if(node instanceof Element child&&tag.equals(child.getTagName()))list.add(child);return list;
    }
    private static String text(Element item,String tag){return children(item,tag).stream().findFirst().map(e->e.getTextContent().strip()).filter(s->!s.isEmpty()).orElse(null);}
    private static Integer number(Element item,String tag,boolean zero){try{int n=Integer.parseInt(children(item,tag).getFirst().getAttribute("value"));return n>0||zero&&n==0?n:null;}catch(Exception e){return null;}}
    private static String links(Element item,String type){String value=children(item,"link").stream().filter(e->type.equals(e.getAttribute("type"))).map(e->e.getAttribute("value")).filter(s->!s.isBlank()).distinct().collect(java.util.stream.Collectors.joining(", "));return value.isBlank()?null:value;}
}
