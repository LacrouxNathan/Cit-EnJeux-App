try {
  const theme = localStorage.getItem('cej-theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
} catch { /* La préférence système reste utilisée. */ }
const status = document.querySelector('#session-status');
const loginLink = document.querySelector('#login-link');
const logout = document.querySelector('#logout');

async function loadSession() {
  try {
    const response = await fetch('/api/auth/me', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (response.status === 401 || response.status === 403) { window.location.replace('/'); return; }
    if (!response.ok) throw new Error();
    const member = await response.json();
    document.querySelector('#display-name').textContent = member.displayName;
    document.querySelector('#admin-users-link').hidden = !member.administrator;
    document.querySelector('#board-reset-link').hidden = !member.boardMember;
    document.querySelector('#manage-games-link').hidden = !member.canManageGames;
    document.querySelector('#member-role').textContent = member.administrator ? 'Administrateur' : member.boardMember ? 'Membre du CA' : 'Membre';
    document.querySelector('#membership-mark').textContent = member.membershipCurrent ? '✓' : 'i';
    document.querySelector('#membership-status').textContent = member.membershipCurrent ? 'Votre adhésion est à jour. Merci de faire partie de l’aventure !' : 'Votre adhésion n’est pas à jour. Contactez un administrateur pour la renouveler.';
    document.querySelector('#member-content').hidden = false;
    status.textContent = '';
  } catch {
    status.textContent = 'Impossible de vérifier votre session. Veuillez réessayer.';
    loginLink.hidden = false;
  }
}
logout.addEventListener('click', async () => {
  logout.disabled = true;
  try {
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error();
    const csrf = await response.json();
    const result = await fetch('/api/auth/logout', {
      method: 'POST', credentials: 'same-origin', headers: { [csrf.headerName]: csrf.token },
    });
    if (!result.ok) throw new Error();
    window.location.replace('/');
  } catch {
    status.textContent = 'La déconnexion a échoué. Veuillez réessayer.';
    logout.disabled = false;
  }
});
loadSession();
