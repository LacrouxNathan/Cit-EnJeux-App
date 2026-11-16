package fr.citeenjeux.cej.application;

import fr.citeenjeux.cej.domain.Member;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Amorçage explicite, uniquement si la base ne contient encore aucun compte. */
@Component
public class InitialAdmin implements ApplicationRunner {
    private final Environment env;
    private final MemberRepository members;
    private final PasswordEncoder encoder;

    public InitialAdmin(Environment env, MemberRepository members, PasswordEncoder encoder) {
        this.env = env; this.members = members; this.encoder = encoder;
    }

    @Override public void run(ApplicationArguments args) {
        if (!env.getProperty("cej.bootstrap.enabled", Boolean.class, false)) return;
        String email = required("cej.bootstrap.email").trim().toLowerCase(Locale.ROOT);
        String password = required("cej.bootstrap.password");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || password.length() < 6) {
            throw new IllegalArgumentException("Amorçage : email valide et mot de passe d’au moins 6 caractères requis.");
        }
        members.createInitialAdmin(new Member(UUID.randomUUID(), email, encoder.encode(password),
            required("cej.bootstrap.first-name"), required("cej.bootstrap.last-name"), null, true, false, true));
    }

    private String required(String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Paramètre d’amorçage manquant : " + name);
        return value;
    }
}
