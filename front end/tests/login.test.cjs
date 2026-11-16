const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

// DOM minimal : vérifie les interactions et le contrat HTTP sans dépendance.
function setup(fetchImpl, savedTheme = null) {
  const elements = new Map();
  function element(selector) {
    if (!elements.has(selector)) elements.set(selector, {
      id: selector.slice(1), value: '', type: 'password', hidden: true,
      validity: { valid: true }, attributes: {}, handlers: {},
      addEventListener(name, handler) { this.handlers[name] = handler; },
      setAttribute(key, value) { this.attributes[key] = value; },
      removeAttribute(key) { delete this.attributes[key]; },
      focus() { this.focused = true; },
      querySelector(child) { return element(`${selector} ${child}`); },
    });
    return elements.get(selector);
  }
  const form = element('#login-form');
  form.action = 'http://localhost/api/auth/login';
  form.querySelector = () => [element('#email'), element('#password')].find(input => input.attributes['aria-invalid'] === 'true');
  const root = { dataset: {} };
  let redirect;
  const storage = new Map([['cej-theme', savedTheme]]);
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../login.js'), 'utf8'), {
    document: { querySelector: element, documentElement: root },
    window: { location: { assign(url) { redirect = url; } } },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
    fetch: (url, options) => url === '/api/auth/csrf'
      ? Promise.resolve({ ok: true, headers: new Map([['content-type', 'application/json']]),
        json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) })
      : fetchImpl(url, options),
    AbortController, setTimeout, clearTimeout,
  });
  return { element, root, storage, redirect: () => redirect,
    submit: () => form.handlers.submit({ preventDefault() {} }),
    fill() { element('#email').value = ' membre@exemple.fr '; element('#password').value = ' secret '; },
  };
}

test('les champs invalides empêchent toute requête et reçoivent le focus', async () => {
  let calls = 0;
  const app = setup(() => { calls++; });
  await app.submit();
  assert.equal(calls, 0);
  assert.equal(app.element('#email').focused, true);
  app.fill();
  app.element('#email').validity.valid = false;
  await app.submit();
  assert.equal(calls, 0);
  assert.match(app.element('#email-error').textContent, /valide/);
});

test('envoie les identifiants sans altérer le mot de passe et attend une confirmation explicite', async () => {
  let request;
  const app = setup(async (url, options) => {
    request = { url, options };
    return { ok: true, status: 200, headers: new Map([['content-type', 'application/json']]), json: async () => ({ authenticated: true }) };
  });
  app.fill();
  await app.submit();
  assert.deepEqual(JSON.parse(request.options.body), { email: 'membre@exemple.fr', password: ' secret ' });
  assert.equal(request.options.credentials, 'same-origin');
  assert.equal(request.options.headers['X-CSRF-TOKEN'], 'test-token');
  assert.equal(app.redirect(), '/member.html');
  assert.equal(app.element('#password').value, '');
});

for (const status of [401, 403, 429, 500]) {
  test(`une réponse ${status} conserve le formulaire utilisable sans redirection`, async () => {
    const app = setup(async () => ({ status, ok: false }));
    app.fill();
    await app.submit();
    assert.equal(app.redirect(), undefined);
    assert.equal(app.element('#form-message').hidden, false);
    assert.equal(app.element('#submit-button').disabled, false);
    assert.equal(app.element('#login-form').attributes['aria-busy'], undefined);
  });
}

test('une réponse HTML ne simule pas de connexion', async () => {
  const app = setup(async () => ({ status: 200, ok: true, headers: new Map([['content-type', 'text/html']]) }));
  app.fill(); await app.submit();
  assert.equal(app.redirect(), undefined);
  assert.match(app.element('#form-message').textContent, /indisponible/);
});

test('bloque les soumissions concurrentes', async () => {
  let finish;
  let calls = 0;
  const app = setup(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
  app.fill();
  const pending = app.submit();
  await app.submit();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls, 1);
  finish({ status: 500, ok: false });
  await pending;
});

test('affichage du mot de passe et préférence de thème', () => {
  const app = setup(undefined, 'dark');
  assert.equal(app.root.dataset.theme, 'dark');
  app.element('#theme-toggle').handlers.click();
  assert.equal(app.root.dataset.theme, undefined);
  assert.equal(app.storage.get('cej-theme'), 'system');
  app.element('#password-toggle').handlers.click();
  assert.equal(app.element('#password').type, 'text');
  assert.equal(app.element('#password-toggle').attributes['aria-pressed'], 'true');
  app.element('#password-toggle').handlers.click();
  assert.equal(app.element('#password').type, 'password');
});
