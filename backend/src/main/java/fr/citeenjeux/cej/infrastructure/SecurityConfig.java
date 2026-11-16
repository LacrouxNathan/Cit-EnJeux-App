package fr.citeenjeux.cej.infrastructure;

import fr.citeenjeux.cej.application.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", java.util.Map.of(
            "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionStrategy(HttpSessionCsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean AuthenticationManager authenticationManager(MemberRepository members, PasswordEncoder encoder) {
        String pendingPassword = encoder.encode(UUID.randomUUID().toString());
        var provider = new DaoAuthenticationProvider(email -> {
            var member = members.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("Identifiants invalides"));
            return User.withUsername(member.id().toString()).password(member.passwordHash() == null ? pendingPassword : member.passwordHash())
                .disabled(!member.active() || member.passwordHash() == null).roles(member.role()).build();
        });
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf, MemberRepository members) throws Exception {
        http
            .csrf(config -> config.csrfTokenRepository(csrf))
            .securityContext(config -> config.securityContextRepository(contexts))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/styles.css", "/login.js", "/favicon.svg", "/logo_cej.png", "/error",
                    "/activate.html", "/activate.js", "/api/invitations/activate",
                    "/reset-password.html", "/reset-password.js", "/api/password-reset/confirm",
                    "/api/auth/csrf", "/api/auth/login").permitAll()
                .requestMatchers("/member-resets.html", "/member-resets.js").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/password-reset/members").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/admin/users/*/password-reset").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers("/admin-users.html", "/admin-users.js").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET,
                    "/api/admin/library/base-games", "/api/admin/library/similar").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/api/admin/library/games", "/api/admin/library/games/*/archive", "/api/admin/library/games/*/restore",
                    "/api/admin/library/bgg/preview").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers(org.springframework.http.HttpMethod.PUT,
                    "/api/admin/library/games/*", "/api/admin/library/games/*/cover").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                    "/api/admin/library/games/*/cover").hasAnyRole("ADMIN", "BOARD_MEMBER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> authenticationRequired(req, res))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "ACCESS_DENIED")))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .deleteCookies("CEJ_SESSION")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                        FilterChain chain) throws ServletException, IOException {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.isAuthenticated()) {
                        var member = members.findById(UUID.fromString(auth.getName()));
                        var session = req.getSession(false);
                        if (member.isEmpty() || !member.get().active() || member.get().passwordHash() == null || session == null
                                || !java.util.Objects.equals(session.getAttribute("CEJ_SECURITY_VERSION"), member.get().securityVersion())) {
                            SecurityContextHolder.clearContext();
                            if (req.getSession(false) != null) req.getSession(false).invalidate();
                            authenticationRequired(req, res);
                            return;
                        }
                        // Recharge les droits : un retrait admin prend effet sans reconnexion.
                        var current = member.get();
                        var refreshed = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                            current.id().toString(), null, org.springframework.security.core.authority.AuthorityUtils
                                .createAuthorityList("ROLE_"+current.role()));
                        SecurityContextHolder.getContext().setAuthentication(refreshed);
                    }
                    chain.doFilter(req, res);
                }
            }, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void authenticationRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String accept = request.getHeader("Accept");
        boolean navigation = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
            && !path.startsWith("/api/") && !path.equals("/api")
            && (path.endsWith(".html") || accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html"));
        if (navigation) response.sendRedirect(request.getContextPath() + "/");
        else error(response, 401, "AUTHENTICATION_REQUIRED");
    }

    public static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
