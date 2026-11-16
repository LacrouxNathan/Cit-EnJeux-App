package fr.citeenjeux.cej.api;

import fr.citeenjeux.cej.application.MemberRepository;
import fr.citeenjeux.cej.infrastructure.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(@NotBlank @Email String email, @NotBlank String password) {
        public Credentials {
            if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
        }
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record CurrentMember(UUID id, String displayName, boolean administrator, boolean membershipCurrent, boolean boardMember, boolean canManageGames) { }
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final MemberRepository members;
    private final LoginThrottle throttle;

    public AuthController(AuthenticationManager manager, SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessions, MemberRepository members, LoginThrottle throttle) {
        this.manager = manager; this.contexts = contexts; this.sessions = sessions;
        this.members = members; this.throttle = throttle;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Credentials credentials,
            HttpServletRequest request, HttpServletResponse response) {
        if (!throttle.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(429).header("Retry-After", Long.toString(throttle.retryAfterSeconds()))
                .body(Map.of("code", "TOO_MANY_ATTEMPTS"));
        }
        try {
            var auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                credentials.email(), credentials.password()));
            var member = members.findById(UUID.fromString(auth.getName())).orElseThrow(() -> new org.springframework.security.authentication.DisabledException("Account unavailable"));
            if (!member.active() || member.passwordHash() == null) throw new org.springframework.security.authentication.DisabledException("Account unavailable");
            sessions.onAuthentication(auth, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            request.getSession().setAttribute("CEJ_SECURITY_VERSION", member.securityVersion());
            return ResponseEntity.ok(Map.of("authenticated", true));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            return ResponseEntity.status(401).body(Map.of("code", "INVALID_CREDENTIALS"));
        }
    }

    @GetMapping("/me")
    public CurrentMember me(Principal principal) {
        var member = members.findById(UUID.fromString(principal.getName())).orElseThrow();
        return new CurrentMember(member.id(), member.displayName(), member.administrator(), member.membershipCurrent(), member.boardMember(), member.canManageGames());
    }
}
