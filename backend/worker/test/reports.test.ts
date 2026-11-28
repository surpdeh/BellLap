import { env } from 'cloudflare:test';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_DESCRIPTION, MAX_REQUEST_BYTES } from '../src/caps';
import { attachmentKey, loadManifest, manifestKey, saveManifest, withStatus, type ReportManifest } from '../src/manifest';
import {
  FIREBASE_APP_ID,
  appCheckToken,
  call,
  countingLimiter,
  fakeNetwork,
  newInstallId,
  reportBody,
  strangerKeys,
  testEnv,
  type FakeNetwork,
} from './helpers';

interface Created {
  reportId: string;
  uploads: { name: string; method: string; url: string; headers: Record<string, string>; expiresAt: string }[];
}

let net: FakeNetwork;
beforeEach(() => {
  net = fakeNetwork();
});
afterEach(() => {
  vi.restoreAllMocks();
});

async function create(body = reportBody()): Promise<Created> {
  const res = await call('/v1/reports', { body });
  expect(res.status).toBe(201);
  return res.json<Created>();
}

async function uploadAll(reportId: string, body = reportBody()): Promise<void> {
  const attachments = body.attachments as { name: string; size: number }[];
  for (const a of attachments) await env.REPORTS.put(attachmentKey(reportId, a.name), new Uint8Array(a.size));
}

const issueCalls = (): FakeNetwork['github'] => net.github.filter((c) => c.url.endsWith('/issues'));

describe('happy path', () => {
  it('creates, accepts uploads, files one issue and stays idempotent', async () => {
    const body = reportBody();
    const created = await create(body);
    expect(created.uploads.map((u) => u.name)).toEqual(['capabilities.json', 'trace-0.jsonl']);
    expect(created.uploads[0]).toMatchObject({
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Content-Length': '120' },
    });
    expect(created.uploads[0]?.url).toContain(`/belllap-reports/reports/${created.reportId}/capabilities.json?`);

    const stored = await loadManifest(env.REPORTS, created.reportId);
    expect(stored?.manifest.status).toEqual({ state: 'awaiting_uploads' });

    await uploadAll(created.reportId, body);
    const first = await call(`/v1/reports/${created.reportId}/submit`);
    expect(first.status).toBe(201);
    const { issue } = await first.json<{ issue: { number: number; url: string } }>();
    expect(issue).toEqual({ number: 101, url: 'https://github.com/surpdeh/belllap/issues/101' });

    const tokenCall = net.github.find((c) => c.url.includes('/access_tokens'));
    expect(tokenCall?.body).toEqual({ repositories: ['belllap'], permissions: { issues: 'write' } });
    const [issueCall, ...extra] = issueCalls();
    expect(extra).toHaveLength(0);
    expect(issueCall?.body.labels).toEqual(['field-report']);
    expect(issueCall?.body.title).toBe('Field report: Bell detected late on lap 12');
    expect(issueCall?.body.body).toContain(created.reportId);
    expect(issueCall?.headers.get('Authorization')).toBe('Bearer ghs_installation');
    expect(issueCall?.headers.get('X-GitHub-Api-Version')).toBe('2022-11-28');
    expect(issueCall?.headers.get('User-Agent')).toBe('belllap-bugreport-worker');

    expect((await loadManifest(env.REPORTS, created.reportId))?.manifest.status).toEqual({ state: 'filed', issue });

    const again = await call(`/v1/reports/${created.reportId}/submit`);
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ issue });
    expect(issueCalls()).toHaveLength(1);
  });

  it('accepts a report with no attachments', async () => {
    const created = await create(reportBody({ attachments: [] }));
    expect(created.uploads).toEqual([]);
    expect((await call(`/v1/reports/${created.reportId}/submit`)).status).toBe(201);
  });
});

describe('integrity', () => {
  const now = Math.floor(Date.now() / 1000);
  const cases: [string, () => Promise<string | null>][] = [
    ['no token', async () => null],
    ['garbage token', async () => 'not-a-jwt'],
    ['bad signature', () => appCheckToken({ key: strangerKeys.privateKey })],
    ['wrong audience', () => appCheckToken({ audience: ['projects/999'] })],
    ['wrong issuer', () => appCheckToken({ issuer: 'https://firebaseappcheck.googleapis.com/999' })],
    ['wrong sub', () => appCheckToken({ subject: `${FIREBASE_APP_ID}x` })],
    ['expired', () => appCheckToken({ expiresAt: now - 3600 })],
  ];
  for (const [label, token] of cases) {
    it(`rejects ${label} with 401 on both endpoints`, async () => {
      const t = await token();
      const res = await call('/v1/reports', { body: reportBody(), token: t });
      expect(res.status).toBe(401);
      expect(await res.json()).toEqual({ error: 'integrity_failed' });
      const sub = await call(`/v1/reports/${crypto.randomUUID()}/submit`, { token: t });
      expect(sub.status).toBe(401);
    });
  }
});

describe('caps', () => {
  const MiB = 1024 * 1024;
  const att = (name: string, kind: string, contentType: string, size: number) => ({ name, kind, contentType, size });
  const cases: [string, Record<string, unknown>][] = [
    ['oversize video', { attachments: [att('v.mp4', 'video', 'video/mp4', 100 * MiB + 1)] }],
    ['oversize capabilities', { attachments: [att('c.json', 'capabilities', 'application/json', MiB + 1)] }],
    ['wrong content type', { attachments: [att('b.mp3', 'bell', 'audio/mpeg', 10)] }],
    ['unknown kind', { attachments: [att('x.bin', 'core-dump', 'application/octet-stream', 10)] }],
    ['zero size', { attachments: [att('t.txt', 'trace', 'text/plain', 0)] }],
    [
      'too many of a kind',
      { attachments: [att('b1.wav', 'bell', 'audio/wav', 10), att('b2.wav', 'bell', 'audio/wav', 10)] },
    ],
    [
      'duplicate names',
      { attachments: [att('t.txt', 'trace', 'text/plain', 10), att('t.txt', 'trace', 'text/plain', 10)] },
    ],
    [
      'total over the cap',
      {
        attachments: [
          att('v1.mp4', 'video', 'video/mp4', 100 * MiB),
          att('v2.mp4', 'video', 'video/mp4', 100 * MiB),
          att('b.wav', 'bell', 'audio/wav', 1),
        ],
      },
    ],
    [
      'too many attachments',
      {
        attachments: [
          ...Array.from({ length: 8 }, (_, i) => att(`t${i}.txt`, 'trace', 'text/plain', 1)),
          att('c.json', 'capabilities', 'application/json', 1),
          att('b.wav', 'bell', 'audio/wav', 1),
          att('v1.mp4', 'video', 'video/mp4', 1),
          att('v2.mp4', 'video', 'video/mp4', 1),
          att('v3.mp4', 'video', 'video/mp4', 1),
        ],
      },
    ],
    ['bad name chars', { attachments: [att('../etc.txt', 'trace', 'text/plain', 10)] }],
    ['name with a space', { attachments: [att('my trace.txt', 'trace', 'text/plain', 10)] }],
    ['name too long', { attachments: [att(`${'a'.repeat(65)}`, 'trace', 'text/plain', 10)] }],
    ['reserved manifest name', { attachments: [att('manifest.json', 'trace', 'application/json', 10)] }],
    ['description too long', { description: 'x'.repeat(MAX_DESCRIPTION + 1) }],
    ['empty description', { description: '' }],
    ['installId not a uuid', { installId: 'abc' }],
    ['appVersion too long', { appVersion: '1'.repeat(33) }],
  ];
  for (const [label, overrides] of cases) {
    it(`rejects ${label} with 400`, async () => {
      const res = await call('/v1/reports', { body: reportBody(overrides) });
      expect(res.status).toBe(400);
      const body = await res.json<{ error: string; issues: { path: string; message: string }[] }>();
      expect(body.error).toBe('invalid_request');
      expect(body.issues.length).toBeGreaterThan(0);
    });
  }

  it('accepts every kind at its cap', async () => {
    const res = await call('/v1/reports', {
      body: reportBody({
        description: 'd'.repeat(MAX_DESCRIPTION),
        attachments: [
          att('c.json', 'capabilities', 'application/json', MiB),
          att('b.ogg', 'bell', 'audio/ogg', 10 * MiB),
          att('v1.mp4', 'video', 'video/mp4', 90 * MiB),
          att('v2.mp4', 'video', 'video/mp4', 90 * MiB),
          att('t.gz', 'trace', 'application/gzip', 9 * MiB),
        ],
      }),
    });
    expect(res.status).toBe(201);
  });

  it('rejects malformed JSON with 400', async () => {
    const res = await call('/v1/reports', { rawBody: '{"installId":' });
    expect(res.status).toBe(400);
    expect((await res.json<{ error: string }>()).error).toBe('invalid_request');
  });

  it('rejects a body over 64 KiB with 413', async () => {
    const res = await call('/v1/reports', { rawBody: JSON.stringify(reportBody({ description: 'x'.repeat(MAX_REQUEST_BYTES) })) });
    expect(res.status).toBe(413);
  });

  it('rejects a declared Content-Length over 64 KiB with 413', async () => {
    const res = await call('/v1/reports', {
      body: reportBody(),
      headers: { 'Content-Length': String(MAX_REQUEST_BYTES + 1) },
    });
    expect(res.status).toBe(413);
  });
});

describe('rate limits', () => {
  // Miniflare's ratelimit binding uses wall-clock-aligned windows, so a window can roll over mid-test.
  // The over-limit tests inject a counting limiter; the happy path above exercises the real bindings.
  it('rejects the fourth report from one install with 429', async () => {
    const e = testEnv({ INSTALL_LIMITER: countingLimiter(3) });
    const installId = newInstallId();
    for (let i = 0; i < 3; i++) {
      expect((await call('/v1/reports', { body: reportBody({ installId }), env: e })).status).toBe(201);
    }
    const res = await call('/v1/reports', { body: reportBody({ installId }), env: e });
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ error: 'rate_limited' });
    expect((await call('/v1/reports', { body: reportBody(), env: e })).status).toBe(201);
  });

  it('limits submit by the install id stored in the manifest', async () => {
    const e = testEnv({ INSTALL_LIMITER: countingLimiter(1) });
    const res = await call('/v1/reports', { body: reportBody({ attachments: [] }), env: e });
    const { reportId } = await res.json<Created>();
    expect((await call(`/v1/reports/${reportId}/submit`, { env: e })).status).toBe(429);
  });

  it('applies the IP limit before parsing or verifying anything', async () => {
    const e = testEnv({ IP_LIMITER: countingLimiter(0) });
    const res = await call('/v1/reports', { rawBody: 'nonsense', token: null, env: e });
    expect(res.status).toBe(429);
  });
});

describe('submit', () => {
  async function createdWithManifest(): Promise<{ reportId: string; body: Record<string, unknown> }> {
    const body = reportBody();
    const { reportId } = await create(body);
    return { reportId, body };
  }

  async function setStatus(reportId: string, status: ReportManifest['status']): Promise<void> {
    const loaded = await loadManifest(env.REPORTS, reportId);
    if (!loaded) throw new Error('no manifest');
    await saveManifest(env.REPORTS, withStatus(loaded.manifest, status));
  }

  it('reports missing uploads with 409 and their names', async () => {
    const { reportId } = await createdWithManifest();
    await env.REPORTS.put(attachmentKey(reportId, 'capabilities.json'), new Uint8Array(120));
    const res = await call(`/v1/reports/${reportId}/submit`);
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ error: 'uploads_missing', missing: ['trace-0.jsonl'] });
    expect(net.github).toHaveLength(0);
  });

  it('deletes a wrong-size upload and answers 422', async () => {
    const { reportId } = await createdWithManifest();
    await env.REPORTS.put(attachmentKey(reportId, 'capabilities.json'), new Uint8Array(120));
    await env.REPORTS.put(attachmentKey(reportId, 'trace-0.jsonl'), new Uint8Array(65));
    const res = await call(`/v1/reports/${reportId}/submit`);
    expect(res.status).toBe(422);
    expect(await res.json()).toEqual({ error: 'size_mismatch', names: ['trace-0.jsonl'] });
    expect(await env.REPORTS.head(attachmentKey(reportId, 'trace-0.jsonl'))).toBeNull();
    expect(await env.REPORTS.head(attachmentKey(reportId, 'capabilities.json'))).not.toBeNull();
    expect((await loadManifest(env.REPORTS, reportId))?.manifest.status).toEqual({ state: 'awaiting_uploads' });
  });

  it('answers 409 while a fresh submit is in progress', async () => {
    const { reportId, body } = await createdWithManifest();
    await uploadAll(reportId, body);
    await setStatus(reportId, { state: 'submitting', since: new Date(Date.now() - 60_000).toISOString(), claim: 'other' });
    const res = await call(`/v1/reports/${reportId}/submit`);
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ error: 'in_progress' });
    expect(net.github).toHaveLength(0);
  });

  it('reclaims a stale submit and files the issue', async () => {
    const { reportId, body } = await createdWithManifest();
    await uploadAll(reportId, body);
    await setStatus(reportId, { state: 'submitting', since: new Date(Date.now() - 6 * 60_000).toISOString(), claim: 'other' });
    const res = await call(`/v1/reports/${reportId}/submit`);
    expect(res.status).toBe(201);
    expect(issueCalls()).toHaveLength(1);
  });

  it('answers 409 when it loses the claim race', async () => {
    const { reportId, body } = await createdWithManifest();
    await uploadAll(reportId, body);
    const real = env.REPORTS;
    // A rival writes the manifest between our read and our conditional claim.
    const bucket = {
      get: real.get.bind(real),
      put: real.put.bind(real),
      delete: real.delete.bind(real),
      head: async (key: string) => {
        const head = await real.head(key);
        if (key.endsWith('trace-0.jsonl')) await setStatus(reportId, { state: 'submitting', since: new Date().toISOString(), claim: 'rival' });
        return head;
      },
    } as unknown as R2Bucket;
    const res = await call(`/v1/reports/${reportId}/submit`, { env: testEnv({ REPORTS: bucket }) });
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ error: 'in_progress' });
    expect(net.github).toHaveLength(0);
  });

  it('answers 502 when GitHub fails and releases the claim', async () => {
    const { reportId, body } = await createdWithManifest();
    await uploadAll(reportId, body);
    net.issueStatus = 500;
    const res = await call(`/v1/reports/${reportId}/submit`);
    expect(res.status).toBe(502);
    expect((await res.json<{ error: string }>()).error).toBe('github_failed');
    expect((await loadManifest(env.REPORTS, reportId))?.manifest.status).toEqual({ state: 'awaiting_uploads' });

    net.issueStatus = 201;
    expect((await call(`/v1/reports/${reportId}/submit`)).status).toBe(201);
  });

  it('answers 404 for an unknown or malformed report id', async () => {
    expect((await call(`/v1/reports/${crypto.randomUUID()}/submit`)).status).toBe(404);
    const res = await call('/v1/reports/not-a-uuid/submit');
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: 'not_found' });
  });

  it('keeps the manifest at its fixed key', async () => {
    const { reportId } = await createdWithManifest();
    expect(await env.REPORTS.head(manifestKey(reportId))).not.toBeNull();
    expect(manifestKey(reportId)).toBe(`reports/${reportId}/manifest.json`);
  });
});

describe('routing and configuration', () => {
  it('answers 404 for an unknown route', async () => {
    const res = await call('/v1/nope');
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: 'not_found' });
  });

  it('answers 405 for the wrong method', async () => {
    const res = await call('/v1/reports', { method: 'GET' });
    expect(res.status).toBe(405);
    expect(res.headers.get('Allow')).toBe('POST');
    expect(await res.json()).toEqual({ error: 'method_not_allowed' });
  });

  const required = [
    'GITHUB_APP_ID',
    'GITHUB_INSTALLATION_ID',
    'GITHUB_APP_PRIVATE_KEY',
    'R2_ACCESS_KEY_ID',
    'R2_SECRET_ACCESS_KEY',
    'FIREBASE_PROJECT_NUMBER',
    'FIREBASE_APP_ID',
  ] as const;
  for (const name of required) {
    it(`fails closed with 503 when ${name} is missing`, async () => {
      const e = testEnv({ [name]: '' });
      const res = await call('/v1/reports', { body: reportBody(), env: e });
      expect(res.status).toBe(503);
      expect(await res.json()).toEqual({ error: 'not_configured' });
      expect((await call(`/v1/reports/${crypto.randomUUID()}/submit`, { env: e })).status).toBe(503);
    });
  }

  it('fails closed with 503 on the deployed defaults (no secrets set)', async () => {
    const res = await call('/v1/reports', { body: reportBody(), env });
    expect(res.status).toBe(503);
  });
});
