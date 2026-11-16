package fr.citeenjeux.cej;

import fr.citeenjeux.cej.application.MemberRepository;
import fr.citeenjeux.cej.domain.Member;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Les scénarios partagent une adresse MockMvc ; le throttle a sa propre suite de tests.
@SpringBootTest(properties="cej.auth.max-attempts=100")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired fr.citeenjeux.cej.application.UserInvitations invitations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.springframework.mail.javamail.JavaMailSender mail;

    private fr.citeenjeux.cej.domain.Member admin() {
        jdbc.update("DELETE FROM app_user");
        var admin = new Member(UUID.randomUUID(), "admin@example.fr", encoder.encode("Secret de test initial"), "Alice", "Martin", null, true, false, true);
        members.createInitialAdmin(admin);
        return admin;
    }

    private fr.citeenjeux.cej.application.UserInvitations.NewUser newUser() {
        return new fr.citeenjeux.cej.application.UserInvitations.NewUser(" invite@example.fr ", " Camille ", " Martin ", "Cam", true);
    }

    private org.springframework.mock.web.MockHttpSession adminSession() throws Exception {
        return (org.springframework.mock.web.MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"admin@example.fr\",\"password\":\"Secret de test initial\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @Test void passwordResetIsSingleUseRevokesSessionsAndIsAdminOnly() throws Exception {
        var administrator=admin(); var issued=invitations.create(administrator.id(),newUser());
        invitations.activate(issued.token(),"ancien");
        var session=(org.springframework.mock.web.MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"password\":\"ancien\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/admin/users/"+issued.userId()+"/password-reset").session(session).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/"+issued.userId()+"/password-reset").session(adminSession())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/"+issued.userId()+"/password-reset").session(adminSession()).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryStatus").value("SENT")).andExpect(jsonPath("$.token").doesNotExist());
        var reset=invitations.requestPasswordReset(administrator.id(),issued.userId());
        assertThat(encoder.matches("ancien",members.findById(issued.userId()).orElseThrow().passwordHash())).isTrue();
        mvc.perform(post("/api/password-reset/confirm").with(csrf()).contentType("application/json")
            .content("{\"token\":\""+reset.token()+"\",\"password\":\"court\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/password-reset/confirm").with(csrf()).contentType("application/json")
            .content("{\"token\":\""+reset.token()+"\",\"password\":\"secret\"}"))
            .andExpect(status().isNoContent());
        assertThat(encoder.matches("secret",members.findById(issued.userId()).orElseThrow().passwordHash())).isTrue();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->invitations.resetPassword(reset.token(),"encore")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"password\":\"secret\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_audit WHERE subject_id=? AND action='PASSWORD_RESET_COMPLETED'",Integer.class,issued.userId())).isEqualTo(1);
    }

    @Test void passwordResetRejectsPendingDisabledExpiredAndReplacedLinks() {
        var administrator=admin();var issued=invitations.create(administrator.id(),newUser());
        assertThatThrownBy(()->invitations.requestPasswordReset(administrator.id(),issued.userId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        invitations.activate(issued.token(),"ancien");
        var first=invitations.requestPasswordReset(administrator.id(),issued.userId());
        var second=invitations.requestPasswordReset(administrator.id(),issued.userId());
        assertThatThrownBy(()->invitations.resetPassword(first.token(),"secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("UPDATE password_reset SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=?",issued.userId());
        assertThatThrownBy(()->invitations.resetPassword(second.token(),"secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var third=invitations.requestPasswordReset(administrator.id(),issued.userId());
        jdbc.update("UPDATE app_user SET security_version=security_version+1 WHERE id=?",issued.userId());
        assertThatThrownBy(()->invitations.resetPassword(third.token(),"secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("UPDATE app_user SET active=false WHERE id=?",issued.userId());
        assertThatThrownBy(()->invitations.requestPasswordReset(administrator.id(),issued.userId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->invitations.resetPassword(third.token(),"secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void activationRequiresAtLeastSixCharacters() throws Exception {
        var issued = invitations.create(admin().id(), newUser());
        mvc.perform(post("/api/invitations/activate").with(csrf()).contentType("application/json")
            .content("{\"token\":\"" + issued.token() + "\",\"password\":\"abcde\"}"))
            .andExpect(status().isBadRequest());
        assertThat(members.findById(issued.userId()).orElseThrow().passwordHash()).isNull();
        mvc.perform(post("/api/invitations/activate").with(csrf()).contentType("application/json")
            .content("{\"token\":\"" + issued.token() + "\",\"password\":\"abcdef\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"password\":\"abcdef\"}"))
            .andExpect(status().isOk());
    }

    @Test void invitationActivationIsSingleUseAndCreatesOnlyAMember() throws Exception {
        var admin = admin();
        var issued = invitations.create(admin.id(), newUser());
        assertThat(members.findById(issued.userId()).orElseThrow().passwordHash()).isNull();
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"password\":\"anything\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invitations/activate").with(csrf()).contentType("application/json")
            .content("{\"token\":\"" + issued.token() + "\",\"password\":\"Mon nouveau secret\"}"))
            .andExpect(status().isNoContent());
        var user = members.findById(issued.userId()).orElseThrow();
        assertThat(encoder.matches("Mon nouveau secret", user.passwordHash())).isTrue();
        assertThat(user.administrator()).isFalse();
        assertThat(user.membershipCurrent()).isTrue();
        assertThatThrownBy(() -> invitations.activate(issued.token(), "Autre nouveau secret"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"password\":\"Mon nouveau secret\"}"))
            .andExpect(status().isOk()).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin-users.html").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_audit WHERE subject_id = ?", Integer.class, user.id())).isEqualTo(3);
    }

    @Test void expiryResendAndDisabledAccountsAreEnforced() {
        var admin = admin();
        var first = invitations.create(admin.id(), newUser());
        jdbc.update("UPDATE user_invitation SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThatThrownBy(() -> invitations.activate(first.token(), "Mon nouveau secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var second = invitations.resend(admin.id(), first.userId());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> invitations.activate(first.token(), "Mon nouveau secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("UPDATE app_user SET active = false WHERE id = ?", first.userId());
        assertThatThrownBy(() -> invitations.activate(second.token(), "Mon nouveau secret")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> invitations.resend(admin.id(), first.userId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void adminApiSendsEmailWithoutReturningTokenAndHandlesDuplicates() throws Exception {
        admin(); var session = adminSession();
        String body = "{\"email\":\" INVITE@EXAMPLE.FR \",\"firstName\":\"Camille\",\"lastName\":\"Martin\",\"administrator\":true}";
        mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.deliveryStatus").value("SENT"))
            .andExpect(jsonPath("$.token").doesNotExist());
        var capture = org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(mail).send(capture.capture());
        assertThat(capture.getValue().getText()).contains("/activate.html#token=");
        assertThat(members.findByEmail("invite@example.fr").orElseThrow().administrator()).isFalse();
        mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].passwordHash").doesNotExist()).andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test void smtpFailureKeepsRecoverableAccount() throws Exception {
        admin(); var session = adminSession();
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("Indisponible"))
            .when(mail).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json")
            .content("{\"email\":\"invite@example.fr\",\"firstName\":\"Camille\",\"lastName\":\"Martin\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.deliveryStatus").value("FAILED"));
        assertThat(members.findByEmail("invite@example.fr")).isPresent();
    }

    @Test void creationAndActivationRequireCsrfAndValidInput() throws Exception {
        admin(); var session = adminSession();
        mvc.perform(post("/api/admin/users").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users").session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json")
            .content("{\"email\":\"invalide\",\"firstName\":\" \",\"lastName\":\"Martin\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/invitations/activate").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/invitations/activate").with(csrf()).contentType("application/json")
            .content("{\"token\":\"invalide\",\"password\":\"court\"}")).andExpect(status().isBadRequest());
    }

    @Test void concurrentActivationSucceedsOnlyOnce() throws Exception {
        var admin = admin(); var issued = invitations.create(admin.id(), newUser());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> activate = () -> {
                start.await();
                try { invitations.activate(issued.token(), "Mon nouveau secret"); return true; }
                catch (org.springframework.web.server.ResponseStatusException rejected) { return false; }
            };
            var a = pool.submit(activate); var b = pool.submit(activate); start.countDown();
            assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void concurrentCreationRejectsDuplicateAndRollsBackAudit() throws Exception {
        var admin = admin();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                start.await();
                try { invitations.create(admin.id(), newUser()); return true; }
                catch (org.springframework.dao.DuplicateKeyException rejected) { return false; }
            };
            var a = pool.submit(create); var b = pool.submit(create); start.countDown();
            assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(2);
        var user = members.findByEmail("invite@example.fr").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_audit WHERE subject_id = ?", Integer.class, user.id())).isEqualTo(2);
    }

    @Test void realDatabaseAuthenticationAndLogout() throws Exception {
        jdbc.update("DELETE FROM app_user");
        members.createInitialAdmin(new Member(UUID.randomUUID(), "admin@example.fr", encoder.encode("Secret de test initial"),
            "Alice", "Martin", null, true, false, true));
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"ADMIN@example.fr\",\"password\":\"Secret de test initial\"}"))
            .andExpect(status().isOk()).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.administrator").value(true)).andExpect(jsonPath("$.displayName").value("Alice Martin"));
        mvc.perform(get("/member.html").session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET active = false");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void concurrentBootstrapCreatesOnlyOneAdministrator() throws Exception {
        jdbc.update("DELETE FROM app_user");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                start.await();
                return members.createInitialAdmin(new Member(UUID.randomUUID(), "admin@example.fr", "hash", "A", "B", null, true, false, true));
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        }
    }

    @Test void migrationAndBootstrapPreserveUniqueIdentity() {
        jdbc.update("DELETE FROM app_user");
        var member = new Member(UUID.randomUUID(), "admin@example.fr", "{pbkdf2}hash", "Alice", "Martin", null, true, false, true);
        assertThat(members.createInitialAdmin(member)).isTrue();
        assertThat(members.createInitialAdmin(new Member(UUID.randomUUID(), "other@example.fr", "hash", "A", "B", null, true, false, true))).isFalse();
        assertThat(members.findByEmail(member.email()).orElseThrow().id()).isEqualTo(member.id());
        assertThat(members.findById(member.id()).orElseThrow().administrator()).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user(id,email,password_hash,first_name,last_name,nickname,active,membership_current,administrator,created_at) SELECT gen_random_uuid(), email, password_hash, first_name, last_name, nickname, active, membership_current, administrator, created_at FROM app_user"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET email = 'ADMIN@EXAMPLE.FR'"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
