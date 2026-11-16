package fr.citeenjeux.cej.api;

import fr.citeenjeux.cej.application.LibraryService;
import static fr.citeenjeux.cej.domain.LibraryModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LibraryController {
    private final LibraryService library;
    public LibraryController(LibraryService library) {this.library=library;}
    private UUID actor(Principal principal) {return UUID.fromString(principal.getName());}
    private Object gameResponse(Principal user,AdminGame game) {
        boolean admin=user instanceof org.springframework.security.core.Authentication auth && auth.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));
        return admin?game:new EditorGame(game.game(),game.archived(),game.archiveReason(),game.version());
    }
    @GetMapping("/api/admin/library/base-games") public List<?> bases(Principal user) {return library.baseGames(actor(user)).stream().map(g->gameResponse(user,g)).toList();}
    @GetMapping("/api/library/games")
    public Page<?> list(Principal user,@RequestParam(defaultValue="") String search,@RequestParam(required=false) Integer players,
        @RequestParam(defaultValue="all") String availability,@RequestParam(defaultValue="name") String sort,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="false") boolean archived) {
        return library.list(actor(user),search,players,availability,sort,page,archived);
    }
    @GetMapping("/api/library/games/{id}") public Object detail(Principal user,@PathVariable UUID id) {return library.detail(actor(user),id);}
    @GetMapping("/api/admin/library/similar") public List<?> similar(Principal user,@RequestParam String name,@RequestParam(required=false) UUID exclude) {return library.similar(actor(user),name,exclude).stream().map(g->gameResponse(user,g)).toList();}
    @PostMapping("/api/admin/library/games") public ResponseEntity<?> create(Principal user,@Valid @RequestBody GameInput data) {return ResponseEntity.status(201).body(gameResponse(user,library.save(actor(user),null,data)));}
    @PutMapping("/api/admin/library/games/{id}") public Object update(Principal user,@PathVariable UUID id,@Valid @RequestBody GameInput data) {return gameResponse(user,library.save(actor(user),id,data));}
    @PostMapping("/api/admin/library/games/{id}/archive") public void archive(Principal user,@PathVariable UUID id,@Valid @RequestBody Revision data) {library.archiveGame(actor(user),id,data,false);}
    @PostMapping("/api/admin/library/games/{id}/restore") public void restore(Principal user,@PathVariable UUID id,@Valid @RequestBody Revision data) {library.archiveGame(actor(user),id,data,true);}
    @GetMapping("/api/library/locations") public List<Location> locations(Principal user) {return library.locations(actor(user));}
    @PostMapping("/api/admin/library/locations") public ResponseEntity<Location> location(Principal user,@Valid @RequestBody LocationInput data) {return ResponseEntity.status(201).body(library.createLocation(actor(user),data));}
    public record Copies(@NotEmpty List<@Valid CopyInput> copies) { }
    @PostMapping("/api/admin/library/games/{id}/copies") public ResponseEntity<List<AdminCopy>> copies(Principal user,@PathVariable UUID id,@Valid @RequestBody Copies data) {return ResponseEntity.status(201).body(library.createCopies(actor(user),id,data.copies()));}
    @PutMapping("/api/admin/library/copies/{id}") public AdminCopy copy(Principal user,@PathVariable UUID id,@Valid @RequestBody CopyInput data) {return library.updateCopy(actor(user),id,data);}
    @PostMapping("/api/admin/library/copies/{id}/archive") public void archiveCopy(Principal user,@PathVariable UUID id,@Valid @RequestBody Revision data) {library.archiveCopy(actor(user),id,data,false);}
    @PostMapping("/api/admin/library/copies/{id}/restore") public void restoreCopy(Principal user,@PathVariable UUID id,@Valid @RequestBody Revision data) {library.archiveCopy(actor(user),id,data,true);}
    @GetMapping("/api/admin/library/{entity}/{id}/history") public List<Audit> history(Principal user,@PathVariable String entity,@PathVariable UUID id) {return library.history(actor(user),entity.toUpperCase(Locale.ROOT),id);}
    @GetMapping("/api/library/games/{id}/cover") public ResponseEntity<byte[]> cover(Principal user,@PathVariable UUID id) {
        var cover=library.cover(actor(user),id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(cover.type())).body(cover.content());
    }
    @GetMapping("/api/admin/library/games/{id}/covers/{revision}") public ResponseEntity<byte[]> oldCover(Principal user,@PathVariable UUID id,@PathVariable UUID revision) {
        var cover=library.oldCover(actor(user),id,revision);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(cover.type())).body(cover.content());
    }
    @PutMapping("/api/admin/library/games/{id}/cover") public void upload(Principal user,@PathVariable UUID id,@RequestParam long version,@RequestParam MultipartFile file) throws java.io.IOException {
        byte[] bytes=file.getBytes();
        String type=fr.citeenjeux.cej.domain.CoverImage.validate(bytes);
        library.coverSave(actor(user),id,version,bytes,type);
    }
    @DeleteMapping("/api/admin/library/games/{id}/cover") public void removeCover(Principal user,@PathVariable UUID id,@RequestParam long version) {library.coverSave(actor(user),id,version,null,null);}
}
