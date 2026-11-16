package fr.citeenjeux.cej.api;

import fr.citeenjeux.cej.application.MemberManagement;
import fr.citeenjeux.cej.application.UserInvitations;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class MemberController {
    private final MemberManagement members;
    public MemberController(MemberManagement members){this.members=members;}
    @GetMapping("/{id}") public UserInvitations.UserSummary detail(Principal actor,@PathVariable UUID id){return members.get(UUID.fromString(actor.getName()),id);}
    @PutMapping("/{id}") public UserInvitations.UserSummary update(Principal actor,@PathVariable UUID id,@Valid @RequestBody MemberManagement.Update data){return members.update(UUID.fromString(actor.getName()),id,data);}
    @GetMapping("/{id}/history") public List<MemberManagement.Audit> history(Principal actor,@PathVariable UUID id){return members.history(UUID.fromString(actor.getName()),id);}
}
