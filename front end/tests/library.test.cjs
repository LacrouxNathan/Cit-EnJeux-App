const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../library-utils.js'), 'utf8');
const modulePromise = import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
test('DTO admin et membre sont interprétés sans inventer de données de stock', async () => {
  const { gameOf, copyOf, availability } = await modulePromise;
  const game = { id: 'A', activeCopies: 0, availableCopies: 0 };
  assert.equal(gameOf({ game, archived: true }), game); assert.equal(gameOf(game), game);
  assert.equal(copyOf({ copy: { id: 'B' }, comments: 'Privé' }).id, 'B');
  assert.equal(availability(game), 'Aucun exemplaire actif');
  assert.equal(availability({ activeCopies: 2, availableCopies: 0 }), 'Indisponible');
  assert.equal(availability({ activeCopies: 2, availableCopies: 1 }), '1 disponible sur 2');
});
test('les champs facultatifs restent absents, les zéros restent valides et la version est conservée', async () => {
  const { gamePayload } = await modulePromise;
  const payload = gamePayload(new Map(Object.entries({ name: 'Azul', type: 'BASE_GAME', baseGameId: 'ancien-parent', publicationYear: '', minPlayers: '2', maxPlayers: '4', durationMinutes: '', minimumAge: '0' })), 3);
  assert.equal(payload.baseGameId, null); assert.equal(payload.publicationYear, null); assert.equal(payload.minimumAge, 0); assert.equal(payload.minPlayers, 2); assert.equal(payload.version, 3);
});
test('une extension conserve son rattachement explicite', async () => {
  const { gamePayload } = await modulePromise;
  const data = new Map(Object.entries({ type: 'EXPANSION', baseGameId: 'base-id', publicationYear: '', minPlayers: '', maxPlayers: '', durationMinutes: '', minimumAge: '' }));
  assert.equal(gamePayload(data).baseGameId, 'base-id');
});
test('la référence BGG importée est conservée à la sauvegarde, sans l’inventer pour une fiche manuelle', async () => {
  const { gamePayload } = await modulePromise;
  const data = new Map(Object.entries({ type: 'BASE_GAME', publicationYear: '', minPlayers: '', maxPlayers: '', durationMinutes: '', minimumAge: '', bggId: '13' }));
  assert.equal(gamePayload(data).bggId, 13);
  data.delete('bggId'); assert.equal(gamePayload(data).bggId, null);
});
