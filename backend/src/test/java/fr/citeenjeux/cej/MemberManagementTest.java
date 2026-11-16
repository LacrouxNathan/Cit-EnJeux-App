package fr.citeenjeux.cej;

import fr.citeenjeux.cej.application.*;
import fr.citeenjeux.cej.domain.Member;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker=true)
class MemberManagementTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
    @Autowired MemberManagement management;
    @Autowired UserInvitations invitations;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    UUID admin,member;
    String hash;
    @BeforeEach void setup(){
        jdbc.execute("TRUNCATE app_user,account_audit CASCADE");
        admin=UUID.randomUUID();member=UUID.randomUUID();hash=encoder.encode("Secret de test initial");
        members.createInitialAdmin(new Member(admin,"admin@example.fr",hash,"Alice","Admin",null,true,false,true));
        insert(member,"member@example.fr",false);
    }
    private void insert(UUID id,String email,boolean administrator){jdbc.update("INSERT INTO app_user(id,email,password_hash,first_name,last_name,administrator) VALUES(?,?,?,?,?,?)",id,email,hash,"Camille","Martin",administrator);}
    private MemberManagement.Update update(UUID id,boolean administrator,boolean active,boolean membership){
        var current=management.get(admin,id);return new MemberManagement.Update(current.email(),current.firstName(),current.lastName(),current.nickname(),membership,administrator,active,current.version());
    }
    private MockHttpSession login(String email)throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("email",email,"password","Secret de test initial"))))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void boardRoleIsExclusiveAdminAssignedAndNotAnAdministrator()throws Exception {
        var user=management.get(admin,member);
        var changed=management.update(admin,member,new MemberManagement.Update(user.email(),user.firstName(),user.lastName(),null,false,false,true,user.version(),true));
        assertThat(changed.boardMember()).isTrue();assertThat(changed.administrator()).isFalse();
        assertThat(invitations.list(admin,0,"","all","board","all")).hasSize(1);
        assertThat(invitations.list(admin,0,"","all","member","all")).isEmpty();
        assertThatThrownBy(()->management.update(admin,admin,new MemberManagement.Update("admin@example.fr","Alice","Admin",null,false,false,true,0L,true)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("LAST_ACTIVE_ADMIN");
        assertThatThrownBy(()->management.update(member,admin,update(admin,false,true,false))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->management.update(admin,member,new MemberManagement.Update(user.email(),user.firstName(),user.lastName(),null,false,true,true,changed.version(),true)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INVALID_ROLE");
        var legacy=management.update(admin,member,new MemberManagement.Update(user.email(),user.firstName(),user.lastName(),null,true,false,true,changed.version()));
        assertThat(legacy.boardMember()).isTrue();
        assertThat(management.history(admin,member).toString()).contains("board_member");
    }
    @Test void profilesMembershipAndAuditPreserveIdentityAndPassword(){
        var before=management.get(admin,member);
        var changed=management.update(admin,member,new MemberManagement.Update(" NEW@EXAMPLE.FR "," Nouveau "," Nom ","Pseudo",true,false,true,before.version()));
        assertThat(changed.id()).isEqualTo(member);assertThat(changed.email()).isEqualTo("new@example.fr");
        assertThat(changed.firstName()).isEqualTo("Nouveau");assertThat(changed.membershipCurrent()).isTrue();
        assertThat(members.findById(member).orElseThrow().passwordHash()).isEqualTo(hash);
        assertThat(members.findById(member).orElseThrow().securityVersion()).isEqualTo(1);
        var events=management.history(admin,member);assertThat(events).hasSize(1);
        assertThat(events.getFirst().changes()).contains("first_name","membership_current","before","after").doesNotContain(hash,"password_hash","security_version");
    }
    @Test void lastUsableAdminCannotBeDemotedOrDisabled(){
        for(var data:List.of(update(admin,false,true,false),update(admin,true,false,false)))
            assertThatThrownBy(()->management.update(admin,admin,data)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("LAST_ACTIVE_ADMIN");
        assertThat(members.findById(admin).orElseThrow().administrator()).isTrue();assertThat(members.findById(admin).orElseThrow().active()).isTrue();
        assertThat(management.history(admin,admin)).isEmpty();
    }
    @Test void pendingAdministratorDoesNotAllowLockingOutLastActivatedAdmin(){
        var invitation=invitations.create(admin,new UserInvitations.NewUser("pending@example.fr","P","M",null,false));
        management.update(admin,invitation.userId(),update(invitation.userId(),true,true,false));
        assertThatThrownBy(()->management.update(admin,admin,update(admin,false,true,false))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("LAST_ACTIVE_ADMIN");
        invitations.activate(invitation.token(),"Mot de passe initial");
        management.update(admin,admin,update(admin,false,true,false));
        assertThat(members.findById(admin).orElseThrow().administrator()).isFalse();
    }
    @Test void concurrentDemotionsLeaveExactlyOneAdministrator()throws Exception {
        jdbc.update("UPDATE app_user SET administrator=true WHERE id=?",member);
        var first=update(admin,false,true,false);var second=update(member,false,true,false);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> a=()->{start.await();try{management.update(admin,admin,first);return "OK";}catch(ResponseStatusException e){return e.getReason();}};
            java.util.concurrent.Callable<String> b=()->{start.await();try{management.update(member,member,second);return "OK";}catch(ResponseStatusException e){return e.getReason();}};
            var one=pool.submit(a);var two=pool.submit(b);start.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder("OK","LAST_ACTIVE_ADMIN");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE active AND administrator AND password_hash IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void deactivationAndReactivationNeverRestoreOldSessions()throws Exception {
        var session=login("member@example.fr");
        management.update(admin,member,update(member,false,false,false));
        management.update(admin,member,update(member,false,true,false));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());assertThat(session.isInvalid()).isTrue();
        var fresh=login("member@example.fr");mvc.perform(get("/api/auth/me").session(fresh)).andExpect(status().isOk());
    }
    @Test void promotionAndRevocationTakeEffectOnExistingSession()throws Exception {
        var session=login("member@example.fr");
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        management.update(admin,member,update(member,true,true,false));
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());
        management.update(admin,member,update(member,false,true,false));
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }
    @Test void pendingEmailChangeInvalidatesOldInvitationAndResendUsesNewAddress(){
        var issued=invitations.create(admin,new UserInvitations.NewUser("old@example.fr","Camille","Martin",null,false));
        var user=management.get(admin,issued.userId());
        management.update(admin,user.id(),new MemberManagement.Update("new@example.fr",user.firstName(),user.lastName(),null,false,false,true,user.version()));
        assertThatThrownBy(()->invitations.activate(issued.token(),"Mot de passe initial")).isInstanceOf(ResponseStatusException.class);
        var next=invitations.resend(admin,user.id());assertThat(next.email()).isEqualTo("new@example.fr");
        invitations.activate(next.token(),"Mot de passe initial");assertThat(members.findById(user.id()).orElseThrow().passwordHash()).isNotNull();
    }
    @Test void staleSaveAndDuplicateEmailDoNotPartiallyModifyAccounts()throws Exception {
        var stale=update(member,false,true,false);management.update(admin,member,update(member,false,true,true));
        assertThatThrownBy(()->management.update(admin,member,stale)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("STALE_USER_VERSION");
        var current=management.get(admin,member);var session=login("admin@example.fr");
        mvc.perform(put("/api/admin/users/"+member).session(session).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(new MemberManagement.Update("ADMIN@example.fr","Changement interdit","Martin",null,false,false,false,current.version()))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
        var after=management.get(admin,member);assertThat(after).isEqualTo(current);
    }
    @Test void authorizationCsrfValidationAndSensitiveDataAreProtected()throws Exception {
        String url="/api/admin/users/"+member;
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url+"/history")).andExpect(status().isUnauthorized());
        var regular=login("member@example.fr");var administrator=login("admin@example.fr");
        mvc.perform(get(url+"/history").session(regular)).andExpect(status().isForbidden());
        mvc.perform(put(url).session(regular).with(csrf()).contentType("application/json").content(json.writeValueAsString(update(member,true,true,true)))).andExpect(status().isForbidden());
        mvc.perform(put(url).session(administrator).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(put(url).session(administrator).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(url).session(administrator)).andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.securityVersion").doesNotExist());
        assertThatThrownBy(()->management.update(member,member,update(member,true,true,true))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void searchAndRoleStatusMembershipFiltersAreServerSide(){
        management.update(admin,member,update(member,false,false,true));
        assertThat(invitations.list(admin,0,"Camille","inactive","member","current")).hasSize(1);
        assertThat(invitations.list(admin,0,"admin@example.fr","active","admin","expired")).hasSize(1);
        assertThat(invitations.list(admin,0,"Camille","active","all","all")).isEmpty();
        assertThatThrownBy(()->invitations.list(member,0)).isInstanceOf(ResponseStatusException.class);
    }
}
