package fr.citeenjeux.cej.domain;

import java.util.UUID;

/** Modèle interne : ne jamais sérialiser ce record dans une réponse API. */
public record Member(UUID id, String email, String passwordHash, String firstName,
                     String lastName, String nickname, boolean active,
                     boolean membershipCurrent, boolean administrator, long securityVersion, boolean boardMember) {
    public Member(UUID id,String email,String passwordHash,String firstName,String lastName,String nickname,
                  boolean active,boolean membershipCurrent,boolean administrator,long securityVersion) {
        this(id,email,passwordHash,firstName,lastName,nickname,active,membershipCurrent,administrator,securityVersion,false);
    }
    public Member(UUID id,String email,String passwordHash,String firstName,String lastName,String nickname,
                  boolean active,boolean membershipCurrent,boolean administrator) {
        this(id,email,passwordHash,firstName,lastName,nickname,active,membershipCurrent,administrator,0);
    }
    public String role() { return administrator ? "ADMIN" : boardMember ? "BOARD_MEMBER" : "MEMBER"; }
    public boolean canManageGames() { return active && passwordHash != null && (administrator || boardMember); }
    public String displayName() {
        return nickname != null && !nickname.isBlank() ? nickname : firstName + " " + lastName;
    }

    @Override public String toString() { return "Member[id=" + id + "]"; }
}
