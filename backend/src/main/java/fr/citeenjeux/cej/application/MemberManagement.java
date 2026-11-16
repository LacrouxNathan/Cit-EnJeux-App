package fr.citeenjeux.cej.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberManagement {
    public record Update(@NotBlank @Email String email,@NotBlank String firstName,@NotBlank String lastName,
        String nickname,@NotNull Boolean membershipCurrent,@NotNull Boolean administrator,@NotNull Boolean active,
        @NotNull @PositiveOrZero Long version,Boolean boardMember) {
        public Update(String email,String firstName,String lastName,String nickname,Boolean membershipCurrent,Boolean administrator,Boolean active,Long version) {
            this(email,firstName,lastName,nickname,membershipCurrent,administrator,active,version,null);
        }
        public Update { if(email!=null)email=email.strip().toLowerCase(Locale.ROOT); }
    }
    public record Audit(UUID id,UUID actorId,String actorName,String action,String changes,Instant createdAt) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public MemberManagement(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private void requireAdmin(UUID actor) {
        if(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=? AND active AND administrator AND password_hash IS NOT NULL",Integer.class,actor)!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
    }
    private static final String SELECT="""
        SELECT u.*,CASE WHEN u.password_hash IS NOT NULL THEN 'ACTIVATED'
            WHEN i.expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE 'PENDING' END invitation_status
        FROM app_user u LEFT JOIN user_invitation i ON i.user_id=u.id
        """;
    public static UserInvitations.UserSummary summary(ResultSet rs,int row)throws SQLException {
        return new UserInvitations.UserSummary(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("first_name"),
            rs.getString("last_name"),rs.getString("nickname"),rs.getBoolean("active"),rs.getBoolean("administrator"),
            rs.getBoolean("membership_current"),rs.getString("invitation_status"),rs.getLong("version"),rs.getBoolean("board_member"));
    }
    private UserInvitations.UserSummary detail(UUID id) {
        var users=jdbc.query(SELECT+" WHERE u.id=?",MemberManagement::summary,id);
        if(users.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"USER_NOT_FOUND");
        return users.getFirst();
    }
    @Transactional(readOnly=true)
    public UserInvitations.UserSummary get(UUID actor,UUID id){requireAdmin(actor);return detail(id);}
    @Transactional
    public UserInvitations.UserSummary update(UUID actor,UUID id,Update data) {
        // Un seul changement de rôle/statut à la fois : protège le dernier admin même entre instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(728314921)");
        requireAdmin(actor);
        var rows=jdbc.queryForList("SELECT * FROM app_user WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"USER_NOT_FOUND");
        var old=rows.getFirst();
        if(data.version()==null||((Number)old.get("version")).longValue()!=data.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"STALE_USER_VERSION");
        if(data.email()==null||data.email().isBlank()||data.firstName()==null||data.firstName().isBlank()||data.lastName()==null||data.lastName().isBlank()
            ||data.active()==null||data.administrator()==null||data.membershipCurrent()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");
        boolean boardMember=data.boardMember()==null?!data.administrator()&&Boolean.TRUE.equals(old.get("board_member")):data.boardMember();
        if(boardMember&&data.administrator())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_ROLE");
        boolean wasUsableAdmin=Boolean.TRUE.equals(old.get("active"))&&Boolean.TRUE.equals(old.get("administrator"))&&old.get("password_hash")!=null;
        if(wasUsableAdmin&&!(data.active()&&data.administrator())) {
            int others=jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id<>? AND active AND administrator AND password_hash IS NOT NULL",Integer.class,id);
            if(others==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"LAST_ACTIVE_ADMIN");
        }
        var after=new LinkedHashMap<String,Object>();
        after.put("email",data.email());after.put("first_name",data.firstName().strip());after.put("last_name",data.lastName().strip());
        after.put("nickname",data.nickname()==null||data.nickname().isBlank()?null:data.nickname().strip());
        after.put("membership_current",data.membershipCurrent());after.put("administrator",data.administrator());after.put("board_member",boardMember);after.put("active",data.active());
        var changes=new LinkedHashMap<String,Object>();
        for(var entry:after.entrySet())if(!Objects.equals(old.get(entry.getKey()),entry.getValue())) {
            var values=new LinkedHashMap<String,Object>();values.put("before",old.get(entry.getKey()));values.put("after",entry.getValue());changes.put(entry.getKey(),values);
        }
        if(changes.isEmpty())return detail(id);
        boolean revokeSessions=changes.containsKey("email")||changes.containsKey("active");
        jdbc.update("""
            UPDATE app_user SET email=?,first_name=?,last_name=?,nickname=?,membership_current=?,administrator=?,board_member=?,active=?,
                version=version+1,security_version=security_version+? WHERE id=?
            """,data.email(),after.get("first_name"),after.get("last_name"),after.get("nickname"),data.membershipCurrent(),data.administrator(),boardMember,data.active(),revokeSessions?1:0,id);
        if(changes.containsKey("email")||!data.active()) {
            // Un lien envoyé à l’ancienne adresse ou avant une désactivation ne doit plus activer le compte.
            jdbc.update("UPDATE user_invitation SET expires_at=LEAST(expires_at,clock_timestamp()) WHERE user_id=? AND consumed_at IS NULL",id);
        }
        try {jdbc.update("INSERT INTO account_audit(id,actor_id,subject_id,action,changes) VALUES(?,?,?,'ACCOUNT_UPDATED',?::jsonb)",UUID.randomUUID(),actor,id,json.writeValueAsString(changes));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return detail(id);
    }
    @Transactional(readOnly=true)
    public List<Audit> history(UUID actor,UUID id) {
        requireAdmin(actor);detail(id);
        return jdbc.query("""
            SELECT a.*,u.first_name || ' ' || u.last_name actor_name FROM account_audit a
            LEFT JOIN app_user u ON u.id=a.actor_id WHERE a.subject_id=? ORDER BY a.created_at DESC,a.id
            """,(rs,n)->new Audit(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),rs.getString("action"),rs.getString("changes"),rs.getTimestamp("created_at").toInstant()),id);
    }
}
