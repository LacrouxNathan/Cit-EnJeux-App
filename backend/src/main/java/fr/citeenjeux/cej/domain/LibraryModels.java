package fr.citeenjeux.cej.domain;

import java.util.*;
import jakarta.validation.constraints.*;

public final class LibraryModels {
    private LibraryModels() { }
    public enum GameType { BASE_GAME, EXPANSION, STANDALONE_EXPANSION }
    public record GameInput(@NotBlank String name, @NotNull GameType type, UUID baseGameId,
        String publisher, String author, @Positive Integer publicationYear,
        @Positive Integer minPlayers, @Positive Integer maxPlayers, @Positive Integer durationMinutes,
        @PositiveOrZero Integer minimumAge, String description, String categories, String mechanics,
        @PositiveOrZero Long version, @Positive Long bggId) { }
    /** DTO commun : aucun historique ni renseignement sur les emprunteurs. */
    public record GameCard(UUID id, String name, GameType type, UUID baseGameId, String baseGameName,
        String publisher, String author, Integer publicationYear, Integer minPlayers, Integer maxPlayers,
        Integer durationMinutes, Integer minimumAge, String description, String categories, String mechanics,
        boolean hasCover, int activeCopies, int availableCopies, Long bggId, String sourceUrl) { }
    public record AdminGame(GameCard game, boolean archived, String archiveReason, long version, int totalCopies) { }
    public record EditorGame(GameCard game, boolean archived, String archiveReason, long version) { }
    public record EditorDetails(EditorGame game, List<CopyView> copies, List<EditorGame> extensions) { }
    public record Location(UUID id, UUID parentId, String name, String path, boolean leaf) { }
    public record LocationInput(@NotBlank String name, UUID parentId) { }
    public record CopyInput(@NotNull UUID locationId, @NotBlank String physicalCondition, String missingPieces,
        String comments, boolean blocked, String blockReason, @PositiveOrZero Long version) { }
    public record CopyView(UUID id, UUID locationId, String locationPath,
        String physicalCondition, String missingPieces, boolean blocked, String blockReason) { }
    public record AdminCopy(CopyView copy, String comments, boolean archived, String archiveReason, long version) { }
    public record GameDetails(GameCard game, List<CopyView> copies, List<GameCard> extensions) { }
    public record AdminDetails(AdminGame game, List<AdminCopy> copies, List<AdminGame> extensions) { }
    public record Page<T>(List<T> items, int page, int pageSize, long total) { }
    public record Revision(@NotNull @PositiveOrZero Long version, @NotBlank String reason, boolean confirmed) { }
    public record Audit(UUID id, UUID actorId, String actorName, String action, String changes, java.time.Instant createdAt) { }
}
