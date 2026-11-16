package fr.citeenjeux.cej.infrastructure;

import fr.citeenjeux.cej.application.MemberRepository;
import fr.citeenjeux.cej.domain.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMemberRepository implements MemberRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Member> mapper = (rs, row) -> new Member(
        rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
        rs.getString("first_name"), rs.getString("last_name"), rs.getString("nickname"),
        rs.getBoolean("active"), rs.getBoolean("membership_current"), rs.getBoolean("administrator"), rs.getLong("security_version"), rs.getBoolean("board_member"));

    public JdbcMemberRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Member> findByEmail(String email) {
        return jdbc.query("SELECT * FROM app_user WHERE email = ?", mapper, email).stream().findFirst();
    }

    @Override public Optional<Member> findById(UUID id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override @Transactional
    public boolean createInitialAdmin(Member member) {
        // Sérialise les amorçages concurrents, même quand la table est vide.
        jdbc.execute("LOCK TABLE app_user IN EXCLUSIVE MODE");
        if (jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class) != 0) return false;
        jdbc.update("""
            INSERT INTO app_user (id, email, password_hash, first_name, last_name, active, administrator)
            VALUES (?, ?, ?, ?, ?, true, true)
            """, member.id(), member.email(), member.passwordHash(), member.firstName(), member.lastName());
        return true;
    }
}
