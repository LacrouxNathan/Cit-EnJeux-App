export const typeNames = { BASE_GAME: 'Jeu de base', EXPANSION: 'Extension', STANDALONE_EXPANSION: 'Extension autonome' };
export function gameOf(value) { return value.game || value; }
export function copyOf(value) { return value.copy || value; }
export function availability(game) {
  if (!game.activeCopies) return 'Aucun exemplaire actif';
  return game.availableCopies ? `${game.availableCopies} disponible${game.availableCopies > 1 ? 's' : ''} sur ${game.activeCopies}` : 'Indisponible';
}
export function gamePayload(form, version) {
  const data = Object.fromEntries(form);
  const result = { ...data, version: version ?? null };
  for (const key of ['publicationYear', 'minPlayers', 'maxPlayers', 'durationMinutes', 'minimumAge', 'bggId']) result[key] = data[key] == null || data[key] === '' ? null : Number(data[key]);
  result.baseGameId = data.type === 'BASE_GAME' ? null : data.baseGameId || null;
  return result;
}
export const messages = {
  BGG_INVALID_URL: 'Collez le lien HTTPS d’une fiche de jeu BoardGameGeek, par exemple https://boardgamegeek.com/boardgame/13/catan.',
  BGG_NOT_CONFIGURED: 'L’import BGG nécessite un jeton d’application approuvée. Configurez BGG_API_TOKEN dans .env puis redémarrez l’application.',
  BGG_AUTH_FAILED: 'BGG refuse l’accès. Vérifiez le jeton BGG et l’approbation de l’application.',
  BGG_UNAVAILABLE: 'BGG est momentanément inaccessible. Réessayez plus tard ou saisissez la fiche manuellement.',
  BGG_PENDING: 'BGG prépare les données. Patientez quelques secondes puis réessayez.',
  BGG_RATE_LIMIT: 'Patientez au moins cinq secondes avant une nouvelle récupération BGG.',
  BGG_GAME_NOT_FOUND: 'Ce lien ne correspond pas à une fiche de jeu BGG disponible.',
  BGG_INVALID_RESPONSE: 'BGG a renvoyé des données inattendues. La saisie manuelle reste disponible.',
  STALE_VERSION: 'Cette fiche a été modifiée ailleurs. Fermez-la puis rouvrez-la avant de réessayer.',
  INVALID_PLAYER_RANGE: 'Le nombre minimum de joueurs ne doit pas dépasser le maximum.',
  GAME_ARCHIVED: 'Cette fiche est archivée. Restaurez-la avant de la modifier.',
  COPY_ARCHIVED: 'Cet exemplaire est archivé. Restaurez-le avant de le modifier.',
  ACTIVE_COPIES_PREVENT_ARCHIVE: 'Tous les exemplaires actifs du jeu et de ses extensions doivent être archivés individuellement par un administrateur avant de retirer la fiche.',
  BASE_GAME_INACTIVE: 'Choisissez un jeu de base compatible et actif pour cette extension.',
  BASE_CANNOT_HAVE_PARENT: 'Un jeu de base ne peut pas être rattaché à un autre jeu.',
  INVALID_BASE_GAME: 'Sélectionnez un jeu de base compatible.',
  GAME_HAS_EXTENSIONS: 'Ce jeu possède des extensions : il ne peut pas devenir lui-même une extension.',
  INVENTORY_IDENTIFIER_RESERVED: 'La création n’a pas pu être terminée. Aucun exemplaire du formulaire n’a été créé. Veuillez réessayer.',
  BLOCK_REASON_REQUIRED: 'Indiquez le motif du blocage.',
  LOCATION_MUST_BE_ACTIVE_LEAF: 'Choisissez un emplacement actif sans sous-emplacements.',
  LOCATION_CONTAINS_COPIES: 'Cet emplacement contient des exemplaires : il ne peut pas recevoir de sous-emplacement.',
  INVALID_LOCATION_PARENT: 'Cet emplacement parent n’est plus disponible.',
  INVALID_COVER: 'Choisissez une image PNG ou JPEG valide de 16 millions de pixels maximum.',
  COVER_TOO_LARGE: 'La couverture doit peser au maximum 5 Mo.',
  INVALID_REQUEST: 'Vérifiez les champs obligatoires, les nombres et le format du fichier.',
  CASCADE_CONFIRMATION_REQUIRED: 'Confirmez l’archivage du jeu et de toutes ses extensions.',
  INVALID_ARCHIVE_STATE: 'L’état de cette fiche a changé. Actualisez-la.',
};
