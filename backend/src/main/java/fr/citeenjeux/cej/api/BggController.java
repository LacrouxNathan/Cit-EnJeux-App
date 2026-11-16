package fr.citeenjeux.cej.api;

import fr.citeenjeux.cej.application.BggImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/library/bgg")
public class BggController {
    public record ImportLink(@NotBlank String url) { }
    private final BggImportService imports;
    public BggController(BggImportService imports){this.imports=imports;}
    @PostMapping("/preview")
    public BggImportService.Preview preview(Principal actor,@Valid @RequestBody ImportLink request){return imports.preview(UUID.fromString(actor.getName()),request.url());}
}
