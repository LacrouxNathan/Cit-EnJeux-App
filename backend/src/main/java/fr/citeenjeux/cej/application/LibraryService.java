package fr.citeenjeux.cej.application;

import static fr.citeenjeux.cej.domain.LibraryModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.text.Normalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class LibraryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private record Row(GameCard card, boolean archived, String reason, long version, int totalCopies) {
        AdminGame admin() { return new AdminGame(card, archived, reason, version, totalCopies); }
        EditorGame editor() { return new EditorGame(card,archived,reason,version); }
    }
    private static final String SELECT = """
        SELECT g.*, p.name base_name, EXISTS(SELECT 1 FROM game_cover cv WHERE cv.game_id=g.id) has_cover,
          (SELECT count(*) FROM game_copy c WHERE c.game_id=g.id) total_copies,
          (SELECT count(*) FROM game_copy c WHERE c.game_id=g.id AND NOT c.archived) active_copies,
          (SELECT count(*) FROM game_copy c WHERE c.game_id=g.id AND NOT c.archived AND NOT c.blocked) available_copies
        FROM game g LEFT JOIN game p ON p.id=g.base_game_id
        """;
    private static final String VISIBLE = "NOT g.archived AND EXISTS(SELECT 1 FROM game_copy c WHERE c.game_id=g.id AND NOT c.archived)";
    private final RowMapper<Row> mapper = (rs, n) -> new Row(new GameCard(rs.getObject("id", UUID.class), rs.getString("name"),
        GameType.valueOf(rs.getString("type")), rs.getObject("base_game_id", UUID.class), rs.getString("base_name"),
        rs.getString("publisher"), rs.getString("author"), rs.getObject("publication_year", Integer.class),
        rs.getObject("min_players", Integer.class), rs.getObject("max_players", Integer.class), rs.getObject("duration_minutes", Integer.class),
        rs.getObject("minimum_age", Integer.class), rs.getString("description"), rs.getString("categories"), rs.getString("mechanics"),
        rs.getBoolean("has_cover"), rs.getInt("active_copies"), rs.getInt("available_copies"),rs.getObject("bgg_id",Long.class),rs.getObject("bgg_id")==null?null:"https://boardgamegeek.com/boardgame/"+rs.getLong("bgg_id")),
        rs.getBoolean("archived"), rs.getString("archive_reason"), rs.getLong("version"), rs.getInt("total_copies"));

    public LibraryService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private void fail(HttpStatus status, String code) { throw new ResponseStatusException(status, code); }
    private void writeLock() { jdbc.execute("SELECT pg_advisory_xact_lock(728314920)"); }
    private record Access(boolean administrator,boolean editor) { }
    private Access access(UUID actor) {
        var result=jdbc.query("SELECT administrator,board_member FROM app_user WHERE id=? AND active AND password_hash IS NOT NULL",
            (rs,n)->new Access(rs.getBoolean("administrator"),rs.getBoolean("administrator")||rs.getBoolean("board_member")),actor);
        if(result.isEmpty())fail(HttpStatus.FORBIDDEN,"ACCOUNT_INACTIVE");
        return result.getFirst();
    }
    private boolean admin(UUID actor) { return access(actor).administrator(); }
    private void requireAdmin(UUID actor) { if(!admin(actor)) fail(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED"); }
    private void requireEditor(UUID actor) { if(!access(actor).editor())fail(HttpStatus.FORBIDDEN,"GAME_EDITOR_REQUIRED"); }
    private Row row(UUID id, boolean administrator) {
        var rows=jdbc.query(SELECT+" WHERE g.id=?"+(administrator?"":" AND "+VISIBLE),mapper,id);
        if(rows.isEmpty()) fail(HttpStatus.NOT_FOUND,"GAME_NOT_FOUND");
        return rows.getFirst();
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.strip(); }
    private void version(long actual, Long expected) {
        if(expected==null||actual!=expected) fail(HttpStatus.CONFLICT,"STALE_VERSION");
    }
    private Map<String,Object> fields(Row r) {
        var map=json.convertValue(r.card(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        for(String key:List.of("id","baseGameName","activeCopies","availableCopies","sourceUrl")) map.remove(key);
        map.put("archived",r.archived()); map.put("archiveReason",r.reason()); return map;
    }
    private void audit(UUID actor, String entity, UUID id, String action, Map<String,Object> before, Map<String,Object> after) {
        var changes=new LinkedHashMap<String,Object>(); var keys=new LinkedHashSet<String>(); keys.addAll(before.keySet());keys.addAll(after.keySet());
        for(String key:keys) if(!Objects.equals(before.get(key),after.get(key))) {
            var values=new LinkedHashMap<String,Object>(); values.put("before",before.get(key));values.put("after",after.get(key));changes.put(key,values);
        }
        if(changes.isEmpty()) return;
        try { jdbc.update("INSERT INTO library_audit(id,entity_type,entity_id,actor_id,action,changes) VALUES(?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(),entity,id,actor,action,json.writeValueAsString(changes)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Page<?> list(UUID actor, String search, Integer players, String availability, String sort, int page, boolean archived) {
        var rights=access(actor);boolean administrator=rights.administrator();
        if(page<0||page>1000000||players!=null&&players<1) fail(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        var where=new StringBuilder(rights.editor()?(archived?"TRUE":"NOT g.archived"):VISIBLE);
        var args=new ArrayList<Object>();
        if(clean(search)!=null) { where.append(" AND strpos(lower(g.name),lower(?))>0"); args.add(search.strip()); }
        if(players!=null) { where.append(" AND g.min_players<=? AND g.max_players>=?");args.add(players);args.add(players); }
        if("available".equals(availability)) where.append(" AND EXISTS(SELECT 1 FROM game_copy c WHERE c.game_id=g.id AND NOT c.archived AND NOT c.blocked)");
        else if("unavailable".equals(availability)) where.append(" AND NOT EXISTS(SELECT 1 FROM game_copy c WHERE c.game_id=g.id AND NOT c.archived AND NOT c.blocked)");
        else if(!"all".equals(availability)) fail(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        String order=switch(sort) { case "name"->"lower(g.name),g.id"; case "recent"->"g.created_at DESC,g.id";
            case "availability"->"available_copies DESC,lower(g.name),g.id"; default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SORT"); };
        long total=jdbc.queryForObject("SELECT count(*) FROM game g WHERE "+where,Long.class,args.toArray());
        args.add(20);args.add(page*20);
        var rows=jdbc.query(SELECT+" WHERE "+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",mapper,args.toArray());
        if(administrator)return new Page<>(rows.stream().map(Row::admin).toList(),page,20,total);
        if(rights.editor())return new Page<>(rows.stream().map(Row::editor).toList(),page,20,total);
        return new Page<>(rows.stream().map(Row::card).toList(),page,20,total);
    }
    public List<AdminGame> baseGames(UUID actor) {requireEditor(actor);return jdbc.query(SELECT+" WHERE g.type='BASE_GAME' ORDER BY lower(g.name),g.id",mapper).stream().map(Row::admin).toList();}
    public Object detail(UUID actor, UUID id) {
        var rights=access(actor);boolean administrator=rights.administrator(); Row game=row(id,rights.editor());
        var children=jdbc.query(SELECT+" WHERE g.base_game_id=?"+(rights.editor()?"":" AND "+VISIBLE)+" ORDER BY lower(g.name),g.id",mapper,id);
        if(administrator)return new AdminDetails(game.admin(),copies(id,true).stream().map(this::adminCopy).toList(),children.stream().map(Row::admin).toList());
        if(rights.editor())return new EditorDetails(game.editor(),copies(id,false).stream().map(this::copyView).toList(),children.stream().map(Row::editor).toList());
        return new GameDetails(game.card(),copies(id,false).stream().map(this::copyView).toList(),children.stream().map(Row::card).toList());
    }
    public List<AdminGame> similar(UUID actor,String name,UUID exclude) {
        requireEditor(actor); String normalized=normalize(name);
        if(normalized.isBlank()) return List.of();
        return jdbc.query(SELECT,mapper).stream().filter(r->!r.card().id().equals(exclude))
            .filter(r->{String other=normalize(r.card().name());return other.contains(normalized)||normalized.contains(other)||distance(normalized,other)<=Math.max(1,Math.min(normalized.length(),other.length())/5);})
            .limit(10).map(Row::admin).toList();
    }
    private static String normalize(String s) { return Normalizer.normalize(s==null?"":s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]",""); }
    private static int distance(String a,String b) {
        // Comparaison bornée pour les noms très longs, sans imposer de limite métier.
        if(a.length()>300||b.length()>300) return Integer.MAX_VALUE;
        int[] p=new int[b.length()+1];for(int j=0;j<p.length;j++)p[j]=j;
        for(int i=1;i<=a.length();i++){int[] n=new int[b.length()+1];n[0]=i;for(int j=1;j<n.length;j++)n[j]=Math.min(Math.min(n[j-1]+1,p[j]+1),p[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));p=n;}return p[b.length()];
    }
    public AdminGame save(UUID actor, UUID id, GameInput data) {
        requireEditor(actor);writeLock();
        if(data.name()==null||data.name().isBlank()||data.type()==null) fail(HttpStatus.BAD_REQUEST,"INVALID_GAME");
        if(data.minPlayers()!=null&&data.maxPlayers()!=null&&data.minPlayers()>data.maxPlayers()) fail(HttpStatus.BAD_REQUEST,"INVALID_PLAYER_RANGE");
        Row old=id==null?null:row(id,true);
        if(old!=null) { version(old.version(),data.version());if(old.archived())fail(HttpStatus.CONFLICT,"GAME_ARCHIVED"); }
        if(data.type()==GameType.BASE_GAME) {
            if(data.baseGameId()!=null) fail(HttpStatus.BAD_REQUEST,"BASE_CANNOT_HAVE_PARENT");
        } else {
            if(data.baseGameId()==null||data.baseGameId().equals(id)) fail(HttpStatus.BAD_REQUEST,"INVALID_BASE_GAME");
            Row parent=row(data.baseGameId(),true);
            if(parent.card().type()!=GameType.BASE_GAME||parent.archived()&&data.type()==GameType.EXPANSION) fail(HttpStatus.CONFLICT,"BASE_GAME_INACTIVE");
            if(id!=null&&jdbc.queryForObject("SELECT count(*) FROM game WHERE base_game_id=?",Integer.class,id)>0) fail(HttpStatus.CONFLICT,"GAME_HAS_EXTENSIONS");
        }
        Object[] values={data.name().strip(),data.type().name(),data.baseGameId(),clean(data.publisher()),clean(data.author()),data.publicationYear(),data.minPlayers(),data.maxPlayers(),data.durationMinutes(),data.minimumAge(),clean(data.description()),clean(data.categories()),clean(data.mechanics()),data.bggId()};
        UUID gameId=id==null?UUID.randomUUID():id;
        var args=new ArrayList<>(Arrays.asList(values));args.add(gameId);
        if(old==null) jdbc.update("INSERT INTO game(name,type,base_game_id,publisher,author,publication_year,min_players,max_players,duration_minutes,minimum_age,description,categories,mechanics,bgg_id,id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",args.toArray());
        else jdbc.update("UPDATE game SET name=?,type=?,base_game_id=?,publisher=?,author=?,publication_year=?,min_players=?,max_players=?,duration_minutes=?,minimum_age=?,description=?,categories=?,mechanics=?,bgg_id=?,version=version+1 WHERE id=?",args.toArray());
        Row result=row(gameId,true);audit(actor,"GAME",gameId,old==null?"CREATED":"UPDATED",old==null?Map.of():fields(old),fields(result));return result.admin();
    }
    public void archiveGame(UUID actor,UUID id,Revision data,boolean restore) {
        requireEditor(actor);writeLock();Row current=row(id,true);version(current.version(),data.version());
        if(current.archived()!=restore) fail(HttpStatus.CONFLICT,"INVALID_ARCHIVE_STATE");
        if(restore) {
            if(current.card().type()==GameType.EXPANSION) {
                Row parent=row(current.card().baseGameId(),true);
                if(parent.archived()||parent.card().type()!=GameType.BASE_GAME) fail(HttpStatus.CONFLICT,"BASE_GAME_INACTIVE");
            }
            jdbc.update("UPDATE game SET archived=false,archive_reason=NULL,version=version+1 WHERE id=?",id);
            var after=fields(row(id,true));after.put("restorationReason",data.reason().strip());audit(actor,"GAME",id,"RESTORED",fields(current),after);return;
        }
        var all=jdbc.query(SELECT+" WHERE g.id=? OR g.base_game_id=? ORDER BY g.id",mapper,id,id);
        if(all.size()>1&&!data.confirmed()) fail(HttpStatus.CONFLICT,"CASCADE_CONFIRMATION_REQUIRED");
        for(Row game:all) if(game.card().activeCopies()>0) fail(HttpStatus.CONFLICT,"ACTIVE_COPIES_PREVENT_ARCHIVE");
        for(Row game:all) if(!game.archived()) {
            jdbc.update("UPDATE game SET archived=true,archive_reason=?,version=version+1 WHERE id=?",data.reason().strip(),game.card().id());
            audit(actor,"GAME",game.card().id(),"ARCHIVED",fields(game),fields(row(game.card().id(),true)));
        }
    }
    public List<Location> locations(UUID actor) { admin(actor);return locationList(); }
    private List<Location> locationList() {
        return jdbc.query("""
            WITH RECURSIVE paths AS (
                SELECT id,parent_id,name,name AS path,archived FROM library_location WHERE parent_id IS NULL
                UNION ALL SELECT l.id,l.parent_id,l.name,p.path || ' / ' || l.name,l.archived OR p.archived
                FROM library_location l JOIN paths p ON l.parent_id=p.id
            ) SELECT p.*,NOT EXISTS(SELECT 1 FROM library_location c WHERE c.parent_id=p.id) leaf
              FROM paths p WHERE NOT p.archived ORDER BY p.path,p.id
            """,(rs,n)->new Location(rs.getObject("id",UUID.class),rs.getObject("parent_id",UUID.class),rs.getString("name"),rs.getString("path"),rs.getBoolean("leaf")));
    }
    public Location createLocation(UUID actor,LocationInput data) {
        requireAdmin(actor);writeLock();
        if(data.parentId()!=null) {
            var parent=locationList().stream().filter(l->l.id().equals(data.parentId())).findFirst();
            if(parent.isEmpty()) fail(HttpStatus.BAD_REQUEST,"INVALID_LOCATION_PARENT");
            if(jdbc.queryForObject("SELECT count(*) FROM game_copy WHERE location_id=?",Integer.class,data.parentId())>0) fail(HttpStatus.CONFLICT,"LOCATION_CONTAINS_COPIES");
        }
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO library_location(id,parent_id,name) VALUES(?,?,?)",id,data.parentId(),data.name().strip());
        audit(actor,"LOCATION",id,"CREATED",Map.of(),Map.of("name",data.name().strip(),"path",locationList().stream().filter(l->l.id().equals(id)).findFirst().orElseThrow().path()));
        return locationList().stream().filter(l->l.id().equals(id)).findFirst().orElseThrow();
    }
    private List<Map<String,Object>> copies(UUID gameId,boolean archived) {
        return jdbc.queryForList("SELECT * FROM game_copy WHERE game_id=?"+(archived?"":" AND NOT archived")+" ORDER BY created_at,id",gameId);
    }
    private Map<String,Object> copy(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM game_copy WHERE id=?",id);
        if(rows.isEmpty())fail(HttpStatus.NOT_FOUND,"COPY_NOT_FOUND");return rows.getFirst();
    }
    private CopyView copyView(Map<String,Object> c) {
        UUID location=(UUID)c.get("location_id");String path=locationList().stream().filter(l->l.id().equals(location)).map(Location::path).findFirst().orElse("Emplacement archivé");
        return new CopyView((UUID)c.get("id"),location,path,(String)c.get("physical_condition"),(String)c.get("missing_pieces"),(Boolean)c.get("blocked"),(String)c.get("block_reason"));
    }
    private AdminCopy adminCopy(Map<String,Object> c) { return new AdminCopy(copyView(c),(String)c.get("comments"),(Boolean)c.get("archived"),(String)c.get("archive_reason"),((Number)c.get("version")).longValue()); }
    private Map<String,Object> copyFields(Map<String,Object> c) {var result=new LinkedHashMap<>(c);result.remove("created_at");result.remove("version");return result;}
    private void validateCopy(CopyInput data) {
        if(data.blocked()&&clean(data.blockReason())==null) fail(HttpStatus.BAD_REQUEST,"BLOCK_REASON_REQUIRED");
        if(locationList().stream().noneMatch(l->l.id().equals(data.locationId())&&l.leaf())) fail(HttpStatus.BAD_REQUEST,"LOCATION_MUST_BE_ACTIVE_LEAF");
    }
    private void reserve(String inventory,UUID copyId) {
        String normalized=inventory.toLowerCase(Locale.ROOT);
        var existing=jdbc.queryForList("SELECT copy_id FROM inventory_identifier WHERE normalized_id=?",UUID.class,normalized);
        // Un ancien identifiant reste réservé, y compris pour son ancien exemplaire.
        if(!existing.isEmpty()) fail(HttpStatus.CONFLICT,"INVENTORY_IDENTIFIER_RESERVED");
        jdbc.update("INSERT INTO inventory_identifier(normalized_id,copy_id) VALUES(?,?)",normalized,copyId);
    }
    public List<AdminCopy> createCopies(UUID actor,UUID gameId,List<CopyInput> data) {
        requireAdmin(actor);writeLock();if(row(gameId,true).archived())fail(HttpStatus.CONFLICT,"GAME_ARCHIVED");
        if(data==null||data.isEmpty())fail(HttpStatus.BAD_REQUEST,"EMPTY_COPIES");
        var result=new ArrayList<AdminCopy>();
        for(CopyInput item:data) {
            validateCopy(item);UUID id=UUID.randomUUID();
            String inventoryId;
            do { inventoryId="CEJ-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT); }
            while(jdbc.queryForObject("SELECT count(*) FROM inventory_identifier WHERE normalized_id=?",Integer.class,inventoryId.toLowerCase(Locale.ROOT))>0);
            jdbc.update("INSERT INTO game_copy(id,game_id,inventory_id,location_id,physical_condition,missing_pieces,comments,blocked,block_reason) VALUES(?,?,?,?,?,?,?,?,?)",id,gameId,inventoryId,item.locationId(),item.physicalCondition().strip(),clean(item.missingPieces()),clean(item.comments()),item.blocked(),item.blocked()?clean(item.blockReason()):null);
            reserve(inventoryId,id);Map<String,Object> c=copy(id);audit(actor,"COPY",id,"CREATED",Map.of(),copyFields(c));result.add(adminCopy(c));
        }
        return result;
    }
    public AdminCopy updateCopy(UUID actor,UUID id,CopyInput data) {
        requireAdmin(actor);writeLock();Map<String,Object> old=copy(id);version(((Number)old.get("version")).longValue(),data.version());
        if((Boolean)old.get("archived"))fail(HttpStatus.CONFLICT,"COPY_ARCHIVED");
        validateCopy(data);
        jdbc.update("UPDATE game_copy SET location_id=?,physical_condition=?,missing_pieces=?,comments=?,blocked=?,block_reason=?,version=version+1 WHERE id=?",data.locationId(),data.physicalCondition().strip(),clean(data.missingPieces()),clean(data.comments()),data.blocked(),data.blocked()?clean(data.blockReason()):null,id);
        Map<String,Object> result=copy(id);audit(actor,"COPY",id,"UPDATED",copyFields(old),copyFields(result));return adminCopy(result);
    }
    public void archiveCopy(UUID actor,UUID id,Revision data,boolean restore) {
        requireAdmin(actor);writeLock();var old=copy(id);version(((Number)old.get("version")).longValue(),data.version());
        if((Boolean)old.get("archived")!=restore)fail(HttpStatus.CONFLICT,"INVALID_ARCHIVE_STATE");
        if(restore) {
            if(row((UUID)old.get("game_id"),true).archived())fail(HttpStatus.CONFLICT,"GAME_ARCHIVED");
            UUID location=(UUID)old.get("location_id");if(locationList().stream().noneMatch(l->l.id().equals(location)&&l.leaf()))fail(HttpStatus.CONFLICT,"LOCATION_MUST_BE_ACTIVE_LEAF");
            jdbc.update("UPDATE game_copy SET archived=false,archive_reason=NULL,blocked=true,block_reason='État et autorisation à revoir après restauration',version=version+1 WHERE id=?",id);
        } else jdbc.update("UPDATE game_copy SET archived=true,archive_reason=?,version=version+1 WHERE id=?",data.reason().strip(),id);
        var after=copyFields(copy(id));if(restore)after.put("restoration_reason",data.reason().strip());audit(actor,"COPY",id,restore?"RESTORED":"ARCHIVED",copyFields(old),after);
    }
    public record Cover(byte[] content,String type) { }
    public Cover cover(UUID actor,UUID id) {row(id,access(actor).editor());var data=jdbc.query("SELECT content,content_type FROM game_cover WHERE game_id=?",(rs,n)->new Cover(rs.getBytes(1),rs.getString(2)),id);if(data.isEmpty())fail(HttpStatus.NOT_FOUND,"COVER_NOT_FOUND");return data.getFirst();}
    public Cover oldCover(UUID actor,UUID id,UUID revision) {
        requireAdmin(actor);var data=jdbc.query("SELECT content,content_type FROM game_cover_revision WHERE game_id=? AND id=?",(rs,n)->new Cover(rs.getBytes(1),rs.getString(2)),id,revision);
        if(data.isEmpty())fail(HttpStatus.NOT_FOUND,"COVER_NOT_FOUND");return data.getFirst();
    }
    public void coverSave(UUID actor,UUID id,long expected,byte[] bytes,String type) {
        requireEditor(actor);writeLock();Row old=row(id,true);version(old.version(),expected);if(old.archived())fail(HttpStatus.CONFLICT,"GAME_ARCHIVED");
        var previous=jdbc.queryForList("SELECT revision_id FROM game_cover WHERE game_id=?",UUID.class,id);
        UUID revision=bytes==null?null:UUID.randomUUID();
        if(bytes==null)jdbc.update("DELETE FROM game_cover WHERE game_id=?",id);
        else {
            jdbc.update("INSERT INTO game_cover_revision(id,game_id,content,content_type) VALUES(?,?,?,?)",revision,id,bytes,type);
            jdbc.update("INSERT INTO game_cover(game_id,revision_id,content,content_type) VALUES(?,?,?,?) ON CONFLICT(game_id) DO UPDATE SET revision_id=EXCLUDED.revision_id,content=EXCLUDED.content,content_type=EXCLUDED.content_type",id,revision,bytes,type);
        }
        jdbc.update("UPDATE game SET version=version+1 WHERE id=?",id);
        var before=new LinkedHashMap<String,Object>();var after=new LinkedHashMap<String,Object>();
        before.put("cover",previous.isEmpty()?null:previous.getFirst());
        after.put("cover",revision);audit(actor,"GAME",id,"COVER_UPDATED",before,after);
    }
    private String visibleChanges(String entity,String changes) {
        if(!"COPY".equals(entity)) return changes;
        try {
            var value=json.readTree(changes);
            if(value instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
                object.remove(List.of("inventory_id","id","game_id"));
            return json.writeValueAsString(value);
        } catch(com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    public List<Audit> history(UUID actor,String entity,UUID id) {
        requireAdmin(actor);if(!List.of("GAME","COPY").contains(entity))fail(HttpStatus.BAD_REQUEST,"INVALID_ENTITY");
        return jdbc.query("SELECT a.*,u.first_name || ' ' || u.last_name actor_name FROM library_audit a JOIN app_user u ON u.id=a.actor_id WHERE entity_type=? AND entity_id=? ORDER BY a.created_at DESC,a.id",(rs,n)->new Audit(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),rs.getString("action"),visibleChanges(entity,rs.getString("changes")),rs.getTimestamp("created_at").toInstant()),entity,id);
    }
}
