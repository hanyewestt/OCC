import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../../frontend/static/auth.js', import.meta.url), 'utf8');
function harness({ key = 'sb_publishable_fixture', apiStatus = 200, role = 'CONTROLLER' } = {}) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: '', textContent: '', disabled: false,
      handlers: {}, addEventListener(type, fn) { this.handlers[type] = fn; } });
    return elements.get(id);
  };
  const calls = [];
  const events = [];
  let expiry;
  const payload = { exp: Math.floor(Date.now() / 1000) + 3600, occ_role: role };
  const token = `header.${Buffer.from(JSON.stringify(payload)).toString('base64url')}.signature`;
  const window = { dispatchEvent: event => events.push(event.detail) };
  const context = vm.createContext({ window, location: { origin: 'http://localhost' }, URL, Headers,
    CustomEvent: class { constructor(type, options) { this.detail = options.detail; } },
    atob, Date, clearTimeout() {}, setTimeout(fn) { expiry = fn; },
    fetch: async (url, options = {}) => {
      calls.push({ url: String(url), options });
      if (String(url) === '/frontend-config') return Response.json({
        projectUrl: 'https://fixture.supabase.co', publishableKey: key });
      if (String(url).startsWith('https://fixture.supabase.co/auth/v1/token')) {
        return Response.json({ access_token: token, refresh_token: 'must-never-be-stored' });
      }
      return Response.json({ ok: true }, { status: apiStatus });
    } });
  vm.runInContext(source, context);
  return { auth: window.occAuth, calls, events, element, token,
    async mount() { await window.occAuth.mount({ set innerHTML(value) {}, querySelector: element }); },
    async login() {
      element('#occ-email').value = 'controller@example.test';
      element('#occ-password').value = 'temporary-password';
      await element('#occ-sign-in').handlers.submit({ preventDefault() {} });
    }, expire() { expiry(); } };
}

test('sign-in sends passwords only to Supabase and attaches bearer tokens only to same-origin APIs', async () => {
  const h = harness(); await h.mount();
  await assert.rejects(h.auth.fetch('/api/v1/arrivals'), /Sign in/);
  assert.equal(h.calls.length, 1);
  await h.login();
  assert.equal(h.element('#occ-password').value, '');
  assert.equal(h.auth.role, 'CONTROLLER');
  assert.equal(h.calls[1].url, 'https://fixture.supabase.co/auth/v1/token?grant_type=password');
  await h.auth.fetch('/api/v1/demo-runs', { method: 'POST' });
  assert.equal(h.calls[2].options.headers.get('Authorization'), `Bearer ${h.token}`);
  assert.equal(h.calls[2].options.method, 'POST');
  assert.equal(h.calls[2].options.redirect, 'error');
  for (const path of ['https://evil.example/api/v1/arrivals', '/internal/v1/secret', '//evil.example/api/v1/arrivals']) {
    await assert.rejects(h.auth.fetch(path), /same-origin/);
  }
  assert.equal(h.calls.length, 3);
  h.auth.clear();
  await assert.rejects(h.auth.fetch('/api/v1/arrivals'), /Sign in/);
  assert.equal(h.auth.authenticated, false);
});

test('expiry and API 401 clear the session; API 403 preserves the authenticated role for inspection', async () => {
  for (const status of [401, 403]) {
    const h = harness({ apiStatus: status, role: 'DRIVER' }); await h.mount(); await h.login();
    const response = await h.auth.fetch('/api/v1/arrivals');
    assert.equal(response.status, status);
    assert.equal(h.auth.authenticated, status !== 401);
  }
  const h = harness(); await h.mount(); await h.login(); h.expire();
  assert.equal(h.auth.authenticated, false);
});

test('secret keys cannot enable browser sign-in', async () => {
  const h = harness({ key: 'sb_secret_forbidden' }); await h.mount();
  assert.equal(h.element('#occ-login').disabled, true);
  assert.match(h.element('#occ-session').textContent, /public Supabase publishable key/);
});

test('the demo routes all API fetches through the authenticated helper', () => {
  const html = readFileSync(new URL('../../frontend/static/demo.html', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /(?<![\w.])fetch\s*\(/);
  assert.match(html, /occAuth\.fetch/);
});
