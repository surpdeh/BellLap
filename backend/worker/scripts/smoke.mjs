// Live smoke test for the deployed bug-report Worker. Plain Node 22, no dependencies.
// Files one real `field-report` issue from a small synthetic trace; close it afterwards (see backend/README.md).

const required = ['WORKER_URL', 'FIREBASE_API_KEY', 'FIREBASE_PROJECT_NUMBER', 'FIREBASE_APP_ID', 'APPCHECK_DEBUG_TOKEN'];
const missing = required.filter((name) => !process.env[name]);
if (missing.length > 0) {
  console.error(`Missing env: ${missing.join(', ')}`);
  process.exit(2);
}
const env = /** @type {Record<string, string>} */ (process.env);
const workerUrl = env.WORKER_URL.replace(/\/+$/, '');

/**
 * @param {string} label
 * @param {Response} res
 * @param {number[]} expected
 * @returns {Promise<any>}
 */
async function expectStatus(label, res, expected) {
  const text = await res.text();
  if (!expected.includes(res.status)) {
    throw new Error(`${label}: HTTP ${res.status}, expected ${expected.join(' or ')}: ${text}`);
  }
  console.log(`${label}: HTTP ${res.status}`);
  return text ? JSON.parse(text) : null;
}

async function appCheckToken() {
  const url =
    `https://firebaseappcheck.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_NUMBER}` +
    `/apps/${env.FIREBASE_APP_ID}:exchangeDebugToken?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ debugToken: env.APPCHECK_DEBUG_TOKEN }),
  });
  const body = await expectStatus('exchange debug token', res, [200]);
  return /** @type {string} */ (body.token);
}

function syntheticTrace() {
  const start = Date.UTC(2026, 0, 1);
  const lines = [
    { t: start, type: 'SESSION_START', note: 'synthetic smoke-test trace, no real data' },
    { t: start + 1000, type: 'BELL', confidence: 0.97 },
    { t: start + 61000, type: 'FLAG' },
    { t: start + 62000, type: 'STOP' },
  ];
  return new TextEncoder().encode(lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
}

async function main() {
  const token = await appCheckToken();
  const headers = { 'Content-Type': 'application/json', 'X-Firebase-AppCheck': token };
  const trace = syntheticTrace();

  const created = await expectStatus(
    'create report',
    await fetch(`${workerUrl}/v1/reports`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        installId: crypto.randomUUID(),
        appVersion: 'smoke-test',
        device: `node ${process.version}`,
        description: 'Smoke test of the bug-report Worker. Safe to close.',
        attachments: [{ name: 'trace-smoke.jsonl', kind: 'trace', contentType: 'application/x-ndjson', size: trace.byteLength }],
      }),
    }),
    [201],
  );
  console.log(`reportId: ${created.reportId}`);

  for (const upload of created.uploads) {
    const res = await fetch(upload.url, { method: upload.method, headers: upload.headers, body: trace });
    await expectStatus(`upload ${upload.name}`, res, [200]);
  }

  const submitUrl = `${workerUrl}/v1/reports/${created.reportId}/submit`;
  const first = await expectStatus('submit', await fetch(submitUrl, { method: 'POST', headers }), [201]);
  console.log(`issue: ${first.issue.url}`);

  const second = await expectStatus('submit again', await fetch(submitUrl, { method: 'POST', headers }), [200]);
  if (second.issue.number !== first.issue.number) {
    throw new Error(`retry filed a different issue: #${second.issue.number} vs #${first.issue.number}`);
  }
  console.log(`idempotent: retry returned the same issue #${second.issue.number}`);
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
