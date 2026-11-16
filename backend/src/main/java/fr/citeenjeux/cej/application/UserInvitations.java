package fr.citeenjeux.cej.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserInvitations {
    public record NewUser(String email, String firstName, String lastName, String nickname, boolean membershipCurrent) { }
    public record UserSummary(UUID id, String email, String firstName, String lastName, String nickname,
                              boolean active, boolean administrator, boolean membershipCurrent, String invitationStatus, long version, boolean boardMember) { }
    public record Issued(UUID userId, String email, String token, Instant expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();

    public UserInvitations(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${cej.invitation.hours:48}") long hours) {
        if (hours < 1) throw new IllegalArgumentException("Durée d’invitation invalide");
        this.jdbc = jdbc; this.encoder = encoder; this.lifetime = Duration.ofHours(hours);
    }

    @Transactional(readOnly = true)
    public List<UserSummary> list(UUID actor, int page) { return list(actor,page,"","all","all","all"); }

    @Transactional(readOnly = true)
    public List<UserSummary> list(UUID actor,int page,String search,String status,String role,String membership) {
        requireAdmin(actor);
        if(page<0||page>1000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        var where=new StringBuilder(" WHERE true");var args=new ArrayList<Object>();
        if(search!=null&&!search.isBlank()) {
            where.append(" AND strpos(lower(concat_ws(' ',u.first_name,u.last_name,u.nickname,u.email)),lower(?))>0");args.add(search.strip());
        }
        switch(status) {
            case "all" -> { }
            case "active" -> where.append(" AND u.active");
            case "inactive" -> where.append(" AND NOT u.active");
            case "pending" -> where.append(" AND u.password_hash IS NULL");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        }
        switch(role) {
            case "all" -> { }
            case "admin" -> where.append(" AND u.administrator");
            case "member" -> where.append(" AND NOT u.administrator AND NOT u.board_member");
            case "board" -> where.append(" AND u.board_member");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        }
        switch(membership) {
            case "all" -> { }
            case "current" -> where.append(" AND u.membership_current");
            case "expired" -> where.append(" AND NOT u.membership_current");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        }
        args.add(page*20);
        return jdbc.query("""
            SELECT u.*,CASE WHEN u.password_hash IS NOT NULL THEN 'ACTIVATED'
                WHEN i.expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE 'PENDING' END invitation_status
            FROM app_user u LEFT JOIN user_invitation i ON i.user_id=u.id
            """+where+" ORDER BY u.created_at DESC,u.id LIMIT 20 OFFSET ?",MemberManagement::summary,args.toArray());
    }

    @Transactional
    public Issued create(UUID actor, NewUser data) {
        requireAdmin(actor);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, email, first_name, last_name, nickname, membership_current)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, data.email().strip().toLowerCase(Locale.ROOT), data.firstName().strip(),
                data.lastName().strip(), data.nickname() == null || data.nickname().isBlank() ? null : data.nickname().strip(), data.membershipCurrent());
        audit(actor, id, "USER_CREATED");
        return issue(actor, id, data.email().strip().toLowerCase(Locale.ROOT));
    }

    @Transactional
    public Issued resend(UUID actor, UUID id) {
        requireAdmin(actor);
        var rows = jdbc.queryForList("SELECT email, active, password_hash FROM app_user WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var user = rows.getFirst();
        if (!Boolean.TRUE.equals(user.get("active")) || user.get("password_hash") != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_NOT_ALLOWED");
        return issue(actor, id, (String) user.get("email"));
    }

    private Issued issue(UUID actor, UUID id, String email) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(lifetime);
        jdbc.update("""
            INSERT INTO user_invitation (user_id, token_hash, expires_at) VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                expires_at = EXCLUDED.expires_at, consumed_at = NULL
            """, id, hash(token), Timestamp.from(expires));
        jdbc.update("UPDATE app_user SET version=version+1 WHERE id=?",id);
        audit(actor, id, "INVITATION_ISSUED");
        return new Issued(id, email, token, expires);
    }

    @Transactional
    public void activate(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) invalidInvitation();
        // Même ordre de verrouillage que le renvoi : compte puis invitation.
        var candidate = jdbc.queryForList("SELECT user_id FROM user_invitation WHERE token_hash = ?", hash(token));
        if (candidate.isEmpty()) invalidInvitation();
        UUID userId = (UUID) candidate.getFirst().get("user_id");
        jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", userId);
        var rows = jdbc.queryForList("SELECT user_id FROM user_invitation WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > clock_timestamp() FOR UPDATE", hash(token));
        if (rows.isEmpty()) invalidInvitation();
        UUID id = (UUID) rows.getFirst().get("user_id");
        int updated = jdbc.update("UPDATE app_user SET password_hash = ?,version=version+1 WHERE id = ? AND active = true AND password_hash IS NULL", encoder.encode(password), id);
        if (updated != 1) invalidInvitation();
        jdbc.update("UPDATE user_invitation SET consumed_at = CURRENT_TIMESTAMP WHERE user_id = ?", id);
        audit(id, id, "ACCOUNT_ACTIVATED");
    }

    @Transactional
    public Issued requestPasswordReset(UUID actor, UUID id) {
        requireResetAccess(actor);
        var rows = jdbc.queryForList("SELECT email,active,password_hash,security_version FROM app_user WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        var user = rows.getFirst();
        if (!Boolean.TRUE.equals(user.get("active")) || user.get("password_hash") == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESET_NOT_ALLOWED");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(Duration.ofHours(1));
        jdbc.update("""
            INSERT INTO password_reset(user_id,token_hash,expires_at,security_version) VALUES (?,?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,expires_at=EXCLUDED.expires_at,
                security_version=EXCLUDED.security_version,consumed_at=NULL
            """, id,hash(token),Timestamp.from(expires),user.get("security_version"));
        audit(actor,id,"PASSWORD_RESET_REQUESTED");
        return new Issued(id,(String) user.get("email"),token,expires);
    }

    @Transactional
    public void resetPassword(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || password == null || password.isBlank() || password.length()<6)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESET");
        var candidates=jdbc.queryForList("SELECT user_id FROM password_reset WHERE token_hash=?",hash(token));
        if(candidates.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESET");
        UUID id=(UUID)candidates.getFirst().get("user_id");
        jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",id);
        var rows=jdbc.queryForList("""
            SELECT security_version FROM password_reset WHERE user_id=? AND token_hash=?
                AND consumed_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE
            """,id,hash(token));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESET");
        int changed=jdbc.update("""
            UPDATE app_user SET password_hash=?,version=version+1,security_version=security_version+1
            WHERE id=? AND active AND password_hash IS NOT NULL AND security_version=?
            """,encoder.encode(password),id,rows.getFirst().get("security_version"));
        if(changed!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESET");
        jdbc.update("UPDATE password_reset SET consumed_at=clock_timestamp() WHERE user_id=?",id);
        audit(id,id,"PASSWORD_RESET_COMPLETED");
    }

    private void invalidInvitation() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INVITATION"); }

    public record ResetMember(UUID id, String displayName) { }

    @Transactional(readOnly=true)
    public List<ResetMember> resetMembers(UUID actor,String search,int page) {
        requireResetAccess(actor);
        if(page<0||page>1000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        return jdbc.query("""
            SELECT id,COALESCE(NULLIF(nickname,''),concat_ws(' ',first_name,last_name)) display_name
            FROM app_user WHERE active AND password_hash IS NOT NULL
            AND strpos(lower(COALESCE(NULLIF(nickname,''),concat_ws(' ',first_name,last_name))),lower(?))>0
            ORDER BY lower(COALESCE(NULLIF(nickname,''),concat_ws(' ',first_name,last_name))),id LIMIT 20 OFFSET ?
            """,(rs,n)->new ResetMember(rs.getObject("id",UUID.class),rs.getString("display_name")),search.strip(),page*20);
    }

    private void requireResetAccess(UUID actor) {
        if(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=? AND active AND password_hash IS NOT NULL AND (administrator OR board_member)",Integer.class,actor)!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void requireAdmin(UUID actor) {
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ? AND active AND administrator", Integer.class, actor) != 1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void audit(UUID actor, UUID subject, String action) {
        jdbc.update("INSERT INTO account_audit(id, actor_id, subject_id, action) VALUES (?, ?, ?, ?)", UUID.randomUUID(), actor, subject, action);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
