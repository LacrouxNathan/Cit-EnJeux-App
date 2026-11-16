package fr.citeenjeux.cej;

import fr.citeenjeux.cej.application.*;
import fr.citeenjeux.cej.infrastructure.BggHttp;
import fr.citeenjeux.cej.domain.Member;
import java.util.*;
import java.net.URI;
import java.io.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BggImportTest {
    MemberRepository members;
    BggHttp http;
    UUID admin;
    BggImportService service;
    static final String XML="""
        <items><item type="boardgame" id="13">
        <name type="alternate" value="Autre nom"/><name type="primary" value="Catan"/>
        <description>Première ligne.&amp;#10;&amp;lt;br/&amp;gt;Deuxième ligne.</description>
        <yearpublished value="1995"/><minplayers value="3"/><maxplayers value="4"/>
        <playingtime value="120"/><minage value="10"/>
        <link type="boardgamepublisher" value="Éditeur"/><link type="boardgamedesigner" value="Auteur"/>
        <link type="boardgamecategory" value="Négociation"/><link type="boardgamemechanic" value="Dés"/>
        </item></items>
        """;
    @BeforeEach void setup() {
        members=mock(MemberRepository.class);http=mock(BggHttp.class);admin=UUID.randomUUID();
        when(members.findById(admin)).thenReturn(Optional.of(new Member(admin,"admin@example.fr","hash","A","B",null,true,false,true)));
        service=new BggImportService(members,http,"test-token");
    }
    @Test void acceptsOnlyExactBggGameLinks() {
        for(String url:List.of("https://boardgamegeek.com/boardgame/13/catan","https://www.boardgamegeek.com/boardgameexpansion/13/example?x=1#test"))
            assertThat(BggImportService.idFromUrl(url)).isEqualTo(13);
        for(String url:List.of("http://boardgamegeek.com/boardgame/13","https://boardgamegeek.com.evil.test/boardgame/13","https://evil.test/?url=https://boardgamegeek.com/boardgame/13",
            "https://boardgamegeek.com@127.0.0.1/boardgame/13","https://boardgamegeek.com:8443/boardgame/13","https://boardgamegeek.com/xmlapi2/thing?id=13","file:///etc/passwd","https://127.0.0.1/boardgame/13","https://boardgamegeek.com/boardgame/0/test"))
            assertThatThrownBy(()->BggImportService.idFromUrl(url)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void importsMetadataUsingCanonicalApiAndKeepsTokenOutOfResponse() throws Exception {
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var data=service.preview(admin,"https://www.boardgamegeek.com/boardgame/13/catan?track=anything");
        assertThat(data.name()).isEqualTo("Catan");assertThat(data.minPlayers()).isEqualTo(3);assertThat(data.durationMinutes()).isEqualTo(120);
        assertThat(data.publisher()).isEqualTo("Éditeur");assertThat(data.author()).isEqualTo("Auteur");
        assertThat(data.description()).contains("Première ligne.").doesNotContain("<br", "&#10;");
        assertThat(data.sourceUrl()).isEqualTo("https://boardgamegeek.com/boardgame/13");
        assertThat(data.toString()).doesNotContain("test-token");
        verify(http).get(URI.create("https://boardgamegeek.com/xmlapi2/thing?id=13"),"test-token",2*1024*1024);
    }
    @Test void tokenIsRequiredButManualApplicationCanStartWithoutIt() {
        var unconfigured=new BggImportService(members,http,"");
        assertThatThrownBy(()->unconfigured.preview(admin,"https://boardgamegeek.com/boardgame/13")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("BGG_NOT_CONFIGURED");
        verifyNoInteractions(http);
    }
    @Test void boardMemberCanPrepareBggImportWithoutAccountAdministration() throws Exception {
        when(members.findById(admin)).thenReturn(Optional.of(new Member(admin,"ca@example.fr","hash","C","A",null,true,false,false,0,true)));
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(service.preview(admin,"https://boardgamegeek.com/boardgame/13").name()).isEqualTo("Catan");
    }
    @Test void malformedTokenCannotLeakThroughHttpHeaderErrors() {
        var malformed=new BggImportService(members,http,"secret\nheader");
        assertThatThrownBy(()->malformed.preview(admin,"https://boardgamegeek.com/boardgame/13"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("BGG_NOT_CONFIGURED").hasMessageNotContaining("secret");
        verifyNoInteractions(http);
    }
    @Test void membersAndDisabledAdminsCannotFetchBgg() {
        for(var member:List.of(new Member(admin,"a@b.fr","hash","A","B",null,true,false,false),
            new Member(admin,"a@b.fr","hash","A","B",null,false,false,true),
            new Member(admin,"a@b.fr",null,"A","B",null,true,false,true))) {
            when(members.findById(admin)).thenReturn(Optional.of(member));
            assertThatThrownBy(()->service.preview(admin,"https://boardgamegeek.com/boardgame/13")).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(http);
    }
    @Test void malformedXmlAndExternalEntitiesAreRejected() throws Exception {
        String attack="<!DOCTYPE items [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><items><item id='13' type='boardgame'><name value='x'/><description>&secret;</description></item></items>";
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,attack.getBytes()));
        assertThatThrownBy(()->service.preview(admin,"https://boardgamegeek.com/boardgame/13")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("BGG_INVALID_RESPONSE");
    }
    @Test void unavailableOrUnauthorizedBggHasSpecificErrors() throws Exception {
        for(var entry:Map.of(401,"BGG_AUTH_FAILED",403,"BGG_AUTH_FAILED",202,"BGG_PENDING",429,"BGG_RATE_LIMIT",500,"BGG_UNAVAILABLE",404,"BGG_GAME_NOT_FOUND").entrySet()) {
            when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(entry.getKey(),new byte[0]));
            var fresh=new BggImportService(members,http,"test-token");
            assertThatThrownBy(()->fresh.preview(admin,"https://boardgamegeek.com/boardgame/13")).isInstanceOf(ResponseStatusException.class).hasMessageContaining(entry.getValue());
        }
    }
    @Test void imageHostsAreRestrictedWithoutDiscardingMetadata() throws Exception {
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.replace("</item>","<image>https://127.0.0.1/secret.png</image></item>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var data=service.preview(admin,"https://boardgamegeek.com/boardgame/13");
        assertThat(data.name()).isEqualTo("Catan");assertThat(data.cover()).isNull();assertThat(data.warnings()).hasSize(2);
        verify(http,times(1)).get(any(),any(),anyInt());
    }
    @Test void coverIsValidatedAndNeverReceivesBearerToken() throws Exception {
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.replace("</item>","<image>https://cf.geekdo-images.com/cover.png</image></item>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(http.get(eq(URI.create("https://cf.geekdo-images.com/cover.png")),isNull(),anyInt())).thenReturn(new BggHttp.Reply(200,bytes.toByteArray()));
        var data=service.preview(admin,"https://boardgamegeek.com/boardgame/13");
        assertThat(data.cover().contentType()).isEqualTo("image/png");assertThat(Base64.getDecoder().decode(data.cover().base64())).isEqualTo(bytes.toByteArray());
        verify(http).get(URI.create("https://cf.geekdo-images.com/cover.png"),null,5*1024*1024);
    }
    @Test void expansionsRequireReviewAndDoNotInventLocalBaseIds() throws Exception {
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.replace("type=\"boardgame\"","type=\"boardgameexpansion\"").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var data=service.preview(admin,"https://boardgamegeek.com/boardgameexpansion/13");
        assertThat(data.type()).isEqualTo("EXPANSION");assertThat(data.warnings()).anyMatch(s->s.contains("autonome"));
    }
    @Test void rateLimitProtectsBgg() throws Exception {
        when(http.get(any(),any(),anyInt())).thenReturn(new BggHttp.Reply(200,XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.preview(admin,"https://boardgamegeek.com/boardgame/13");
        assertThatThrownBy(()->service.preview(admin,"https://boardgamegeek.com/boardgame/13")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("BGG_RATE_LIMIT");
        verify(http,times(1)).get(any(),any(),anyInt());
    }
}
