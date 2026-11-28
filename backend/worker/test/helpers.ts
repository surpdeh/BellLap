import { env } from 'cloudflare:test';
import { SignJWT, exportJWK, exportPKCS8, generateKeyPair, jwtVerify, type CryptoKey } from 'jose';
import { vi } from 'vitest';
import worker from '../src/index';
import type { Env } from '../src/env';
import { APP_CHECK_JWKS_URL } from '../src/integrity';

export const PROJECT_NUMBER = '123456789012';
export const FIREBASE_APP_ID = '1:123456789012:android:0123456789abcdef';
export const GITHUB_APP_ID = '424242';
export const INSTALLATION_ID = '777';
const KID = 'test-key';

interface KeyPair {
  publicKey: CryptoKey;
  privateKey: CryptoKey;
}
const rsa = (): Promise<KeyPair> => generateKeyPair('RS256', { extractable: true });

export const appCheckKeys = await rsa();
export const strangerKeys = await rsa();
export const githubKeys = await rsa();
const githubPem = await exportPKCS8(githubKeys.privateKey);
const jwks = { keys: [{ ...(await exportJWK(appCheckKeys.publicKey)), kid: KID, alg: 'RS256', use: 'sig' }] };

export interface TokenOptions {
  audience?: string[];
  subject?: string;
  issuer?: string;
  expiresAt?: number;
  key?: CryptoKey;
}

export async function appCheckToken(opts: TokenOptions = {}): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({})
    .setProtectedHeader({ alg: 'RS256', typ: 'JWT', kid: KID })
    .setIssuer(opts.issuer ?? `https://firebaseappcheck.googleapis.com/${PROJECT_NUMBER}`)
    .setAudience(opts.audience ?? [`projects/${PROJECT_NUMBER}`, 'projects/belllap-test'])
    .setSubject(opts.subject ?? FIREBASE_APP_ID)
    .setIssuedAt(now - 10)
    .setExpirationTime(opts.expiresAt ?? now + 3600)
    .sign(opts.key ?? appCheckKeys.privateKey);
}

export function testEnv(overrides: Partial<Env> = {}): Env {
  return {
    ...env,
    FIREBASE_PROJECT_NUMBER: PROJECT_NUMBER,
    FIREBASE_APP_ID,
    GITHUB_APP_ID,
    GITHUB_INSTALLATION_ID: INSTALLATION_ID,
    GITHUB_APP_PRIVATE_KEY: githubPem,
    R2_ACCESS_KEY_ID: 'test-access-key',
    R2_SECRET_ACCESS_KEY: 'test-secret-key',
    ...overrides,
  };
}

export interface GitHubCall {
  url: string;
  body: Record<string, unknown>;
  headers: Headers;
}

export interface FakeNetwork {
  github: GitHubCall[];
  issueStatus: number;
  nextIssueNumber: number;
}

/** Stubs outbound fetch: the App Check JWKS and the two GitHub endpoints. Anything else fails the test. */
export function fakeNetwork(): FakeNetwork {
  const net: FakeNetwork = { github: [], issueStatus: 201, nextIssueNumber: 101 };
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const req = new Request(input, init);
    if (req.url === APP_CHECK_JWKS_URL) return Response.json(jwks);
    if (req.url.startsWith('https://api.github.com/')) {
      const body = (await req.json()) as Record<string, unknown>;
      net.github.push({ url: req.url, body, headers: req.headers });
      if (req.url.endsWith(`/app/installations/${INSTALLATION_ID}/access_tokens`)) {
        const jwt = (req.headers.get('Authorization') ?? '').replace(/^Bearer /, '');
        await jwtVerify(jwt, githubKeys.publicKey, { issuer: GITHUB_APP_ID, algorithms: ['RS256'] });
        return Response.json({ token: 'ghs_installation' }, { status: 201 });
      }
      if (req.url.endsWith('/repos/surpdeh/belllap/issues')) {
        if (net.issueStatus !== 201) return Response.json({ message: 'boom' }, { status: net.issueStatus });
        const number = net.nextIssueNumber++;
        return Response.json({ number, html_url: `https://github.com/surpdeh/belllap/issues/${number}` }, { status: 201 });
      }
    }
    throw new Error(`unexpected fetch ${req.method} ${req.url}`);
  });
  return net;
}

let ipCounter = 0;
/** A fresh client IP per call site keeps tests clear of each other's IP limit. */
export const freshIp = (): string => `198.51.100.${++ipCounter % 250}`;

export interface CallOptions {
  method?: string;
  body?: unknown;
  rawBody?: string;
  token?: string | null;
  ip?: string;
  env?: Env;
  headers?: Record<string, string>;
}

export async function call(path: string, opts: CallOptions = {}): Promise<Response> {
  const headers = new Headers(opts.headers);
  const token = opts.token === undefined ? await appCheckToken() : opts.token;
  if (token !== null) headers.set('X-Firebase-AppCheck', token);
  headers.set('CF-Connecting-IP', opts.ip ?? freshIp());
  const hasBody = opts.rawBody !== undefined || opts.body !== undefined;
  if (hasBody) headers.set('Content-Type', 'application/json');
  const req = new Request(`https://worker.test${path}`, {
    method: opts.method ?? 'POST',
    headers,
    ...(hasBody ? { body: opts.rawBody ?? JSON.stringify(opts.body) } : {}),
  });
  return worker.fetch(req as Request<unknown, IncomingRequestCfProperties>, opts.env ?? testEnv());
}

export const newInstallId = (): string => crypto.randomUUID();

export function reportBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    installId: newInstallId(),
    appVersion: '0.4.0 (42)',
    device: 'Google Pixel 8 / Android 16',
    description: 'Bell detected late on lap 12\nSecond line',
    attachments: [
      { name: 'capabilities.json', kind: 'capabilities', contentType: 'application/json', size: 120 },
      { name: 'trace-0.jsonl', kind: 'trace', contentType: 'application/x-ndjson', size: 64 },
    ],
    ...overrides,
  };
}

/** A limiter that allows `limit` calls per key, for deterministic rate-limit tests. */
export function countingLimiter(limit: number): RateLimit {
  const counts = new Map<string, number>();
  return {
    async limit({ key }) {
      const n = (counts.get(key) ?? 0) + 1;
      counts.set(key, n);
      return { success: n <= limit };
    },
  };
}
