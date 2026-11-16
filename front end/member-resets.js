try { const theme = localStorage.getItem('cej-theme'); if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme; } catch { /* Préférence système. */ }
const $ = selector => document.querySelector(selector);
let page = 0, search = '', loading = false, count = 0;
async function api(path, post = false) {
  const options = { credentials: 'same-origin', headers: { Accept: 'application/json' } };
  if (post) {
    const response = await fetch('/api/auth/csrf', options); if (!response.ok) throw new Error('Impossible de préparer l’envoi.');
    const csrf = await response.json(); options.method = 'POST'; options.headers[csrf.headerName] = csrf.token;
  }
  const response = await fetch(path, options);
  if (response.status === 401) { window.location.replace('/'); throw new Error('Session expirée.'); }
  if (response.status === 403) { window.location.replace('/member.html'); throw new Error('Accès réservé aux administrateurs et membres du CA.'); }
  if (!response.ok) throw new Error(response.status === 409 ? 'Ce compte doit être actif et déjà activé. Actualisez la liste.' : 'L’opération a échoué. Veuillez réessayer.');
  return response.json();
}
async function list() {
  if (loading) return; loading = true; $('#reset-previous').disabled = $('#reset-next').disabled = true;
  try {
    const members = await api(`/api/password-reset/members?${new URLSearchParams({ page, search })}`); count = members.length;
    $('#reset-content').hidden = false; const container = $('#reset-members'); container.replaceChildren();
    if (!count) { const p = document.createElement('p'); p.textContent = 'Aucun membre actif et activé ne correspond à votre recherche.'; container.append(p); }
    for (const member of members) {
      const article = document.createElement('article'); article.className = 'user-row'; const name = document.createElement('strong'); name.textContent = member.displayName;
      const actions = document.createElement('div'); actions.className = 'action-row'; const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary-button'; button.textContent = 'Réinitialiser le mot de passe';
      button.addEventListener('click', async () => {
        if (button.disabled || !window.confirm(`Envoyer un lien de réinitialisation à ${member.displayName} ? Le mot de passe actuel reste valable jusqu’au changement par le membre.`)) return;
        button.disabled = true;
        try { const result = await api(`/api/admin/users/${encodeURIComponent(member.id)}/password-reset`, true); $('#reset-status').textContent = result.deliveryStatus === 'SENT' ? 'Lien de réinitialisation envoyé par email. Il expire dans une heure et remplace le lien précédent.' : 'L’email n’a pas pu être envoyé. Contactez un administrateur puis réessayez.'; }
        catch (error) { $('#reset-status').textContent = error.message; } finally { button.disabled = false; }
      });
      actions.append(button); article.append(name, actions); container.append(article);
    }
    $('#reset-page').textContent = `Page ${page + 1}`;
  } finally { loading = false; $('#reset-previous').disabled = page === 0; $('#reset-next').disabled = count < 20; }
}
$('#reset-search').addEventListener('submit', event => { event.preventDefault(); if (loading) return; page = 0; search = $('#reset-query').value.trim(); list().catch(error => { $('#reset-status').textContent = error.message; }); });
for (const [id, delta] of [['#reset-previous', -1], ['#reset-next', 1]]) $(id).addEventListener('click', () => { if (loading) return; page = Math.max(0, page + delta); list().catch(error => { $('#reset-status').textContent = error.message; }); });
list().then(() => { $('#reset-status').textContent = ''; }).catch(error => { $('#reset-status').textContent = error.message; });
