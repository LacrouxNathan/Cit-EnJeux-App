try {
  const theme = localStorage.getItem('cej-theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
} catch { /* Préférence système. */ }
const $ = selector => document.querySelector(selector);
const status = $('#admin-status');
const form = $('#create-user-form');
const submit = $('#create-submit');
const previous = $('#previous-page');
const next = $('#next-page');
let page = 0, loading = false, currentMember, editingUser, lastCount = 0;
const filters = { search: '', status: 'all', role: 'all', membership: 'all' };

async function api(path, body, method = 'POST') {
  const options = { credentials: 'same-origin', headers: { Accept: 'application/json' } };
  if (body !== undefined) {
    const csrfResponse = await fetch('/api/auth/csrf', options);
    if (!csrfResponse.ok) throw new Error('Impossible de préparer la requête. Veuillez réessayer.');
    const csrf = await csrfResponse.json();
    options.method = method;
    options.headers = { ...options.headers, 'Content-Type': 'application/json', [csrf.headerName]: csrf.token };
    options.body = JSON.stringify(body);
  }
  let response;
  try { response = await fetch(path, options); } catch { throw new Error('Le service est inaccessible. Veuillez réessayer.'); }
  if (response.status === 401) { window.location.replace('/'); throw new Error('Votre session a expiré.'); }
  if (response.status === 403) { window.location.replace('/member.html'); throw new Error('Cette action est réservée aux administrateurs.'); }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    const messages = {
      EMAIL_ALREADY_USED: 'Cette adresse email est déjà utilisée.',
      INVITATION_NOT_ALLOWED: 'Ce compte est déjà activé ou a été désactivé.',
      LAST_ACTIVE_ADMIN: 'Il doit rester au moins un administrateur actif ayant activé son compte. Activez un autre administrateur avant de retirer ces accès.',
      STALE_USER_VERSION: 'Ce compte a été modifié depuis son ouverture. Fermez puis rouvrez le formulaire pour charger les dernières informations.',
      USER_NOT_FOUND: 'Ce membre n’est plus accessible.',
      INVALID_FILTER: 'Vérifiez les filtres sélectionnés.',
      RESET_NOT_ALLOWED: 'La réinitialisation nécessite un compte actif et déjà activé. Pour un compte invité, renvoyez l’invitation.',
      INVALID_ROLE: 'Choisissez un seul rôle : Membre, Membre du CA ou Administrateur.',
    };
    if (messages[result.code]) throw new Error(messages[result.code]);
    if (response.status === 400) throw new Error('Vérifiez les champs saisis. Le prénom, le nom et un email valide sont obligatoires.');
    throw new Error('L’opération a échoué. Veuillez réessayer.');
  }
  return result;
}
function element(tag, text, className) {
  const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node;
}
function action(label, callback) {
  const button = element('button', label, 'secondary-button'); button.type = 'button';
  button.addEventListener('click', async () => {
    if (button.disabled) return; button.disabled = true;
    try { await callback(); } catch (error) { status.textContent = error.message; }
    finally { button.disabled = false; }
  });
  return button;
}
function deliveryMessage(result, created) {
  return result.deliveryStatus === 'SENT'
    ? (created ? 'Compte créé et invitation envoyée.' : 'Nouvelle invitation envoyée. Le lien précédent est invalidé.')
    : 'Le compte est enregistré, mais l’email n’a pas pu être envoyé. Vérifiez le service email puis utilisez « Renvoyer l’invitation ».';
}
async function listUsers() {
  if (loading) return;
  loading = true; previous.disabled = next.disabled = true;
  try {
    const users = await api(`/api/admin/users?${new URLSearchParams({ page, ...filters })}`); lastCount = users.length;
    const list = $('#users-list'); list.replaceChildren();
    if (!users.length) list.append(element('p', 'Aucun membre ne correspond à ces critères.'));
    for (const user of users) {
      const article = element('article', undefined, 'user-row'); article.dataset.userId = user.id;
      const name = element('strong', `${user.firstName} ${user.lastName}${user.id === currentMember.id ? ' (vous)' : ''}`);
      const email = element('p', user.email);
      const state = !user.active ? 'Désactivé' : user.invitationStatus === 'ACTIVATED' ? 'Activé' : user.invitationStatus === 'EXPIRED' ? 'Invitation expirée' : 'Invitation en attente';
      const detail = element('p', `${state} · ${user.administrator ? 'Administrateur' : user.boardMember ? 'Membre du CA' : 'Membre'} · ${user.membershipCurrent ? 'Adhésion à jour' : 'Adhésion non à jour'}`);
      article.append(name, email);
      if (user.nickname) article.append(element('p', `Pseudonyme : ${user.nickname}`));
      article.append(detail);
      const actions = element('div', undefined, 'action-row');
      actions.append(action('Modifier', () => editUser(user.id)), action('Historique', () => showHistory(user.id)));
      if (user.active && user.invitationStatus !== 'ACTIVATED') actions.append(action('Renvoyer l’invitation', async () => {
        const result = await api(`/api/admin/users/${encodeURIComponent(user.id)}/invitation`, {});
        status.textContent = deliveryMessage(result, false); await listUsers();
      }));
      if (user.active && user.invitationStatus === 'ACTIVATED') actions.append(action('Réinitialiser le mot de passe', async () => {
        if (!window.confirm(`Envoyer un lien de réinitialisation à ${user.email} ? Le mot de passe actuel reste valable jusqu’au changement par le membre.`)) return;
        const result = await api(`/api/admin/users/${encodeURIComponent(user.id)}/password-reset`, {});
        status.textContent = result.deliveryStatus === 'SENT' ? 'Lien de réinitialisation envoyé par email. Il expire dans une heure et remplace le lien précédent.' : 'Le lien a été créé mais l’email n’a pas pu être envoyé. Vérifiez le service email puis réessayez.';
      }));
      article.append(actions); list.append(article);
    }
    $('#page-number').textContent = `Page ${page + 1}`;
  } finally { loading = false; previous.disabled = page === 0; next.disabled = lastCount < 20; }
}
async function editUser(id) {
  editingUser = await api(`/api/admin/users/${encodeURIComponent(id)}`);
  $('#edit-user-title').textContent = `Modifier ${editingUser.firstName} ${editingUser.lastName}`;
  for (const key of ['firstName', 'lastName', 'email', 'nickname']) $('#edit-user-form').elements[key].value = editingUser[key] ?? '';
  $('#edit-role').value = editingUser.administrator ? 'admin' : editingUser.boardMember ? 'board' : 'member';
  $('#edit-membership').checked = editingUser.membershipCurrent; $('#edit-active').checked = editingUser.active;
  $('#edit-user-status').textContent = ''; $('#save-user').disabled = false;
  $('#edit-user-dialog').showModal();
}
$('#close-edit-user').addEventListener('click', () => $('#edit-user-dialog').close());
$('#close-user-history').addEventListener('click', () => $('#user-history-dialog').close());
$('#edit-user-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = $('#save-user'); if (button.disabled || !event.currentTarget.reportValidity()) return;
  const data = new FormData(event.currentTarget);
  const payload = Object.fromEntries(['email', 'firstName', 'lastName', 'nickname'].map(key => [key, data.get(key).trim()]));
  payload.administrator = data.get('role') === 'admin'; payload.boardMember = data.get('role') === 'board'; payload.membershipCurrent = data.has('membershipCurrent'); payload.active = data.has('active'); payload.version = editingUser.version;
  if (!payload.firstName || !payload.lastName) { $('#edit-user-status').textContent = 'Le prénom et le nom sont obligatoires.'; return; }
  const warnings = [];
  if (payload.administrator !== editingUser.administrator) warnings.push(payload.administrator ? 'Ce membre recevra les mêmes droits de gestion que les autres administrateurs.' : 'Les droits administrateur seront retirés de ce compte.');
  if (payload.boardMember !== Boolean(editingUser.boardMember)) warnings.push(payload.boardMember ? 'Ce membre pourra créer, modifier et archiver les fiches de jeux, sans gérer les comptes ni les exemplaires.' : 'Les droits de gestion des jeux du CA seront retirés.');
  if (payload.active !== editingUser.active) warnings.push(payload.active ? 'Le compte sera réactivé. Les anciennes sessions resteront invalides.' : 'Le compte sera désactivé et ses sessions ne pourront plus accéder à l’application. Les historiques seront conservés.');
  const emailChanged = payload.email.toLowerCase() !== editingUser.email.toLowerCase();
  if (emailChanged) warnings.push('Le changement d’email fermera les sessions. Pour un compte non activé, une nouvelle invitation devra être renvoyée.');
  if (editingUser.id === currentMember.id && (emailChanged || !payload.active)) warnings.push('Vous serez déconnecté après cette modification.');
  else if (editingUser.id === currentMember.id && !payload.administrator) warnings.push('Vous quitterez l’espace d’administration.');
  if (warnings.length && !window.confirm(`${warnings.join('\n\n')}\n\nConfirmer ces modifications ?`)) return;
  button.disabled = true;
  try {
    const result = await api(`/api/admin/users/${encodeURIComponent(editingUser.id)}`, payload, 'PUT');
    $('#edit-user-dialog').close();
    if (result.id === currentMember.id && (!result.active || emailChanged)) { window.location.replace('/'); return; }
    if (result.id === currentMember.id && !result.administrator) { window.location.replace('/member.html'); return; }
    status.textContent = 'Les informations du membre ont été enregistrées.';
    if (emailChanged && result.invitationStatus !== 'ACTIVATED') status.textContent += ' Renvoyez une invitation à sa nouvelle adresse.';
    await listUsers();
  } catch (error) { $('#edit-user-status').textContent = error.message; }
  finally { button.disabled = false; }
});
const fieldLabels = { email: 'Email', first_name: 'Prénom', last_name: 'Nom', nickname: 'Pseudonyme', membership_current: 'Adhésion à jour', administrator: 'Droits administrateur', board_member: 'Membre du CA', active: 'Compte actif' };
const eventLabels = { PASSWORD_RESET_REQUESTED: 'Réinitialisation du mot de passe demandée', PASSWORD_RESET_COMPLETED: 'Mot de passe réinitialisé', USER_CREATED: 'Compte créé', INVITATION_ISSUED: 'Invitation créée ou renouvelée', ACCOUNT_ACTIVATED: 'Compte activé', ACCOUNT_UPDATED: 'Informations du membre modifiées' };
function displayValue(value) { return value === true ? 'Oui' : value === false ? 'Non' : value == null || value === '' ? '—' : String(value); }
async function showHistory(id) {
  const [user, events] = await Promise.all([api(`/api/admin/users/${encodeURIComponent(id)}`), api(`/api/admin/users/${encodeURIComponent(id)}/history`)]);
  $('#user-history-title').textContent = `Historique de ${user.firstName} ${user.lastName}`;
  const content = $('#user-history-content'); content.replaceChildren();
  if (!events.length) content.append(element('p', 'Aucun événement enregistré.'));
  for (const event of events) {
    const article = element('article', undefined, 'audit-event');
    article.append(element('h3', eventLabels[event.action] || event.action));
    article.append(element('p', `${new Date(event.createdAt).toLocaleString('fr-FR', { timeZone: 'Europe/Paris' })} · ${event.actorName || 'Administrateur historique'}`));
    const changes = typeof event.changes === 'string' ? JSON.parse(event.changes) : event.changes;
    const list = element('dl', undefined, 'game-facts');
    for (const [key, value] of Object.entries(changes)) list.append(element('dt', fieldLabels[key] || key), element('dd', `${displayValue(value.before)} → ${displayValue(value.after)}`));
    article.append(list); content.append(article);
  }
  $('#user-history-dialog').showModal();
}
form.addEventListener('submit', async event => {
  event.preventDefault(); if (submit.disabled || !form.reportValidity()) return;
  const data = new FormData(form);
  const payload = Object.fromEntries(['firstName', 'lastName', 'email', 'nickname'].map(key => [key, data.get(key).trim()]));
  if (!payload.firstName || !payload.lastName) { status.textContent = 'Le prénom et le nom sont obligatoires.'; return; }
  payload.membershipCurrent = data.has('membershipCurrent'); submit.disabled = true;
  try { const result = await api('/api/admin/users', payload); form.reset(); page = 0; status.textContent = deliveryMessage(result, true); await listUsers(); }
  catch (error) { status.textContent = error.message; }
  finally { submit.disabled = false; }
});
$('#user-filters').addEventListener('submit', event => {
  event.preventDefault(); if (loading) return; Object.assign(filters, Object.fromEntries(new FormData(event.currentTarget))); page = 0; listUsers().catch(error => { status.textContent = error.message; });
});
$('#reset-user-filters').addEventListener('click', () => {
  if (loading) return; $('#user-filters').reset(); Object.assign(filters, { search: '', status: 'all', role: 'all', membership: 'all' }); page = 0; listUsers().catch(error => { status.textContent = error.message; });
});
for (const [button, delta] of [[previous, -1], [next, 1]]) button.addEventListener('click', async () => {
  if (loading) return; const old = page; page = Math.max(0, page + delta);
  try { await listUsers(); } catch (error) { page = old; status.textContent = error.message; }
});
(async () => {
  try { currentMember = await api('/api/auth/me'); if (!currentMember.administrator) { status.textContent = 'Cet espace est réservé aux administrateurs.'; return; } $('#admin-content').hidden = false; await listUsers(); status.textContent = ''; }
  catch (error) { status.textContent = error.message; }
})();
