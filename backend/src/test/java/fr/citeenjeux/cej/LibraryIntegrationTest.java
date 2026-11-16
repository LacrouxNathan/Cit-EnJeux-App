package fr.citeenjeux.cej;

import static fr.citeenjeux.cej.domain.LibraryModels.*;
import fr.citeenjeux.cej.application.*;
import fr.citeenjeux.cej.domain.Member;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker=true)
class LibraryIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired LibraryService library;
    @Autowired MemberManagement management;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    UUID admin,member;Location shelf;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE library_audit,game_cover,game_cover_revision,inventory_identifier,game_copy,library_location,game,account_audit,user_invitation,app_user CASCADE");
        admin=UUID.randomUUID();member=UUID.randomUUID();
        members.createInitialAdmin(new Member(admin,"admin@example.fr",encoder.encode("Secret de test initial"),"Alice","Admin",null,true,false,true));
        jdbc.update("INSERT INTO app_user(id,email,password_hash,first_name,last_name) VALUES(?,?,?,?,?)",member,"member@example.fr",encoder.encode("Secret de test initial"),"Camille","Membre");
        shelf=library.createLocation(admin,new LocationInput("Étagère",null));
    }
    private GameInput input(String name,GameType type,UUID base,Long version) {return new GameInput(name,type,base,"Éditeur","Auteur",2020,2,4,60,8,"Description","Stratégie","Cartes",version,null);}
    private AdminGame game(String name) {return library.save(admin,null,input(name,GameType.BASE_GAME,null,null));}
    private CopyInput copyInput(String id,UUID loc,Long version) {return new CopyInput(loc,"Bon état",null,"Privé",false,null,version);}
    private AdminCopy copy(AdminGame game,String id) {return library.createCopies(admin,game.game().id(),List.of(copyInput(id,shelf.id(),null))).getFirst();}
    private MockHttpSession session(String email) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"email\":\""+email+"\",\"password\":\"Secret de test initial\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void boardCanCreateEditArchiveRestoreAndManageCover() throws Exception {
        jdbc.update("UPDATE app_user SET board_member=true WHERE id=?",member);var ca=session("member@example.fr");
        mvc.perform(get("/api/auth/me").session(ca)).andExpect(status().isOk()).andExpect(jsonPath("$.boardMember").value(true)).andExpect(jsonPath("$.canManageGames").value(true)).andExpect(jsonPath("$.administrator").value(false));
        var created=mvc.perform(post("/api/admin/library/games").session(ca).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(input("Fiche du CA",GameType.BASE_GAME,null,null))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.totalCopies").doesNotExist()).andReturn();
        UUID id=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("game").get("id").asText());
        mvc.perform(get("/api/library/games/"+id).session(ca)).andExpect(status().isOk()).andExpect(jsonPath("$.game.game.name").value("Fiche du CA"));
        mvc.perform(get("/api/admin/library/base-games").session(ca)).andExpect(status().isOk()).andExpect(jsonPath("$[0].totalCopies").doesNotExist());
        mvc.perform(get("/api/admin/library/similar").param("name","Fiche du CA").session(ca)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/library/games/"+id).session(ca).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(input("Modifié par le CA",GameType.BASE_GAME,null,0L))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.game.name").value("Modifié par le CA"));
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("file","cover.png","image/png",bytes.toByteArray());
        mvc.perform(multipart("/api/admin/library/games/"+id+"/cover").file(file).param("version","1").with(request->{request.setMethod("PUT");return request;}).session(ca).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/library/games/"+id+"/cover").session(ca)).andExpect(status().isOk()).andExpect(content().bytes(bytes.toByteArray()));
        mvc.perform(delete("/api/admin/library/games/"+id+"/cover").param("version","2").session(ca).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/library/games/"+id+"/archive").session(ca).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new Revision(3L,"Retrait par le CA",true)))).andExpect(status().isOk());
        mvc.perform(get("/api/library/games/"+id).session(ca)).andExpect(status().isOk()).andExpect(jsonPath("$.game.archived").value(true));
        mvc.perform(post("/api/admin/library/games/"+id+"/restore").session(ca).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new Revision(4L,"Restauration par le CA",true)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM library_audit WHERE entity_id=? AND actor_id=?",Integer.class,id,member)).isEqualTo(6);
    }
    @Test void boardCannotAccessStockAccountsOrAdministrativeHistory()throws Exception {
        var game=game("Stock privé");var active=copy(game,"A");var archived=copy(game,"B");
        library.archiveCopy(admin,archived.copy().id(),new Revision(0L,"Retrait",true),false);
        jdbc.update("UPDATE app_user SET board_member=true WHERE id=?",member);var ca=session("member@example.fr");
        mvc.perform(get("/api/library/games/"+game.game().id()).session(ca)).andExpect(status().isOk())
            .andExpect(jsonPath("$.copies.length()").value(1)).andExpect(jsonPath("$.copies[0].comments").doesNotExist())
            .andExpect(jsonPath("$.copies[0].copy").doesNotExist()).andExpect(jsonPath("$.game.totalCopies").doesNotExist());
        mvc.perform(get("/api/admin/users").session(ca)).andExpect(status().isForbidden());
        mvc.perform(get("/admin-users.html").session(ca)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library/games/"+game.game().id()+"/copies").session(ca).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/library/copies/"+active.copy().id()).session(ca).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library/copies/"+active.copy().id()+"/archive").session(ca).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library/locations").session(ca).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/library/game/"+game.game().id()+"/history").session(ca)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/library/games/"+game.game().id()+"/covers/"+UUID.randomUUID()).session(ca)).andExpect(status().isForbidden());
        assertThatThrownBy(()->library.createCopies(member,game.game().id(),List.of(copyInput("x",shelf.id(),null)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->library.createLocation(member,new LocationInput("Interdit",null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(post("/api/admin/library/games/"+game.game().id()+"/archive").session(ca).with(csrf()).contentType("application/json").content(json.writeValueAsString(new Revision(0L,"Retrait",true))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVE_COPIES_PREVENT_ARCHIVE"));
    }
    @Test void removingBoardRoleRevokesEditorialAccessOnExistingSession()throws Exception {
        jdbc.update("UPDATE app_user SET board_member=true WHERE id=?",member);var ca=session("member@example.fr");var game=library.save(member,null,input("Brouillon du CA",GameType.BASE_GAME,null,null));
        var user=management.get(admin,member);
        management.update(admin,member,new MemberManagement.Update(user.email(),user.firstName(),user.lastName(),user.nickname(),false,false,true,user.version(),false));
        mvc.perform(post("/api/admin/library/games").session(ca).with(csrf()).contentType("application/json").content(json.writeValueAsString(input("Interdit",GameType.BASE_GAME,null,null)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/library/games/"+game.game().id()).session(ca)).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").session(ca)).andExpect(status().isOk()).andExpect(jsonPath("$.canManageGames").value(false));
    }
    @Test void bggSourceIsPreservedAndAudited() {
        var input=new GameInput("Catan",GameType.BASE_GAME,null,null,null,1995,3,4,120,10,null,null,null,null,13L);
        var result=library.save(admin,null,input);
        assertThat(result.game().bggId()).isEqualTo(13L);
        assertThat(result.game().sourceUrl()).isEqualTo("https://boardgamegeek.com/boardgame/13");
        assertThat(library.history(admin,"GAME",result.game().id()).getFirst().changes()).contains("bggId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_copy",Integer.class)).isZero();
    }
    @Test void privateCatalogueAndMutationPermissions() throws Exception {
        var game=game("Azul");
        mvc.perform(get("/library.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/library/games")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/library/games/"+game.game().id()+"/cover")).andExpect(status().isUnauthorized());
        var user=session("member@example.fr");
        mvc.perform(post("/api/admin/library/games").session(user).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/library/game/"+game.game().id()+"/history").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library/games").session(session("admin@example.fr")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertThatThrownBy(()->library.save(member,null,input("Interdit",GameType.BASE_GAME,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void zeroCopyVisibilityAndNoAdministrativeLeak() throws Exception {
        var game=game("Azul");var user=session("member@example.fr");
        mvc.perform(get("/api/library/games/"+game.game().id()).session(user)).andExpect(status().isNotFound());
        assertThat(library.list(member,"",null,"all","name",0,false).total()).isZero();
        copy(game,"000-A");
        mvc.perform(get("/api/library/games/"+game.game().id()).session(user)).andExpect(status().isOk())
            .andExpect(jsonPath("$.copies[0].inventoryId").doesNotExist())
            .andExpect(jsonPath("$.copies[0].comments").doesNotExist())
            .andExpect(jsonPath("$.copies[0].borrowerId").doesNotExist())
            .andExpect(jsonPath("$.copies[0].version").doesNotExist())
            .andExpect(jsonPath("$.game.archiveReason").doesNotExist());
    }
    @Test void bulkCreationRollsBackEveryCopyAndAudit() {
        var game=game("Azul");
        assertThatThrownBy(()->library.createCopies(admin,game.game().id(),List.of(copyInput("AA",shelf.id(),null),copyInput("aa",UUID.randomUUID(),null))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_copy",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_identifier",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM library_audit WHERE entity_type='COPY'",Integer.class)).isZero();
    }
    @Test void generatedIdentifiersAreUniqueImmutableAndNeverDisplayed() throws Exception {
        var game=game("Azul");
        var copies=library.createCopies(admin,game.game().id(),List.of(copyInput("ignored",shelf.id(),null),copyInput("ignored",shelf.id(),null)));
        var first=copies.getFirst();
        String inventory=jdbc.queryForObject("SELECT inventory_id FROM game_copy WHERE id=?",String.class,first.copy().id());
        assertThat(inventory).startsWith("CEJ-");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT lower(inventory_id)) FROM game_copy",Integer.class)).isEqualTo(2);
        var changed=library.updateCopy(admin,first.copy().id(),copyInput("cannot-change",shelf.id(),first.version()));
        assertThat(changed.copy().id()).isEqualTo(first.copy().id());
        assertThat(jdbc.queryForObject("SELECT inventory_id FROM game_copy WHERE id=?",String.class,first.copy().id())).isEqualTo(inventory);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_identifier",Integer.class)).isEqualTo(2);
        var adminSession=session("admin@example.fr");
        mvc.perform(get("/api/library/games/"+game.game().id()).session(adminSession))
            .andExpect(status().isOk()).andExpect(jsonPath("$.copies[0].copy.inventoryId").doesNotExist());
        mvc.perform(get("/api/admin/library/copy/"+first.copy().id()+"/history").session(adminSession))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(inventory))));
        mvc.perform(post("/api/admin/library/games/"+game.game().id()+"/copies").session(adminSession).with(csrf()).contentType("application/json")
            .content("{\"copies\":[{\"inventoryId\":\"CHOISI-PAR-LE-CLIENT\",\"locationId\":\""+shelf.id()+"\",\"physicalCondition\":\"Bon état\"}]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$[0].copy.inventoryId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_copy WHERE inventory_id='CHOISI-PAR-LE-CLIENT'",Integer.class)).isZero();
    }
    @Test void legacyIdentifiersSurviveUpdatesAndRestoration() {
        var game=game("Jeu existant");var original=copy(game,"Ancien exemplaire");
        jdbc.update("UPDATE game_copy SET inventory_id='000-LEGACY' WHERE id=?",original.copy().id());
        jdbc.update("UPDATE inventory_identifier SET normalized_id='000-legacy' WHERE copy_id=?",original.copy().id());
        var updated=library.updateCopy(admin,original.copy().id(),copyInput("ignoré",shelf.id(),original.version()));
        library.archiveCopy(admin,original.copy().id(),new Revision(updated.version(),"Archivage",true),false);
        library.archiveCopy(admin,original.copy().id(),new Revision(updated.version()+1,"Restauration",true),true);
        assertThat(jdbc.queryForObject("SELECT inventory_id FROM game_copy WHERE id=?",String.class,original.copy().id())).isEqualTo("000-LEGACY");
        assertThat(jdbc.queryForObject("SELECT copy_id FROM inventory_identifier WHERE normalized_id='000-legacy'",UUID.class)).isEqualTo(original.copy().id());
        assertThat(library.history(admin,"COPY",original.copy().id()).toString()).doesNotContain("000-LEGACY");
    }
    @Test void locationNamesCanRepeatButOccupiedLeafCannotBecomeContainer() {
        var duplicate=library.createLocation(admin,new LocationInput("Étagère",null));
        assertThat(duplicate.id()).isNotEqualTo(shelf.id());
        var child=library.createLocation(admin,new LocationInput("Bas",shelf.id()));
        var game=game("Azul");
        assertThatThrownBy(()->library.createCopies(admin,game.game().id(),List.of(copyInput("A",shelf.id(),null))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        library.createCopies(admin,game.game().id(),List.of(copyInput("A",child.id(),null)));
        assertThatThrownBy(()->library.createLocation(admin,new LocationInput("Autre",child.id())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void archiveIsAtomicAndRestorationNeverCascades() {
        var base=game("Base");var expansion=library.save(admin,null,input("Extension",GameType.EXPANSION,base.game().id(),null));
        var copy=copy(expansion,"EXT-1");
        assertThatThrownBy(()->library.archiveGame(admin,base.game().id(),new Revision(base.version(),"Retrait",true),false))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(((AdminDetails)library.detail(admin,base.game().id())).game().archived()).isFalse();
        library.archiveCopy(admin,copy.copy().id(),new Revision(copy.version(),"Retrait",true),false);
        library.archiveGame(admin,base.game().id(),new Revision(base.version(),"Retrait",true),false);
        var archivedBase=((AdminDetails)library.detail(admin,base.game().id())).game();
        var archivedExpansion=((AdminDetails)library.detail(admin,expansion.game().id())).game();
        assertThat(archivedExpansion.archived()).isTrue();
        assertThatThrownBy(()->library.archiveGame(admin,expansion.game().id(),new Revision(archivedExpansion.version(),"Retour",true),true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        library.archiveGame(admin,base.game().id(),new Revision(archivedBase.version(),"Retour",true),true);
        assertThat(((AdminDetails)library.detail(admin,expansion.game().id())).game().archived()).isTrue();
        library.archiveGame(admin,expansion.game().id(),new Revision(archivedExpansion.version(),"Retour",true),true);
        library.archiveCopy(admin,copy.copy().id(),new Revision(1L,"Retour",true),true);
        var restored=((AdminDetails)library.detail(admin,expansion.game().id())).copies().getFirst();
        assertThat(restored.copy().blocked()).isTrue();assertThat(restored.archived()).isFalse();
    }
    @Test void similarNamesIncludeArchivedAndNeverForbidCreation() {
        var first=game("Les Aventuriers du Rail");library.archiveGame(admin,first.game().id(),new Revision(0L,"Doublon",true),false);
        assertThat(library.similar(admin,"les aventuriers du rail",null)).hasSize(1);
        assertThat(game("Les Aventuriers du Rail").game().id()).isNotEqualTo(first.game().id());
    }
    @Test void filtersPaginationAndBlockedAvailability() {
        for(int i=0;i<22;i++) copy(game(String.format("Jeu %02d",i)),"COPY-"+i);
        assertThat(library.list(member,"Jeu",3,"available","name",0,false).items()).hasSize(20);
        assertThat(library.list(member,"Jeu",3,"available","recent",1,false).items()).hasSize(2);
        assertThat(library.list(member,"Jeu",5,"all","name",0,false).total()).isZero();
        var game=game("Bloqué");var copy=copy(game,"BLOCKED");
        library.updateCopy(admin,copy.copy().id(),new CopyInput(shelf.id(),"À revoir",null,null,true,"Pièces à vérifier",copy.version()));
        assertThat(library.list(member,"Bloqué",null,"unavailable","availability",0,false).total()).isEqualTo(1);
        assertThat(library.list(member,"Bloqué",null,"available","name",0,false).total()).isZero();
        assertThat(library.list(member,"Bloqué",null,"all","name",0,false).total()).isEqualTo(1);
    }
    @Test void concurrentCopyCreationGeneratesDistinctIdentifiers() throws Exception {
        var game=game("Azul");var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> action=()->{start.await();try{library.createCopies(admin,game.game().id(),List.of(copyInput("SAME",shelf.id(),null)));return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,true);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM game_copy",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT lower(inventory_id)) FROM game_copy",Integer.class)).isEqualTo(2);
    }
    @Test void staleSavesAndInvalidParentRelationshipsAreRejected() {
        var base=game("Azul");
        library.save(admin,base.game().id(),input("Azul nouveau",GameType.BASE_GAME,null,base.version()));
        assertThatThrownBy(()->library.save(admin,base.game().id(),input("Perte de mise à jour",GameType.BASE_GAME,null,base.version())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var ext=library.save(admin,null,input("Extension",GameType.EXPANSION,base.game().id(),null));
        assertThatThrownBy(()->library.save(admin,null,input("Extension d’extension",GameType.EXPANSION,ext.game().id(),null)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->library.save(admin,base.game().id(),input("Convertir parent",GameType.EXPANSION,game("Autre").game().id(),1L)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void privateCoverUploadAndHistoryPreserveOriginalImages() throws Exception {
        var game=game("Azul");var adminSession=session("admin@example.fr");var user=session("member@example.fr");
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var file=new org.springframework.mock.web.MockMultipartFile("file","cover.png","image/png",bytes.toByteArray());
        mvc.perform(multipart("/api/admin/library/games/"+game.game().id()+"/cover").file(file).param("version","0").with(request->{request.setMethod("PUT");return request;}).session(adminSession).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/library/games/"+game.game().id()+"/cover").session(user)).andExpect(status().isNotFound());
        copy(game,"A");
        mvc.perform(get("/api/library/games/"+game.game().id()+"/cover").session(user)).andExpect(status().isOk()).andExpect(content().bytes(bytes.toByteArray()));
        var revision=jdbc.queryForObject("SELECT revision_id FROM game_cover WHERE game_id=?",UUID.class,game.game().id());
        library.coverSave(admin,game.game().id(),1,null,null);
        assertThat(library.oldCover(admin,game.game().id(),revision).content()).isEqualTo(bytes.toByteArray());
        mvc.perform(get("/api/admin/library/games/"+game.game().id()+"/covers/"+revision).session(user)).andExpect(status().isForbidden());
        var invalid=new org.springframework.mock.web.MockMultipartFile("file","image.svg","image/png","<svg/>".getBytes());
        mvc.perform(multipart("/api/admin/library/games/"+game.game().id()+"/cover").file(invalid).param("version","2").with(request->{request.setMethod("PUT");return request;}).session(adminSession).with(csrf())).andExpect(status().isBadRequest());
    }
}
