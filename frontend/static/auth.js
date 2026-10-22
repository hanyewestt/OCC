/* Standalone demo password sign-in.
 * Access tokens live only in this document's memory; refresh tokens are discarded. */
(() => {
  let token = null;
  let expiresAt = 0;
  let role = null;
  let config = null;
  let revision = 0;
  let expiryTimer = null;
  let session;
  const changed = () => window.dispatchEvent(new CustomEvent('occ-auth-change', {
    detail: { authenticated: Boolean(token), role }
  }));
  function clear(message = 'Session cleared. Sign in to call protected APIs.') {
    revision++;
    token = null;
    role = null;
    expiresAt = 0;
    clearTimeout(expiryTimer);
    if (session) session.textContent = message;
    changed();
  }
  async function apiFetch(path, options = {}) {
    const target = new URL(path, location.origin);
    if (target.origin !== location.origin || !target.pathname.startsWith('/api/v1/')) {
      throw new Error('Use a same-origin /api/v1/... API path.');
    }
    if (!token || Date.now() >= expiresAt) {
      clear('Session missing or expired. Sign in again.');
      throw new Error('Sign in before calling protected APIs.');
    }
    const currentRevision = revision;
    const headers = new Headers(options.headers);
    headers.set('Authorization', `Bearer ${token}`);
    const response = await fetch(target, { ...options, headers, redirect: 'error' });
    if (currentRevision !== revision) throw new Error('Session changed; request result discarded.');
    if (response.status === 401) clear('Session rejected or expired. Sign in again.');
    return response;
  }
  async function mount(container) {
    container.innerHTML = `<fieldset><legend>Supabase sign-in</legend>
      <p>Use an OCC test account. Tokens stay in this page's memory and are cleared on reload.</p>
      <form id="occ-sign-in"><label>Email <input id="occ-email" type="email" autocomplete="username" required></label>
      <label>Password <input id="occ-password" type="password" autocomplete="current-password" required></label>
      <button id="occ-login" type="submit" disabled>Sign in</button>
      <button id="occ-logout" type="button">Clear session</button></form>
      <p id="occ-session" role="status">Loading public sign-in configuration…</p></fieldset>`;
    session = container.querySelector('#occ-session');
    const login = container.querySelector('#occ-login');
    login.disabled = true;
    container.querySelector('#occ-logout').addEventListener('click', () => clear());
    container.querySelector('#occ-sign-in').addEventListener('submit', async event => {
      event.preventDefault();
      clear('Signing in…');
      const attempt = revision;
      login.disabled = true;
      const passwordInput = container.querySelector('#occ-password');
      const email = container.querySelector('#occ-email').value.trim();
      const password = passwordInput.value;
      passwordInput.value = '';
      try {
        const response = await fetch(`${config.projectUrl.replace(/\/+$/, '')}/auth/v1/token?grant_type=password`, {
          method: 'POST', redirect: 'error',
          headers: { apikey: config.publishableKey, 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password })
        });
        const body = await response.json();
        if (!response.ok || typeof body.access_token !== 'string') {
          throw new Error(body.msg || body.message || body.error_description || 'Supabase sign-in failed.');
        }
        if (attempt !== revision) return;
        const encoded = body.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        const claims = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')));
        if (!Number.isFinite(claims.exp) || claims.exp * 1000 <= Date.now()) throw new Error('Token is expired.');
        token = body.access_token;
        expiresAt = claims.exp * 1000;
        role = typeof claims.occ_role === 'string' ? claims.occ_role : 'UNASSIGNED';
        session.textContent = `Signed in as ${email}. Decoded role: ${role}. APIs verify the token and role.`;
        expiryTimer = setTimeout(() => clear('Session expired. Sign in again.'), expiresAt - Date.now());
        changed();
      } catch (error) {
        if (attempt === revision) clear(`Sign-in failed: ${error.message}`);
      } finally {
        login.disabled = !config;
      }
    });
    try {
      const response = await fetch('/frontend-config', { cache: 'no-store', redirect: 'error' });
      if (!response.ok) throw new Error(`Configuration unavailable (${response.status}).`);
      const publicConfig = await response.json();
      const url = new URL(publicConfig.projectUrl);
      if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) {
        throw new Error('Supabase must use HTTPS.');
      }
      if (!publicConfig.publishableKey?.startsWith('sb_publishable_')) {
        throw new Error('Set SUPABASE_SB_PUBLISHABLE to the public Supabase publishable key.');
      }
      config = publicConfig;
      login.disabled = false;
      session.textContent = 'Sign in to call protected APIs.';
    } catch (error) {
      session.textContent = error.message;
    }
  }
  window.occAuth = { mount, fetch: apiFetch, clear,
    get authenticated() { return Boolean(token); }, get role() { return role; } };
})();
