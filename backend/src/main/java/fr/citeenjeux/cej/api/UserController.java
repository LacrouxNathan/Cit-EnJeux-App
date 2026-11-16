package fr.citeenjeux.cej.api;

import fr.citeenjeux.cej.application.UserInvitations;
import fr.citeenjeux.cej.infrastructure.InvitationMail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
    public record CreateUser(@NotBlank @Email String email, @NotBlank String firstName,
                             @NotBlank String lastName, String nickname, boolean membershipCurrent) {
        public CreateUser { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
    }
    public record Activation(@NotBlank String token, @NotBlank @Size(min = 6) String password) {
        @Override public String toString() { return "Activation[redacted]"; }
    }
    public record InvitationResult(UUID userId, java.time.Instant expiresAt, String deliveryStatus) { }
    private final UserInvitations invitations;
    private final InvitationMail mail;
    public UserController(UserInvitations invitations, InvitationMail mail) { this.invitations = invitations; this.mail = mail; }

    @GetMapping("/api/admin/users")
    public List<UserInvitations.UserSummary> list(Principal principal, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="all") String status,
        @RequestParam(defaultValue="all") String role,@RequestParam(defaultValue="all") String membership) {
        return invitations.list(UUID.fromString(principal.getName()), page,search,status,role,membership);
    }

    @PostMapping("/api/admin/users")
    public ResponseEntity<InvitationResult> create(Principal principal, @Valid @RequestBody CreateUser data) {
        var issued = invitations.create(UUID.fromString(principal.getName()), new UserInvitations.NewUser(
            data.email(), data.firstName(), data.lastName(), data.nickname(), data.membershipCurrent()));
        return ResponseEntity.status(201).body(deliver(issued));
    }

    @PostMapping("/api/admin/users/{id}/invitation")
    public InvitationResult resend(Principal principal, @PathVariable UUID id) {
        return deliver(invitations.resend(UUID.fromString(principal.getName()), id));
    }

    @PostMapping("/api/invitations/activate")
    public ResponseEntity<Void> activate(@Valid @RequestBody Activation data) {
        invitations.activate(data.token(), data.password());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/password-reset/members")
    public List<UserInvitations.ResetMember> resetMembers(Principal principal,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page) {
        return invitations.resetMembers(UUID.fromString(principal.getName()),search,page);
    }

    @PostMapping("/api/admin/users/{id}/password-reset")
    public InvitationResult requestReset(Principal principal, @PathVariable UUID id) {
        var issued = invitations.requestPasswordReset(UUID.fromString(principal.getName()),id);
        return new InvitationResult(id,issued.expiresAt(),mail.sendReset(issued)?"SENT":"FAILED");
    }

    @PostMapping("/api/password-reset/confirm")
    public ResponseEntity<Void> reset(@Valid @RequestBody Activation data) {
        invitations.resetPassword(data.token(),data.password());
        return ResponseEntity.noContent().build();
    }

    private InvitationResult deliver(UserInvitations.Issued issued) {
        return new InvitationResult(issued.userId(), issued.expiresAt(), mail.send(issued) ? "SENT" : "FAILED");
    }
}
