const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function setup(hash = '#token=test-token', activationStatus = 204) {
  const nodes = new Map(); const calls = []; let replaced;
  const element = selector => {
    if (!nodes.has(selector)) nodes.set(selector, { value: '', hidden: false, disabled: false, handlers: {},
      addEventListener(event, handler) { this.handlers[event] = handler; }, reportValidity() { return true; }, reset() { this.resetCalled = true; } });
    return nodes.get(selector);
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../activate.js'), 'utf8'), {
    URLSearchParams,
    window: { location: { hash, pathname: '/activate.html' }, history: { replaceState(...args) { replaced = args[2]; } } },
    localStorage: { getItem() { return null; } },
    document: { querySelector: element, documentElement: { dataset: {} } },
    fetch: async (url, options) => {
      calls.push({ url, options });
      return url === '/api/auth/csrf'
        ? { ok: true, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }) }
        : { ok: activationStatus === 204, status: activationStatus };
    },
  });
  return { element, calls, replaced, submit: () => element('#activation-form').handlers.submit({ preventDefault() {} }),
    fill(password = 'un secret suffisamment long') { element('#new-password').value = password; element('#confirm-password').value = password; } };
}
test('le lien secret est retiré de l’adresse, sans requête à l’ouverture', () => {
  const app = setup(); assert.equal(app.replaced, '/activate.html'); assert.equal(app.calls.length, 0);
});
test('sans lien, le formulaire est masqué', () => {
  const app = setup(''); assert.equal(app.element('#activation-form').hidden, true);
});
test('les mots de passe doivent correspondre avant toute requête', async () => {
  const app = setup(); app.fill(); app.element('#confirm-password').value = 'autre'; await app.submit();
  assert.equal(app.calls.length, 0); assert.match(app.element('#activation-status').textContent, /correspondent/);
});
test('activation avec CSRF sans stocker de secret dans le navigateur', async () => {
  const app = setup(); app.fill(); await app.submit();
  assert.equal(app.calls[1].options.headers['X-CSRF-TOKEN'], 'csrf');
  assert.equal(JSON.parse(app.calls[1].options.body).token, 'test-token');
  assert.equal(app.element('#activation-form').hidden, true);
  assert.equal(app.element('#activation-form').resetCalled, true);
});
test('un lien expiré ne simule pas une activation réussie', async () => {
  const app = setup('#token=expire', 400); app.fill(); await app.submit();
  assert.match(app.element('#activation-status').textContent, /expiré/);
  assert.equal(app.element('#activation-form').hidden, false);
  assert.equal(app.element('#activate-submit').disabled, false);
});
