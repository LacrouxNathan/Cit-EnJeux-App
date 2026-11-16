const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
// Le lien secret ne reste ni dans la barre d’adresse ni dans les requêtes HTTP.
window.history.replaceState(null, '', window.location.pathname);
try {
  const theme = localStorage.getItem('cej-theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
} catch { /* Préférence système. */ }
const form = document.querySelector('#activation-form');
const status = document.querySelector('#activation-status');
const submit = document.querySelector('#activate-submit');
if (!token) {
  form.hidden = true;
  status.textContent = 'Ouvrez le lien reçu par email. Si votre lien de réinitialisation a expiré, demandez à un administrateur de le renvoyer.';
}
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (submit.disabled || !form.reportValidity()) return;
  const password = document.querySelector('#new-password').value;
  if (password !== document.querySelector('#confirm-password').value) {
    status.textContent = 'Les mots de passe ne correspondent pas.'; return;
  }
  submit.disabled = true;
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!csrfResponse.ok) throw new Error();
    const csrf = await csrfResponse.json();
    const response = await fetch('/api/password-reset/confirm', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ token, password }),
    });
    if (response.status === 400) {
      status.textContent = 'Ce lien est invalide, expiré ou déjà utilisé, ou le mot de passe est trop court. Pour un nouveau lien, contactez un administrateur.';
      return;
    }
    if (!response.ok) throw new Error();
    form.reset(); form.hidden = true;
    status.textContent = 'Votre mot de passe a été réinitialisé. Connectez-vous avec votre nouveau mot de passe.';
  } catch {
    status.textContent = 'Le service est indisponible. Veuillez réessayer.';
  } finally { submit.disabled = false; }
});
