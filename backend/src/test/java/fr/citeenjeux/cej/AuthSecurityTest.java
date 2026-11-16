package fr.citeenjeux.cej;

import fr.citeenjeux.cej.application.MemberRepository;
import fr.citeenjeux.cej.domain.Member;
import fr.citeenjeux.cej.infrastructure.LoginThrottle;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "cej.auth.max-attempts=1000"
})
@AutoConfigureMockMvc
class AuthSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean MemberRepository members;
    @MockitoBean fr.citeenjeux.cej.application.UserInvitations invitations;
    @MockitoBean fr.citeenjeux.cej.application.LibraryService library;
    @MockitoBean fr.citeenjeux.cej.application.MemberManagement management;
    @MockitoBean LoginThrottle throttle;
    Member member;

    @BeforeEach void setup() {
        when(throttle.allow(anyString())).thenReturn(true);
        member = new Member(UUID.randomUUID(), "membre@example.fr", encoder.encode("Un secret de test"),
            "Camille", "Martin", "Cam", true, false, false);
        when(members.findByEmail(anyString())).thenReturn(Optional.empty());
        when(members.findByEmail(member.email())).thenReturn(Optional.of(member));
        when(members.findById(member.id())).thenReturn(Optional.of(member));
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
            .contentType("application/json").content("{\"email\":\" MEMBRE@EXAMPLE.FR \",\"password\":\"Un secret de test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
            .andReturn().getRequest().getSession(false);
    }

    @Test void visitorCannotAccessPrivateData() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/member.html", "/library.html", "/admin-users.html"}) {
            mvc.perform(get(path)).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        }
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/logo_cej.png")).andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
    }

    @Test void apiAndAssetsKeepUnauthorizedResponses() throws Exception {
        mvc.perform(post("/api/admin/library/bgg/preview").with(csrf()).contentType("application/json")
            .content("{\"url\":\"https://boardgamegeek.com/boardgame/13\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").accept("text/html")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/member.js")).andExpect(status().isUnauthorized());
        mvc.perform(get("/page-privee").accept("text/html")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        mvc.perform(get("/activate.html")).andExpect(status().isOk());
    }

    @Test void bggPreviewRequiresCsrfAndReportsMissingConfigurationWithoutSaving() throws Exception {
        var administrator = new Member(member.id(), member.email(), member.passwordHash(), "Camille", "Martin", null, true, false, true);
        when(members.findByEmail(member.email())).thenReturn(Optional.of(administrator));
        when(members.findById(member.id())).thenReturn(Optional.of(administrator));
        var session = login();
        mvc.perform(post("/api/admin/library/bgg/preview").session(session).contentType("application/json")
            .content("{\"url\":\"https://boardgamegeek.com/boardgame/13\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/library/bgg/preview").session(session).with(csrf()).contentType("application/json")
            .content("{\"url\":\"https://localhost/boardgame/13\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BGG_INVALID_URL"));
        mvc.perform(post("/api/admin/library/bgg/preview").session(session).with(csrf()).contentType("application/json")
            .content("{\"url\":\"https://boardgamegeek.com/boardgame/13\"}")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("BGG_NOT_CONFIGURED"));
        org.mockito.Mockito.verifyNoInteractions(library);
    }

    @Test void deactivatedAccountIsRedirectedOnPageNavigation() throws Exception {
        var session = login();
        when(members.findById(member.id())).thenReturn(Optional.of(new Member(member.id(), member.email(), member.passwordHash(),
            "Camille", "Martin", null, false, false, false)));
        mvc.perform(get("/member.html").session(session)).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void csrfRequiredEvenForLogin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"membre@example.fr\",\"password\":\"Un secret de test\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void sessionWorksWithoutCurrentMembershipAndDoesNotLeakSecrets() throws Exception {
        var session = login();
        mvc.perform(post("/api/admin/library/bgg/preview").session(session).with(csrf()).contentType("application/json")
            .content("{\"url\":\"https://boardgamegeek.com/boardgame/13\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Cam"))
            .andExpect(jsonPath("$.membershipCurrent").value(false))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isForbidden());
    }

    @Test void wrongPasswordAndUnknownUserReturnSameGenericError() throws Exception {
        for (String email : new String[]{"membre@example.fr", "inconnu@example.fr"}) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":\"INVALID_CREDENTIALS\"}"));
        }
    }

    @Test void disabledMemberCannotLogin() throws Exception {
        var disabled = new Member(member.id(), member.email(), member.passwordHash(), "Camille", "Martin", null, false, false, false);
        when(members.findByEmail(member.email())).thenReturn(Optional.of(disabled));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"membre@example.fr\",\"password\":\"Un secret de test\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test void deactivationRevokesExistingSession() throws Exception {
        var session = login();
        when(members.findById(member.id())).thenReturn(Optional.of(new Member(member.id(), member.email(), member.passwordHash(),
            "Camille", "Martin", null, false, false, false)));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void logoutRequiresCsrfAndInvalidatesSession() throws Exception {
        var session = login();
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void malformedInputIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"invalide\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void tooManyAttemptsReturn429() throws Exception {
        when(throttle.allow(anyString())).thenReturn(false);
        when(throttle.retryAfterSeconds()).thenReturn(900L);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
            .content("{\"email\":\"membre@example.fr\",\"password\":\"incorrect\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "900"));
    }

    @Test void actualCsrfTokenAndSessionRotationWork() throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(csrfResult.getResponse().getContentAsString());
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String previousId = session.getId();
        mvc.perform(post("/api/auth/login").session(session).header(json.get("headerName").asText(), json.get("token").asText())
            .contentType("application/json").content("{\"email\":\"membre@example.fr\",\"password\":\"Un secret de test\"}"))
            .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }
}
