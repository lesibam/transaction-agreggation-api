#!/usr/bin/env node
/**
 * Mints a HS* JWT compatible with JwtTokenProvider (JJWT) for E2E tests.
 *
 * The HMAC algorithm is chosen from the secret length exactly like JJWT's
 * Keys.hmacShaKeyFor/MacAlgorithm.fromKey: >=64 bytes → HS512, >=48 → HS384,
 * >=32 → HS256. (The surefire/dev secret is 54 bytes → HS384.)
 *
 * Usage:
 *   APP_SECURITY_JWT_SECRET=... node scripts/mint-jwt.mjs
 *   E2E_CUSTOMER_ID=... E2E_ROLES=ADMIN node scripts/mint-jwt.mjs
 *
 * Prints the raw token to stdout. No dependencies — Node built-in crypto only.
 */
import crypto from 'node:crypto';

const secret = process.env.APP_SECURITY_JWT_SECRET
  || 'test-only-not-a-real-secret-0123456789abcdef0123456789';
const customerId = process.env.E2E_CUSTOMER_ID
  || '00000000-0000-0000-0000-000000000001';
const tenantId = process.env.E2E_TENANT_ID || 'tenant-1';
const roles = (process.env.E2E_ROLES || 'CUSTOMER')
  .split(',')
  .map((r) => r.trim())
  .filter((r) => r.length > 0);
const issuer = process.env.E2E_JWT_ISS || 'transact';
const audience = process.env.E2E_JWT_AUD || 'transact-api';
const ttlSeconds = Number(process.env.E2E_TTL_SECONDS || 3600);

const secretBytes = Buffer.byteLength(secret, 'utf8');
let alg;
let nodeAlg;
if (secretBytes * 8 >= 512) {
  alg = 'HS512';
  nodeAlg = 'sha512';
} else if (secretBytes * 8 >= 384) {
  alg = 'HS384';
  nodeAlg = 'sha384';
} else if (secretBytes * 8 >= 256) {
  alg = 'HS256';
  nodeAlg = 'sha256';
} else {
  throw new Error('APP_SECURITY_JWT_SECRET must be at least 32 bytes (256 bits)');
}

const now = Math.floor(Date.now() / 1000);
const b64url = (input) => Buffer.from(input).toString('base64url');

const header = { alg, typ: 'JWT' };
const payload = {
  sub: customerId,
  tenantId,
  roles,
  iss: issuer,
  aud: [audience],
  iat: now,
  exp: now + ttlSeconds,
};

const signingInput =
  `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
const signature = crypto
  .createHmac(nodeAlg, secret)
  .update(signingInput)
  .digest('base64url');

process.stdout.write(`${signingInput}.${signature}`);
