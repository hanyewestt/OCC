import { generateKeyPairSync, sign } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

const [outputDirectory] = process.argv.slice(2);
if (!outputDirectory) {
  throw new Error('Usage: node create_es256_auth_fixtures.mjs <output-directory>');
}

const issuer = 'http://jwks-test:8080/auth/v1';
const audience = 'authenticated';
const keyId = 'occ-ci-ephemeral-es256';
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicJwk = publicKey.export({ format: 'jwk' });
const jwks = {
  keys: [{ kty: publicJwk.kty, crv: publicJwk.crv, x: publicJwk.x, y: publicJwk.y,
    use: 'sig', alg: 'ES256', kid: keyId }],
};

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

function makeToken(role, subject) {
  const header = encode({ alg: 'ES256', typ: 'JWT', kid: keyId });
  const now = Math.floor(Date.now() / 1000);
  const payload = encode({ iss: issuer, aud: audience, sub: subject, iat: now, exp: now + 1800, occ_role: role });
  const content = `${header}.${payload}`;
  const signature = sign('sha256', Buffer.from(content), {
    key: privateKey,
    dsaEncoding: 'ieee-p1363',
  }).toString('base64url');
  return `${content}.${signature}`;
}

await mkdir(`${outputDirectory}/auth/v1/.well-known`, { recursive: true });
await writeFile(`${outputDirectory}/auth/v1/.well-known/jwks.json`, JSON.stringify(jwks), { mode: 0o644 });

const roleSubjects = {
  ADMIN: '10000000-0000-4000-8000-000000000001',
  CONTROLLER: '10000000-0000-4000-8000-000000000002',
  CAPTAIN: '10000000-0000-4000-8000-000000000003',
  UNASSIGNED: '10000000-0000-4000-8000-000000000004',
};
for (const [role, subject] of Object.entries(roleSubjects)) {
  const token = makeToken(role, subject);
  await writeFile(`${outputDirectory}/${role.toLowerCase()}.jwt`, `${token}\n`, { mode: 0o600 });
}

const invalid = makeToken('ADMIN', '00000000-0000-4000-8000-000000000999').split('.');
const signature = Buffer.from(invalid[2], 'base64url');
signature[0] ^= 0x01;
invalid[2] = signature.toString('base64url');
await writeFile(`${outputDirectory}/invalid-signature.jwt`, `${invalid.join('.')}\n`, { mode: 0o600 });

console.log('Generated one ephemeral ES256 JWKS and role-token set for the isolated CI auth test.');
