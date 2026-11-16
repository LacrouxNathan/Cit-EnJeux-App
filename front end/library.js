import { gameOf, copyOf, availability, typeNames, gamePayload, messages } from './library-utils.js';
try { const theme = localStorage.getItem('cej-theme'); if (['light', 'dark'].includes(theme)) document.documentElement.dataset.theme = theme; } catch { /* Système. */ }
const $ = selector => document.querySelector(selector);
const status = $('#library-status');
const mobileFilters = window.matchMedia('(max-width: 800px)');
$('#filter-panel').open = !mobileFilters.matches;
mobileFilters.addEventListener('change', event => { $('#filter-panel').open = !event.matches; });
let canManageGames = false;
let administrator = false, page = 0, total = 0, loading = false, detail = null, editingGame = null, editingCopy = null, locations = [], reasonAction;
let view = 'grid';
let importedCover = null, importedCoverUrl = null, importGeneration = 0;
try { view = localStorage.getItem('cej-library-view') === 'list' ? 'list' : 'grid'; } catch { /* Facultatif. */ }
const filters = { search: '', players: '', availability: 'all', sort: 'name', archived: false };
function node(tag, text, className) { const result = document.createElement(tag); if (text !== undefined) result.textContent = text; if (className) result.className = className; return result; }
function button(text, action, className = 'secondary-button') {
  const result = node('button', text, className); result.type = 'button';
  result.addEventListener('click', async () => { if (result.disabled) return; result.disabled = true; try { await action(); } catch (error) { ( $('#game-dialog').open ? $('#detail-status') : status).textContent = error.message; } finally { result.disabled = false; } });
  return result;
}
async function api(path, method = 'GET', data) {
  const options = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
  if (method !== 'GET') {
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error('Impossible de préparer la requête. Veuillez réessayer.');
    const csrf = await response.json(); options.headers[csrf.headerName] = csrf.token;
    if (data instanceof FormData) options.body = data;
    else if (data !== undefined) { options.body = JSON.stringify(data); options.headers['Content-Type'] = 'application/json'; }
  }
  let response;
  try { response = await fetch(path, options); } catch { throw new Error('Le service est inaccessible. Veuillez réessayer.'); }
  if (response.status === 401) { window.location.replace('/'); throw new Error('Votre session a expiré.'); }
  if (response.status === 403) throw new Error('Vous ne disposez pas des droits nécessaires.');
  if (response.status === 404) throw new Error('Cette fiche n’est pas accessible ou n’existe plus.');
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new Error(messages[result?.code] || 'L’opération a échoué. Veuillez réessayer.');
  return result;
}
function openDialog(id) { const dialog = $(id); if (!dialog.open) dialog.showModal(); }
for (const close of document.querySelectorAll('[data-close]')) close.addEventListener('click', () => $(`#${close.dataset.close}`).close());
function cover(game) {
  if (!game.hasCover) return node('div', 'Cité en jeux', 'cover-placeholder');
  const image = document.createElement('img'); image.alt = `Couverture de ${game.name}`; image.loading = 'lazy'; image.className = 'game-cover';
  image.src = `/api/library/games/${game.id}/cover?v=${Date.now()}`;
  image.addEventListener('error', () => image.replaceWith(node('div', 'Couverture indisponible', 'cover-placeholder')), { once: true }); return image;
}
function applyView() {
  $('#games').className = view === 'list' ? 'game-grid compact-list' : 'game-grid';
  $('#grid-view').setAttribute('aria-pressed', String(view === 'grid')); $('#list-view').setAttribute('aria-pressed', String(view === 'list'));
}
for (const mode of ['grid', 'list']) $(`#${mode}-view`).addEventListener('click', () => { view = mode; applyView(); try { localStorage.setItem('cej-library-view', mode); } catch { /* Facultatif. */ } });
async function listGames() {
  if (loading) return; loading = true; $('#previous-games').disabled = $('#next-games').disabled = true;
  status.textContent = 'Chargement des jeux…';
  try {
    const params = new URLSearchParams({ ...filters, page }); if (!filters.players) params.delete('players');
    const result = await api(`/api/library/games?${params}`); total = result.total;
    $('#results-count').textContent = `${total} fiche${total > 1 ? 's' : ''}`;
    $('#games-page').textContent = `Page ${page + 1} sur ${Math.max(1, Math.ceil(total / 20))}`;
    const container = $('#games'); container.replaceChildren();
    if (!result.items.length) container.append(node('p', canManageGames ? 'Aucune fiche. Ajoutez votre premier jeu ou modifiez vos filtres.' : 'Aucun jeu ne correspond à la recherche. Seuls les jeux possédant un exemplaire actif sont visibles.'));
    for (const item of result.items) {
      const game = gameOf(item); const article = node('article', undefined, 'game-card'); article.append(cover(game));
      const body = node('div', undefined, 'game-card-body'); body.append(node('p', typeNames[game.type] + (item.archived ? ' · Archivé' : ''), 'game-type'));
      const title = node('h2'); title.append(button(game.name, () => openGame(game.id), 'game-title-button')); body.append(title);
      if (game.baseGameId) body.append(node('p', `Extension de ${game.baseGameName}`, 'game-meta'));
      body.append(node('p', [game.minPlayers != null && game.maxPlayers != null ? `${game.minPlayers}–${game.maxPlayers} joueurs` : 'Joueurs non renseignés', game.durationMinutes == null ? null : `${game.durationMinutes} min`, game.minimumAge == null ? null : `${game.minimumAge} ans +`].filter(Boolean).join(' · '), 'game-meta'));
      body.append(node('p', availability(game), game.availableCopies ? 'availability available' : 'availability'));
      article.append(body); container.append(article);
    }
    status.textContent = '';
  } catch (error) { status.textContent = error.message; }
  finally { loading = false; $('#previous-games').disabled = page === 0; $('#next-games').disabled = (page + 1) * 20 >= total; }
}
$('#filters').addEventListener('submit', event => {
  event.preventDefault(); if (loading) return;
  Object.assign(filters, Object.fromEntries(new FormData(event.currentTarget))); filters.archived = $('#show-archived').checked; page = 0; listGames();
});
$('#reset-filters').addEventListener('click', () => { if (loading) return; $('#filters').reset(); Object.assign(filters, { search: '', players: '', availability: 'all', sort: 'name', archived: false }); page = 0; listGames(); });
$('#previous-games').addEventListener('click', () => { if (!loading && page > 0) { page--; listGames(); } });
$('#next-games').addEventListener('click', () => { if (!loading && (page + 1) * 20 < total) { page++; listGames(); } });
async function openGame(id) {
  const result = await api(`/api/library/games/${id}`); detail = result;
  $('#detail-status').textContent = ''; const game = gameOf(result.game), adminGame = canManageGames ? result.game : null;
  $('#detail-title').textContent = game.name;
  const content = $('#game-detail'); content.replaceChildren();
  const intro = node('div', undefined, 'game-intro'); intro.append(cover(game)); const text = node('div');
  text.append(node('p', typeNames[game.type] + (adminGame?.archived ? ' · Archivé' : ''), 'game-type'));
  if (game.baseGameId) text.append(button(`Jeu de base : ${game.baseGameName}`, () => openGame(game.baseGameId)));
  const meta = node('dl', undefined, 'game-facts');
  for (const [label, value] of [['Éditeur', game.publisher], ['Auteur', game.author], ['Année', game.publicationYear], ['Joueurs', `${game.minPlayers ?? '?'}–${game.maxPlayers ?? '?'}`], ['Durée', game.durationMinutes == null ? null : `${game.durationMinutes} min`], ['Âge minimum', game.minimumAge == null ? null : `${game.minimumAge} ans`], ['Catégories', game.categories], ['Mécaniques', game.mechanics]]) if (value != null && value !== '') meta.append(node('dt', label), node('dd', String(value)));
  text.append(meta); text.append(node('p', game.description || 'Aucune description.', 'game-description')); intro.append(text); content.append(intro);
  if (game.sourceUrl) {
    const source = node('a', 'Source : BoardGameGeek', 'bgg-credit'); source.href = game.sourceUrl; source.target = '_blank'; source.rel = 'noopener noreferrer';
    const logo = document.createElement('img'); logo.src = '/powered-by-bgg.png'; logo.alt = 'Powered by BGG'; source.append(logo); content.append(source);
  }
  if (canManageGames) {
    const actions = node('div', undefined, 'action-row');
    if (!adminGame.archived) {
      actions.append(button('Modifier la fiche', () => editGame(adminGame)));
      if (administrator) actions.append(button('Ajouter des exemplaires', () => editCopies(null)));
      actions.append(button('Archiver', () => askReason('Archiver le jeu', 'Le jeu et toutes ses extensions seront archivés. Tous leurs exemplaires doivent déjà être archivés. Aucun exemplaire ne sera archivé automatiquement.', async reason => { await api(`/api/admin/library/games/${game.id}/archive`, 'POST', { version: adminGame.version, reason, confirmed: true }); })));
    } else actions.append(button('Restaurer cette fiche', () => askReason('Restaurer la fiche', 'La restauration ne restaure aucune extension ni aucun exemplaire.', async reason => { await api(`/api/admin/library/games/${game.id}/restore`, 'POST', { version: adminGame.version, reason }); })));
    if (administrator) actions.append(button('Historique de la fiche', () => showHistory('game', game.id))); content.append(actions);
    if (adminGame.archiveReason) content.append(node('p', `Motif d’archivage : ${adminGame.archiveReason}`));
    if (!adminGame.archived) {
      const form = document.createElement('form'); form.className = 'cover-form';
      const label = node('label', 'Couverture (PNG ou JPEG, 5 Mo maximum)'); const file = document.createElement('input'); file.type = 'file'; file.accept = 'image/png,image/jpeg'; file.required = true; file.id = 'cover-file'; label.htmlFor = file.id;
      const send = node('button', 'Enregistrer la couverture', 'secondary-button'); send.type = 'submit'; form.append(label, file, send);
      form.addEventListener('submit', async event => { event.preventDefault(); if (send.disabled) return; const chosen = file.files[0]; if (!chosen) return; if (chosen.size > 5 * 1024 * 1024) { $('#detail-status').textContent = messages.COVER_TOO_LARGE; return; } send.disabled = true;
        try { const data = new FormData(); data.append('file', chosen); await api(`/api/admin/library/games/${game.id}/cover?version=${adminGame.version}`, 'PUT', data); await openGame(game.id); await listGames(); } catch (error) { $('#detail-status').textContent = error.message; } finally { send.disabled = false; } });
      if (game.hasCover) form.append(button('Retirer la couverture', () => askReason('Retirer la couverture', 'La couverture ne sera plus affichée. Sa version précédente restera dans l’historique administratif.', async () => { await api(`/api/admin/library/games/${game.id}/cover?version=${adminGame.version}`, 'DELETE'); })));
      content.append(form);
    }
  }
  if (result.extensions.length) {
    const section = node('section'); section.append(node('h3', 'Extensions de ce jeu'));
    for (const item of result.extensions) section.append(button(gameOf(item).name + (item.archived ? ' · Archivée' : ''), () => openGame(gameOf(item).id)));
    content.append(section);
  }
  content.append(node('h3', 'Les exemplaires'));
  if (!result.copies.length) content.append(node('p', 'Aucun exemplaire actif enregistré. Cette fiche reste réservée aux administrateurs et au CA tant qu’elle n’a pas d’exemplaire actif.'));
  for (const [index, item] of result.copies.entries()) {
    const copy = copyOf(item); const article = node('article', undefined, 'copy-card');
    article.append(node('h4', `Exemplaire ${index + 1}`), node('p', item.archived ? 'Archivé' : copy.blocked ? `Bloqué : ${copy.blockReason}` : 'Disponible', 'availability'));
    article.append(node('p', `Emplacement : ${copy.locationPath}`), node('p', `État : ${copy.physicalCondition}`));
    if (copy.missingPieces) article.append(node('p', `Pièces manquantes : ${copy.missingPieces}`));
    if (administrator) {
      if (item.comments) article.append(node('p', `Commentaire administratif : ${item.comments}`));
      if (item.archiveReason) article.append(node('p', `Motif d’archivage : ${item.archiveReason}`));
      const actions = node('div', undefined, 'action-row');
      if (!item.archived) actions.append(button('Modifier cet exemplaire', () => editCopies(item)), button('Archiver cet exemplaire', () => askReason('Archiver l’exemplaire', `Seul l’exemplaire ${index + 1}, situé dans ${copy.locationPath}, sera archivé.`, async reason => { await api(`/api/admin/library/copies/${copy.id}/archive`, 'POST', { version: item.version, reason }); })));
      else actions.append(button('Restaurer cet exemplaire', () => askReason('Restaurer l’exemplaire', 'L’exemplaire restera bloqué après sa restauration. Son état et son autorisation d’emprunt devront être revus individuellement.', async reason => { await api(`/api/admin/library/copies/${copy.id}/restore`, 'POST', { version: item.version, reason }); })));
      actions.append(button('Historique', () => showHistory('copy', copy.id))); article.append(actions);
    }
    content.append(article);
  }
  openDialog('#game-dialog'); $('#detail-title').focus();
}
function askReason(title, explanation, action) {
  $('#reason-title').textContent = title; $('#reason-explanation').textContent = explanation;
  $('#reason-form').reset(); $('#reason-status').textContent = ''; reasonAction = action; openDialog('#reason-dialog');
}
$('#reason-form').addEventListener('submit', async event => {
  event.preventDefault(); const submit = event.currentTarget.querySelector('[type=submit]'); if (submit.disabled) return;
  const reason = $('#operation-reason').value.trim(); if (!reason) return;
  submit.disabled = true;
  try { await reasonAction(reason); $('#reason-dialog').close(); await openGame(gameOf(detail.game).id); await listGames(); } catch (error) { $('#reason-status').textContent = error.message; } finally { submit.disabled = false; }
});
const fieldLabels = { name: 'Nom du jeu', type: 'Type de fiche', baseGameId: 'Jeu de base', publisher: 'Éditeur', author: 'Auteur', publicationYear: 'Année de publication', minPlayers: 'Joueurs minimum', maxPlayers: 'Joueurs maximum', durationMinutes: 'Durée (minutes)', minimumAge: 'Âge minimum', description: 'Description', categories: 'Catégories', mechanics: 'Mécaniques' };
function field(parent, name, label, options = {}) {
  const wrapper = node('div', undefined, 'field'); const id = `field-${name}-${document.querySelectorAll('input,select,textarea').length}`;
  const caption = node('label', label); caption.htmlFor = id;
  const input = document.createElement(options.options ? 'select' : options.multiline ? 'textarea' : 'input'); input.id = id; input.name = name; input.required = Boolean(options.required);
  if (options.options) for (const [value, text] of options.options) { const option = node('option', text); option.value = value; input.append(option); }
  else if (options.number) { input.type = 'number'; input.min = options.zero ? '0' : '1'; input.step = '1'; }
  else if (!options.multiline) input.type = 'text';
  if (options.value != null) input.value = options.value;
  wrapper.append(caption, input); parent.append(wrapper); return input;
}
async function editGame(value) {
  importGeneration++; importedCover = null;
  $('#save-game').disabled = false;
  if (importedCoverUrl) URL.revokeObjectURL(importedCoverUrl);
  importedCoverUrl = null;
  $('#bgg-import').hidden = Boolean(value); $('#bgg-link').value = ''; $('#bgg-link').disabled = false; $('#bgg-status').textContent = ''; $('#bgg-cover-choice').hidden = true;
  $('#bgg-cover-preview').removeAttribute('src'); $('#bgg-load').disabled = false;
  editingGame = value; const data = value?.game || { type: 'BASE_GAME' }; const bases = await api('/api/admin/library/base-games');
  $('#edit-title').textContent = value ? 'Modifier le jeu' : 'Ajouter un jeu'; $('#game-fields').replaceChildren(); $('#save-status').textContent = ''; $('#similar-warning').textContent = '';
  for (const [name, label] of Object.entries(fieldLabels)) {
    let options;
    if (name === 'type') options = Object.entries(typeNames);
    if (name === 'baseGameId') options = [['', 'Sélectionner un jeu de base'], ...bases.filter(base => base.game.id !== data.id).map(base => [base.game.id, base.game.name + (base.archived ? ' (archivé)' : '')])];
    field($('#game-fields'), name, label, { value: data[name], required: ['name', 'type'].includes(name), options, multiline: name === 'description', number: ['publicationYear', 'minPlayers', 'maxPlayers', 'durationMinutes', 'minimumAge'].includes(name), zero: name === 'minimumAge' });
  }
  const form = $('#game-form');
  const sourceId = document.createElement('input'); sourceId.type = 'hidden'; sourceId.name = 'bggId'; sourceId.value = data.bggId ?? ''; $('#game-fields').append(sourceId);
  const refreshParent = () => { form.elements.baseGameId.closest('.field').hidden = form.elements.type.value === 'BASE_GAME'; form.elements.baseGameId.required = form.elements.type.value !== 'BASE_GAME'; };
  form.elements.type.addEventListener('change', refreshParent); refreshParent();
  form.elements.name.addEventListener('blur', async () => {
    const name = form.elements.name.value.trim(); if (!name) return;
    try { const params = new URLSearchParams({ name }); if (value) params.set('exclude', data.id); const matches = await api(`/api/admin/library/similar?${params}`); $('#similar-warning').textContent = matches.length ? `Fiches ressemblantes : ${matches.map(item => item.game.name + (item.archived ? ' (archivée)' : '')).join(', ')}. La création reste autorisée.` : ''; } catch { $('#similar-warning').textContent = 'La vérification des noms proches est momentanément indisponible.'; }
  });
  openDialog('#edit-dialog');
}
$('#new-game').addEventListener('click', () => editGame(null).catch(error => { status.textContent = error.message; }));
$('#bgg-load').addEventListener('click', async () => {
  const load = $('#bgg-load'); if (load.disabled || $('#save-game').disabled) return;
  const generation = importGeneration;
  const form = $('#game-form');
  const url = $('#bgg-link').value.trim();
  if (!url) { $('#bgg-status').textContent = 'Collez le lien de la fiche BGG.'; return; }
  if (form.elements.name.value.trim() && !window.confirm('Remplacer les champs déjà saisis par les informations BGG ? Vous pourrez les corriger avant d’enregistrer.')) return;
  load.disabled = true; $('#bgg-link').disabled = true; $('#save-game').disabled = true; $('#bgg-status').textContent = 'Récupération des données BGG…';
  try {
    const preview = await api('/api/admin/library/bgg/preview', 'POST', { url });
    if (generation !== importGeneration || !$('#edit-dialog').open) return;
    for (const key of [...Object.keys(fieldLabels), 'bggId']) if (form.elements[key] && key !== 'baseGameId') form.elements[key].value = preview[key] ?? '';
    form.elements.baseGameId.value = ''; form.elements.type.dispatchEvent(new Event('change'));
    if (importedCoverUrl) URL.revokeObjectURL(importedCoverUrl);
    importedCover = null; importedCoverUrl = null; $('#bgg-cover-choice').hidden = true;
    if (preview.cover) {
      const bytes = Uint8Array.from(atob(preview.cover.base64), char => char.charCodeAt(0));
      importedCover = new Blob([bytes], { type: preview.cover.contentType }); importedCoverUrl = URL.createObjectURL(importedCover);
      $('#bgg-cover-preview').src = importedCoverUrl; $('#bgg-use-cover').checked = true; $('#bgg-cover-choice').hidden = false;
    }
    $('#bgg-status').textContent = ['Informations récupérées. Vérifiez les champs puis enregistrez la fiche.', ...preview.warnings].join(' ');
    form.elements.name.dispatchEvent(new Event('blur'));
  } catch (error) { if (generation === importGeneration) $('#bgg-status').textContent = error.message; }
  finally { if (generation === importGeneration) { load.disabled = false; $('#bgg-link').disabled = false; $('#save-game').disabled = false; } }
});
$('#game-form').addEventListener('submit', async event => {
  event.preventDefault(); const submit = $('#save-game'); if (submit.disabled) return;
  const payload = gamePayload(new FormData(event.currentTarget), editingGame?.version);
  if (payload.minPlayers != null && payload.maxPlayers != null && payload.minPlayers > payload.maxPlayers) { $('#save-status').textContent = messages.INVALID_PLAYER_RANGE; return; }
  submit.disabled = true;
  try {
    const similarity = new URLSearchParams({ name: payload.name });
    if (editingGame) similarity.set('exclude', editingGame.game.id);
    const matches = await api(`/api/admin/library/similar?${similarity}`);
    if (matches.length && !window.confirm(`Des fiches portent un nom ressemblant : ${matches.map(item => item.game.name + (item.archived ? ' (archivée)' : '')).join(', ')}. Enregistrer quand même ?`)) return;
    const result = await api('/api/admin/library/games' + (editingGame ? `/${editingGame.game.id}` : ''), editingGame ? 'PUT' : 'POST', payload);
    let coverFailure = false;
    if (!editingGame && importedCover && $('#bgg-use-cover').checked) {
      const file = new FormData(); file.append('file', importedCover, importedCover.type === 'image/png' ? 'bgg-cover.png' : 'bgg-cover.jpg');
      try { await api(`/api/admin/library/games/${result.game.id}/cover?version=${result.version}`, 'PUT', file); }
      catch { coverFailure = true; }
    }
    $('#edit-dialog').close(); await openGame(result.game.id); await listGames();
    if (coverFailure) $('#detail-status').textContent = 'La fiche a été créée, mais sa couverture n’a pas pu être enregistrée. Vous pouvez la téléverser dans cette fiche.';
  } catch (error) { $('#save-status').textContent = error.message; } finally { submit.disabled = false; }
});
function copyRow(value) {
  const data = value ? { ...value.copy, comments: value.comments, version: value.version } : { physicalCondition: 'Bon état' };
  const wrapper = node('fieldset', undefined, 'copy-fields'); wrapper.append(node('legend', value ? 'Modifier l’exemplaire' : 'Nouvel exemplaire'));
  field(wrapper, 'locationId', 'Emplacement', { value: data.locationId, required: true, options: [['', 'Choisir une feuille active'], ...locations.filter(l => l.leaf).map(l => [l.id, `${l.path} · ${l.id.slice(0, 8)}`])] });
  field(wrapper, 'physicalCondition', 'État physique', { value: data.physicalCondition, required: true });
  field(wrapper, 'missingPieces', 'Pièces manquantes (facultatif)', { value: data.missingPieces, multiline: true });
  field(wrapper, 'comments', 'Commentaire administratif (facultatif)', { value: data.comments, multiline: true });
  const checkboxLabel = node('label', undefined, 'checkbox-field'); const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.name = 'blocked'; checkbox.checked = Boolean(data.blocked); checkboxLabel.append(checkbox, document.createTextNode('Bloquer les emprunts')); wrapper.append(checkboxLabel);
  const reason = field(wrapper, 'blockReason', 'Motif du blocage', { value: data.blockReason });
  const refresh = () => { reason.required = checkbox.checked; reason.closest('.field').hidden = !checkbox.checked; }; checkbox.addEventListener('change', refresh); refresh();
  if (!editingCopy) wrapper.append(button('Retirer cette ligne', () => { if ($('#copy-fields').children.length > 1) wrapper.remove(); }));
  $('#copy-fields').append(wrapper);
}
async function editCopies(value) {
  editingCopy = value; locations = await api('/api/library/locations');
  $('#copy-title').textContent = value ? 'Modifier cet exemplaire' : 'Ajouter des exemplaires';
  $('#copy-help').textContent = value ? 'Cette sauvegarde ne modifie que cet exemplaire.' : 'Toutes les lignes seront créées ensemble, ou aucune en cas d’erreur. Créez d’abord un emplacement si la liste est vide.';
  $('#copy-status').textContent = ''; $('#copy-fields').replaceChildren(); $('#add-copy-row').hidden = Boolean(value); copyRow(value); openDialog('#copy-dialog');
}
$('#add-copy-row').addEventListener('click', () => copyRow(null));
$('#copy-form').addEventListener('submit', async event => {
  event.preventDefault(); const submit = $('#save-copies'); if (submit.disabled) return;
  const rows = [...$('#copy-fields').children].map(wrapper => {
    const value = name => wrapper.querySelector(`[name="${name}"]`).value;
    return { locationId: value('locationId'), physicalCondition: value('physicalCondition'), missingPieces: value('missingPieces'), comments: value('comments'), blocked: wrapper.querySelector('[name=blocked]').checked, blockReason: value('blockReason'), version: editingCopy?.version ?? null };
  });
  submit.disabled = true;
  try { if (editingCopy) await api(`/api/admin/library/copies/${editingCopy.copy.id}`, 'PUT', rows[0]); else await api(`/api/admin/library/games/${gameOf(detail.game).id}/copies`, 'POST', { copies: rows }); $('#copy-dialog').close(); await openGame(gameOf(detail.game).id); await listGames(); } catch (error) { $('#copy-status').textContent = error.message; } finally { submit.disabled = false; }
});
$('#new-location').addEventListener('click', async () => {
  try { locations = await api('/api/library/locations'); const parent = $('#location-parent'); parent.replaceChildren(); for (const option of [['', 'À la racine'], ...locations.map(l => [l.id, `${l.path} · ${l.id.slice(0, 8)}`])]) { const element = node('option', option[1]); element.value = option[0]; parent.append(element); } $('#location-name').value = ''; $('#location-status').textContent = ''; openDialog('#location-dialog'); } catch (error) { status.textContent = error.message; }
});
$('#location-form').addEventListener('submit', async event => {
  event.preventDefault(); const submit = event.currentTarget.querySelector('[type=submit]'); if (submit.disabled) return; submit.disabled = true;
  try { await api('/api/admin/library/locations', 'POST', { name: $('#location-name').value.trim(), parentId: $('#location-parent').value || null }); $('#location-dialog').close(); status.textContent = 'Emplacement créé. Il peut maintenant recevoir des exemplaires s’il ne possède pas de sous-emplacement.'; } catch (error) { $('#location-status').textContent = error.message; } finally { submit.disabled = false; }
});
async function showHistory(entity, id) {
  const events = await api(`/api/admin/library/${entity}/${id}/history`); const content = $('#history-content'); content.replaceChildren();
  if (!events.length) content.append(node('p', 'Aucun événement enregistré.'));
  for (const event of events) {
    const article = node('article', undefined, 'audit-event'); article.append(node('h3', `${new Date(event.createdAt).toLocaleString('fr-FR', { timeZone: 'Europe/Paris' })} · ${event.actorName}`), node('p', event.action));
    const changes = JSON.parse(event.changes); const list = node('dl', undefined, 'game-facts');
    for (const [key, value] of Object.entries(changes)) {
      const label = fieldLabels[key] || ({ archived: 'Archivé', archiveReason: 'Motif d’archivage', restorationReason: 'Motif de restauration', location_id: 'Emplacement', physical_condition: 'État', missing_pieces: 'Pièces manquantes', blocked: 'Blocage', block_reason: 'Motif du blocage', comments: 'Commentaire', cover: 'Couverture' })[key] || key;
      list.append(node('dt', label)); const description = node('dd', `${value.before ?? '—'} → ${value.after ?? '—'}`);
      if (key === 'cover' && entity === 'game') for (const [name, revision] of [['Ancienne', value.before], ['Nouvelle', value.after]]) if (revision) { const a = node('a', ` ${name} couverture`); a.href = `/api/admin/library/games/${id}/covers/${revision}`; a.target = '_blank'; a.rel = 'noopener'; description.append(a); }
      list.append(description);
    }
    article.append(list); content.append(article);
  }
  openDialog('#history-dialog');
}
(async () => {
  try { const me = await api('/api/auth/me'); administrator = me.administrator; canManageGames = Boolean(me.canManageGames || administrator); $('#library-content').hidden = false; for (const id of ['#new-game', '#archived-filter']) $(id).hidden = !canManageGames; $('#location-tools').hidden = !administrator; applyView(); await listGames(); if (canManageGames && new URLSearchParams(window.location.search).get('action') === 'new') $('#new-game').click(); const id = new URLSearchParams(window.location.search).get('game'); if (id) await openGame(id); } catch (error) { status.textContent = error.message; }
})();
