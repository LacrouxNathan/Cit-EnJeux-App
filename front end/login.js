const form = document.querySelector('#login-form');
const email = document.querySelector('#email');
const password = document.querySelector('#password');
const submit = document.querySelector('#submit-button');
const message = document.querySelector('#form-message');
const passwordToggle = document.querySelector('#password-toggle');
const themeToggle = document.querySelector('#theme-toggle');
const themes = ['system', 'light', 'dark'];
const themeNames = { system: 'Thème système', light: 'Thème clair', dark: 'Thème sombre' };
let theme = 'system';
try {
  const saved = localStorage.getItem('cej-theme');
  if (themes.includes(saved)) theme = saved;
} catch { /* Le choix reste disponible si le stockage est bloqué. */ }

function applyTheme() {
  if (theme === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
  document.querySelector('#theme-label').textContent = themeNames[theme];
  themeToggle.setAttribute('aria-label', `${themeNames[theme]}. Activer le ${themeNames[themes[(themes.indexOf(theme) + 1) % themes.length]].toLowerCase()}.`);
}
applyTheme();
themeToggle.hidden = false;
themeToggle.addEventListener('click', () => {
  theme = themes[(themes.indexOf(theme) + 1) % themes.length];
  applyTheme();
  try { localStorage.setItem('cej-theme', theme); } catch { /* Facultatif. */ }
});

passwordToggle.hidden = false;
passwordToggle.addEventListener('click', () => {
  const visible = password.type === 'password';
  password.type = visible ? 'text' : 'password';
  passwordToggle.setAttribute('aria-pressed', String(visible));
  passwordToggle.setAttribute('aria-label', visible ? 'Masquer le mot de passe' : 'Afficher le mot de passe');
});

function fieldError(input, text = '') {
  const error = document.querySelector(`#${input.id}-error`);
  error.textContent = text;
  error.hidden = !text;
  input.setAttribute('aria-invalid', String(Boolean(text)));
}
for (const input of [email, password]) {
  input.addEventListener('input', () => { fieldError(input); message.hidden = true; });
}

// Cookie HttpOnly et jeton CSRF de session : aucun secret dans localStorage.
form.noValidate = true;
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (submit.disabled) return;
  email.value = email.value.trim();
  fieldError(email, !email.value ? 'Saisissez votre adresse email.' : !email.validity.valid ? 'Saisissez une adresse email valide.' : '');
  fieldError(password, !password.value ? 'Saisissez votre mot de passe.' : '');
  const invalid = form.querySelector('[aria-invalid="true"]');
  message.hidden = true;
  if (invalid) { invalid.focus(); return; }

  submit.disabled = true;
  form.setAttribute('aria-busy', 'true');
  submit.querySelector('span').textContent = 'Connexion en cours…';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const csrfResponse = await fetch('/api/auth/csrf', {
      credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: controller.signal,
    });
    if (!csrfResponse.ok || !csrfResponse.headers.get('content-type')?.includes('application/json')) {
      throw new Error('Le service de connexion est indisponible. Veuillez réessayer plus tard.');
    }
    const csrf = await csrfResponse.json();
    if (typeof csrf.token !== 'string' || csrf.headerName !== 'X-CSRF-TOKEN') {
      throw new Error('Le service de connexion est indisponible. Veuillez réessayer plus tard.');
    }
    const response = await fetch(form.action, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ email: email.value, password: password.value }),
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) {
      throw new Error('Connexion impossible. Vérifiez vos identifiants ou contactez un administrateur.');
    }
    if (response.status === 429) throw new Error('Trop de tentatives. Patientez avant de réessayer.');
    if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
      throw new Error('Le service de connexion est indisponible. Veuillez réessayer plus tard.');
    }
    const result = await response.json();
    if (result.authenticated !== true) throw new Error('La connexion n’a pas pu être confirmée. Veuillez réessayer.');
    password.value = '';
    window.location.assign('/member.html');
  } catch (error) {
    message.textContent = error.name === 'AbortError'
      ? 'Le service met trop de temps à répondre. Veuillez réessayer.'
      : error instanceof TypeError || error instanceof SyntaxError
        ? 'Le service de connexion est inaccessible. Vérifiez votre connexion et réessayez.'
        : error.message;
    message.hidden = false;
  } finally {
    clearTimeout(timeout);
    submit.disabled = false;
    form.removeAttribute('aria-busy');
    submit.querySelector('span').textContent = 'Se connecter';
  }
});

document.querySelector('#year').textContent = new Date().getFullYear();
