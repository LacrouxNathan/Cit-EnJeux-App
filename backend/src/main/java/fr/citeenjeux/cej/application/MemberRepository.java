package fr.citeenjeux.cej.application;

import fr.citeenjeux.cej.domain.Member;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository {
    Optional<Member> findByEmail(String email);
    Optional<Member> findById(UUID id);
    boolean createInitialAdmin(Member member);
}
