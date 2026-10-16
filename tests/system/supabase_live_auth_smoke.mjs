import { createPublicKey, verify } from 'node:crypto';

const accountSettings = {
  ADMIN: ['OCC_SUPABASE_TEST_ADMIN_EMAIL', 'OCC_SUPABASE_TEST_ADMIN_PASSWORD'],
  CONTROLLER: ['OCC_SUPABASE_TEST_CONTROLLER_EMAIL', 'OCC_SUPABASE_TEST_CONTROLLER_PASSWORD'],
  DRIVER: ['OCC_SUPABASE_TEST_DRIVER_EMAIL', 'OCC_SUPABASE_TEST_DRIVER_PASSWORD'],
  UNASSIGNED: ['OCC_SUPABASE_TEST_UNASSIGNED_EMAIL', 'OCC_SUPABASE_TEST_UNASSIGNED_PASSWORD'],
};
const args = process.argv.slice(2);
const supabaseOnly = args.includes('--supabase-only');
const requestedAccounts = args.filter((name) => name !== '--supabase-only').map((name) => name.toUpperCase());
const unknownAccounts = requestedAccounts.filter((name) => !Object.hasOwn(accountSettings, name));
if (unknownAccounts.length) {
  console.error(`Unknown account labels: ${unknownAccounts.join(', ')}.`);
  process.exit(2);
}
const accountNamesToCheck = requestedAccounts.length ? requestedAccounts : Object.keys(accountSettings);
const required = [
  'SUPABASE_PROJECT_URL',
  'SUPABASE_JWKS_URL',
  'SUPABASE_SB_PUBLISHABLE',
  ...accountNamesToCheck.flatMap((name) => accountSettings[name]),
];
if (!supabaseOnly) required.push('OCC_SUPABASE_TEST_GATEWAY_URL');
const missing = required.filter((name) => !process.env[name]?.trim());
if (missing.length) {
  console.error(`Missing live-auth settings: ${missing.join(', ')}`);
  process.exit(2);
}

const projectUrl = new URL(process.env.SUPABASE_PROJECT_URL);
if (projectUrl.protocol !== 'https:' || projectUrl.pathname !== '/' || projectUrl.search || projectUrl.hash) {
  throw new Error('SUPABASE_PROJECT_URL must be an HTTPS project origin.');
}
const projectBase = projectUrl.origin;
const issuer = `${projectBase}/auth/v1`;
const jwksUrl = new URL(process.env.SUPABASE_JWKS_URL);
const gatewayUrl = supabaseOnly ? undefined : new URL(process.env.OCC_SUPABASE_TEST_GATEWAY_URL);
if (jwksUrl.protocol !== 'https:' || jwksUrl.username || jwksUrl.password || jwksUrl.hash) {
  throw new Error('JWKS URL must be a valid HTTPS endpoint without embedded credentials.');
}
if (gatewayUrl && ((gatewayUrl.protocol !== 'http:' && gatewayUrl.protocol !== 'https:')
    || gatewayUrl.username || gatewayUrl.password || gatewayUrl.search || gatewayUrl.hash)) {
  throw new Error('Gateway URL must be a valid HTTP(S) endpoint without embedded credentials.');
}

const timeoutMs = Number(process.env.OCC_SUPABASE_TEST_TIMEOUT_MS || 20000);
if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 120000) {
  throw new Error('OCC_SUPABASE_TEST_TIMEOUT_MS must be between 1000 and 120000.');
}
const audience = process.env.SUPABASE_JWT_AUDIENCE || 'authenticated';
const publishableKey = process.env.SUPABASE_SB_PUBLISHABLE;
const protectedPath = '/api/v1/disruption/bunching-alerts?page=0&size=5';
const accounts = [
  { name: 'ADMIN', email: process.env.OCC_SUPABASE_TEST_ADMIN_EMAIL, password: process.env.OCC_SUPABASE_TEST_ADMIN_PASSWORD, role: 'ADMIN', status: 200 },
  { name: 'CONTROLLER', email: process.env.OCC_SUPABASE_TEST_CONTROLLER_EMAIL, password: process.env.OCC_SUPABASE_TEST_CONTROLLER_PASSWORD, role: 'CONTROLLER', status: 200 },
  { name: 'DRIVER', email: process.env.OCC_SUPABASE_TEST_DRIVER_EMAIL, password: process.env.OCC_SUPABASE_TEST_DRIVER_PASSWORD, role: 'DRIVER', status: 403 },
  { name: 'UNASSIGNED', email: process.env.OCC_SUPABASE_TEST_UNASSIGNED_EMAIL, password: process.env.OCC_SUPABASE_TEST_UNASSIGNED_PASSWORD, role: null, status: 403 },
];
const accountsToTest = requestedAccounts.length
  ? accounts.filter((account) => requestedAccounts.includes(account.name))
  : accounts;

const decodeBase64Url = (value) => Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
async function request(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

let jwks;
async function fetchJwks() {
  const response = await request(jwksUrl);
  if (!response.ok) throw new Error(`JWKS returned HTTP ${response.status}.`);
  jwks = await response.json();
}

async function validateToken(token, expectedRole) {
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('JWT is malformed.');
  const header = JSON.parse(decodeBase64Url(parts[0]));
  const claims = JSON.parse(decodeBase64Url(parts[1]));
  let jwk = jwks.keys.find((key) => key.kid === header.kid && key.kty === 'EC' && key.crv === 'P-256');
  if (!jwk) {
    await fetchJwks();
    jwk = jwks.keys.find((key) => key.kid === header.kid && key.kty === 'EC' && key.crv === 'P-256');
  }
  if (header.alg !== 'ES256' || !jwk || (jwk.alg && jwk.alg !== 'ES256')) {
    throw new Error('JWT algorithm or signing key is not allowed.');
  }
  const publicKey = createPublicKey({ key: jwk, format: 'jwk' });
  const validSignature = verify('sha256', Buffer.from(`${parts[0]}.${parts[1]}`),
    { key: publicKey, dsaEncoding: 'ieee-p1363' }, decodeBase64Url(parts[2]));
  if (!validSignature) throw new Error('JWT signature is invalid.');

  const now = Math.floor(Date.now() / 1000);
  if (claims.iss !== issuer || ![].concat(claims.aud).includes(audience) || !claims.sub
      || !Number.isSafeInteger(claims.exp) || claims.exp <= now
      || claims.nbf !== undefined && (!Number.isSafeInteger(claims.nbf) || claims.nbf > now + 30)) {
    throw new Error('JWT issuer, audience, subject, or time claims are invalid.');
  }
  if ((claims.occ_role ?? null) !== expectedRole) throw new Error('JWT OCC role claim does not match.');
}

async function authPost(path, body, accessToken) {
  return request(new URL(`/auth/v1/${path}`, projectBase), {
    method: 'POST',
    headers: {
      apikey: publishableKey,
      'content-type': 'application/json',
      ...(accessToken ? { authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

async function checkProtectedRead(accessToken, expectedStatus) {
  const response = await request(new URL(protectedPath, gatewayUrl), {
    headers: accessToken ? { authorization: `Bearer ${accessToken}` } : {},
  });
  await response.arrayBuffer();
  if (response.status !== expectedStatus) {
    throw Object.assign(new Error('Gateway status mismatch.'), { observedStatus: response.status });
  }
}

let failures = 0;
try {
  await fetchJwks();
} catch {
  failures += 1;
  console.error('FAIL Supabase JWKS check (details withheld).');
}
if (jwks && !supabaseOnly) {
  try {
    await checkProtectedRead(null, 401);
    console.log('PASS missing bearer token is denied with HTTP 401.');
  } catch {
    failures += 1;
    console.error('FAIL missing-token Gateway check (details withheld).');
  }
}

for (const account of accountsToTest) {
  let accessToken;
  let refreshToken;
  let stage = 'sign-in';
  let failureStatus;
  try {
    const signIn = await authPost('token?grant_type=password', {
      email: account.email,
      password: account.password,
    });
    if (!signIn.ok) {
      failureStatus = signIn.status;
      await signIn.body?.cancel();
      throw new Error('Supabase rejected sign-in.');
    }
    const session = await signIn.json();
    accessToken = session.access_token;
    refreshToken = session.refresh_token;
    if (!accessToken || !refreshToken) throw new Error('Supabase returned no complete session.');
    stage = 'token validation';
    await validateToken(accessToken, account.role);
    if (!supabaseOnly) {
      stage = 'Gateway authorization';
      await checkProtectedRead(accessToken, account.status);
    }
    console.log(supabaseOnly
      ? `PASS ${account.name} sign-in and Supabase token validation.`
      : `PASS ${account.name} sign-in token validated; Gateway returned HTTP ${account.status}.`);

    stage = 'refresh';
    const refreshedResponse = await authPost('token?grant_type=refresh_token', { refresh_token: refreshToken });
    if (!refreshedResponse.ok) {
      failureStatus = refreshedResponse.status;
      await refreshedResponse.body?.cancel();
      throw new Error('Supabase rejected refresh.');
    }
    const refreshed = await refreshedResponse.json();
    accessToken = refreshed.access_token;
    refreshToken = refreshed.refresh_token;
    if (!accessToken || !refreshToken) throw new Error('Supabase returned no refreshed session.');
    stage = 'refreshed-token validation';
    await validateToken(accessToken, account.role);
    if (!supabaseOnly) {
      stage = 'refreshed Gateway authorization';
      await checkProtectedRead(accessToken, account.status);
    }
    console.log(supabaseOnly
      ? `PASS ${account.name} refreshed Supabase token validation.`
      : `PASS ${account.name} refreshed token validated; Gateway returned HTTP ${account.status}.`);
  } catch (error) {
    failures += 1;
    const observedStatus = failureStatus ?? error?.observedStatus;
    const status = observedStatus ? ` HTTP ${observedStatus}` : '';
    const safeReason = new Map([
      ['JWT is malformed.', 'malformed JWT'],
      ['JWT algorithm or signing key is not allowed.', 'signing algorithm/key mismatch'],
      ['JWT signature is invalid.', 'signature validation failed'],
      ['JWT issuer, audience, subject, or time claims are invalid.', 'issuer/audience/time validation failed'],
      ['JWT OCC role claim does not match.', 'OCC role claim mismatch'],
      ['Gateway status mismatch.', 'Gateway authorization outcome mismatch'],
    ]).get(error?.message);
    const reason = safeReason ? ` (${safeReason})` : '';
    console.error(`FAIL ${account.name} ${stage} check${status}${reason} (response details withheld).`);
  } finally {
    if (accessToken) {
      try {
        const signOut = await authPost('logout?scope=local', {}, accessToken);
        if (!signOut.ok) throw new Error('sign-out failed');
        console.log(`PASS ${account.name} sign-out.`);
      } catch {
        failures += 1;
        console.error(`FAIL ${account.name} sign-out (response details withheld).`);
      }
    }
  }
}

if (failures) process.exitCode = 1;
