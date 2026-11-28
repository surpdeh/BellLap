# Bug-report backend (F23)

The app never holds a GitHub token. It sends a bug report to a Cloudflare Worker. The Worker checks that the request comes from a genuine install, hands out short-lived upload URLs for the media, and files a GitHub issue labelled `field-report` once the uploads are in.

- **Code:** [`worker/`](worker/). TypeScript, tested with Vitest in the Workers runtime (Miniflare R2 and rate-limit bindings).
- **CI:** [`.github/workflows/worker.yml`](../.github/workflows/worker.yml). It runs typecheck and tests on PRs that touch `backend/`, and deploys on push to `main`.
- **Media stays private.** Attachments go to the private R2 bucket `belllap-reports`. Issues link to the Cloudflare dashboard, which needs a Cloudflare login. The repo is public, so issues never carry a public or presigned download URL.

## How it works

1. The app calls `POST /v1/reports` with an App Check token and a JSON description of the report.
2. The Worker verifies the token, rate-limits, validates sizes and types against [`caps.ts`](worker/src/caps.ts), and writes `reports/<reportId>/manifest.json` to R2.
3. It answers with one presigned `PUT` URL per attachment. Each URL is valid for 15 minutes and signs the exact `Content-Type` and `Content-Length`, so R2 refuses any other size or type.
4. The app uploads each file straight to R2.
5. The app calls `POST /v1/reports/<reportId>/submit`. The Worker checks every upload is present at its declared size, claims the report, and creates the issue with a GitHub App installation token scoped to Issues on this repo only.
6. A repeated submit returns the same issue. It never files a second one.

## One-time setup

Do these once, in order. Each step names the value it produces and where that value goes.

### 1. GitHub App

1. GitHub → Settings → Developer settings → GitHub Apps → **New GitHub App**.
2. Name it (for example `belllap-bugreport`). Any homepage URL will do.
3. Under **Webhook**, clear **Active**. The App receives no events.
4. Under **Repository permissions**, set **Issues: Read and write**. Leave every other permission at **No access**. GitHub adds **Metadata: Read-only** itself; that is expected.
5. Under **Where can this GitHub App be installed?**, choose **Only on this account**. Create the App.
6. Note the **App ID** at the top of the App's page. This is `GITHUB_APP_ID`.
7. **Install App** → the `surpdeh` account → **Only select repositories** → `belllap`. The URL of the installation page ends in the installation ID (`.../installations/<id>`). This is `GITHUB_INSTALLATION_ID`.
8. On the App's page, **Generate a private key**. GitHub downloads a PKCS#1 PEM. jose needs PKCS#8, so convert it:

   ```bash
   openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem
   ```

   `key-pkcs8.pem` starts with `-----BEGIN PRIVATE KEY-----`. It becomes `GITHUB_APP_PRIVATE_KEY`. Delete both files once the secret is stored.

The Worker also narrows each installation token to `issues: write` on `belllap`, so a leaked token can do nothing else.

### 2. The `field-report` label

```bash
gh label create field-report --repo surpdeh/belllap --color D93F0B \
  --description "Filed from the in-app bug report"
```

Issue creation fails if the App cannot apply the label, so create it before the first report.

### 3. R2 bucket and upload keys

```bash
cd backend/worker
npx wrangler login
npx wrangler r2 bucket create belllap-reports
```

- New buckets are private. Leave **Public access** off: do not enable the `r2.dev` URL and do not attach a custom domain.
- The bucket needs no CORS policy. The Android client is not a browser.

The presigned URLs need an S3 key pair, which is separate from any Cloudflare API token:

1. Dashboard → R2 → **Manage API tokens** → **Create API token**.
2. Permission **Object Read & Write**. Under **Specify bucket(s)**, apply it to `belllap-reports` only.
3. Copy the **Access Key ID** (`R2_ACCESS_KEY_ID`) and the **Secret Access Key** (`R2_SECRET_ACCESS_KEY`). The secret is shown once.

### 4. Firebase App Check

App Check proves a request comes from the genuine BellLap app on a real device.

1. In the Firebase project, add the Android app (`applicationId` from `app/build.gradle.kts`) if it is not there yet.
2. Firebase console → **App Check** → **Apps** → the Android app → register the **Play Integrity** provider. It needs the SHA-256 fingerprint of the release signing key (and of the Play app signing key once the app is on Play).
3. Project settings → General gives the **Project number** (digits only) and the Android **App ID** (`1:<number>:android:<hex>`).
4. Put both in [`worker/wrangler.jsonc`](worker/wrangler.jsonc) under `vars` as `FIREBASE_PROJECT_NUMBER` and `FIREBASE_APP_ID`, and commit the change. They are identifiers, not secrets. While either is empty, the Worker answers every request with `503 not_configured`.
5. For the smoke test, register a debug token: **App Check** → **Apps** → the Android app → overflow menu → **Manage debug tokens** → **Add debug token**. Keep the token private, because it passes App Check from anywhere. Delete it when the smoke test is done.

The Worker accepts only RS256 tokens signed by a key in `https://firebaseappcheck.googleapis.com/v1/jwks`, with issuer `https://firebaseappcheck.googleapis.com/<project number>`, audience `projects/<project number>` and subject equal to the App ID. There is no bypass and no dev mode.

### 5. Worker secrets

From `backend/worker`, after `npx wrangler login`:

```bash
npx wrangler secret put GITHUB_APP_ID
npx wrangler secret put GITHUB_INSTALLATION_ID
npx wrangler secret put GITHUB_APP_PRIVATE_KEY < key-pkcs8.pem
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
```

`wrangler secret put` creates the Worker on first use if it does not exist yet. The Worker fails closed: if any secret or var is missing or empty, it answers `503 {"error":"not_configured"}`.

The rate limits live in `wrangler.jsonc` (`ratelimits`): 3 requests per 60 s per install ID and 20 per 60 s per client IP.

### 6. CI deploy secrets

The `worker-deploy` job runs on push to `main`. It needs two repository secrets (Settings → Secrets and variables → Actions):

- `CLOUDFLARE_API_TOKEN`: a Cloudflare API token from the **Edit Cloudflare Workers** template, limited to this account. It must include **Workers Scripts: Edit** and **Workers R2 Storage: Edit** (for the R2 binding).
- `CLOUDFLARE_ACCOUNT_ID`: `8e33116347c6438304959552670c559c`.

```bash
gh secret set CLOUDFLARE_API_TOKEN --repo surpdeh/belllap
gh secret set CLOUDFLARE_ACCOUNT_ID --repo surpdeh/belllap --body 8e33116347c6438304959552670c559c
```

Until `CLOUDFLARE_API_TOKEN` is set, the deploy job skips and says so in the step summary, so `main` stays green. The tests still run.

## API contract (for the F22 client)

Base URL: the Worker's URL, for example `https://belllap-bugreport.<subdomain>.workers.dev`. Every response is JSON. A failure is `{"error": "<code>", ...}`.

Both endpoints require the header `X-Firebase-AppCheck: <App Check token>`.

### `POST /v1/reports`

Request body, at most 64 KiB:

```json
{
  "installId": "6f1c2a9e-0b7d-4e8f-9a3c-1d2e3f4a5b6c",
  "appVersion": "0.4.0 (42)",
  "device": "Google Pixel 8 / Android 16",
  "description": "Bell detected late on lap 12",
  "attachments": [
    { "name": "capabilities.json", "kind": "capabilities", "contentType": "application/json", "size": 2311 },
    { "name": "trace-0.jsonl", "kind": "trace", "contentType": "application/x-ndjson", "size": 81234 }
  ]
}
```

- `installId` is a UUID, stable per install. `appVersion` is 1 to 32 characters, `device` 1 to 128 and `description` 1 to 8000.
- `name` matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, is unique in the report and is not `manifest.json`.
- `size` is the exact byte count of the file to upload.
- `attachments` may be empty. Caps per kind, from [`caps.ts`](worker/src/caps.ts):

| Kind | Max size | Max count | Content types |
|---|---|---|---|
| `capabilities` | 1 MiB | 1 | `application/json` |
| `trace` | 10 MiB | 8 | `application/json`, `application/x-ndjson`, `text/plain`, `application/zip`, `application/gzip` |
| `bell` | 10 MiB | 1 | `audio/wav`, `audio/mp4`, `audio/ogg` |
| `video` | 100 MiB | 2 | `video/mp4` |

At most 12 attachments and 200 MiB in total.

Response `201`:

```json
{
  "reportId": "0b7e9a4e-6d2f-4c1a-9f7e-2a1b3c4d5e6f",
  "uploads": [
    {
      "name": "trace-0.jsonl",
      "method": "PUT",
      "url": "https://<account>.r2.cloudflarestorage.com/belllap-reports/reports/<reportId>/trace-0.jsonl?X-Amz-...",
      "headers": { "Content-Type": "application/x-ndjson", "Content-Length": "81234" },
      "expiresAt": "2026-11-28T10:15:00.000Z"
    }
  ]
}
```

Upload each file with exactly that method, URL and those headers, and a body of exactly the declared size. Any other size or content type fails with `403` from R2. The URL expires at `expiresAt` (15 minutes).

### `POST /v1/reports/<reportId>/submit`

No body. Safe to retry: the outcome depends on the stored state of the report, not on how often the client calls.

| Status | Body | Client action |
|---|---|---|
| `201` | `{"issue": {"number": 123, "url": "https://github.com/..."}}` | Done. |
| `200` | Same as `201` | Already filed. Done. |
| `409` | `{"error": "uploads_missing", "missing": ["..."]}` | Upload the named files, then submit again. |
| `422` | `{"error": "size_mismatch", "names": ["..."]}` | The Worker deleted those objects. Re-upload them while the URLs are valid, otherwise start a new report. |
| `409` | `{"error": "in_progress"}` | Another submit is running. Retry after a few seconds. A claim older than 5 minutes is taken over. |
| `502` | `{"error": "github_failed"}` | Retry with backoff. Nothing was filed. |
| `404` | `{"error": "not_found"}` | Unknown report. Start a new one. |

### Errors on both endpoints

| Status | `error` | Meaning |
|---|---|---|
| `400` | `invalid_request` | Validation failed. `issues` lists `{path, message}` for each problem. |
| `401` | `integrity_failed` | App Check token missing or invalid. |
| `413` | `payload_too_large` | Request body over 64 KiB. |
| `429` | `rate_limited` | Over the per-install or per-IP limit. Back off for a minute. |
| `503` | `not_configured` | The Worker is missing a secret or var. |
| `404` / `405` | `not_found` / `method_not_allowed` | Unknown path, or not `POST`. |

## Fetching private media

Each issue lists its attachments with a link to the object in the Cloudflare dashboard, and one Wrangler command per file:

```bash
npx wrangler login     # once, with a Cloudflare account that can read the bucket
npx wrangler r2 object get belllap-reports/reports/<reportId>/trace-0.jsonl --remote --file trace-0.jsonl
```

The manifest sits beside the media at `reports/<reportId>/manifest.json`. Never attach the media to the issue, a PR or the repo: it may be real race footage or a user's recording (see CLAUDE.md, Data rules).

## Smoke test

[`worker/scripts/smoke.mjs`](worker/scripts/smoke.mjs) files one real issue against the deployed Worker. It uses plain Node 22 and needs no install. It exchanges the App Check debug token for a real App Check token, creates a report with a small synthetic trace, uploads it, submits, then submits again to show the retry returns the same issue.

```bash
cd backend/worker
WORKER_URL=https://belllap-bugreport.<subdomain>.workers.dev \
FIREBASE_API_KEY=<Web API key from Firebase project settings> \
FIREBASE_PROJECT_NUMBER=<project number> \
FIREBASE_APP_ID=<Android App ID> \
APPCHECK_DEBUG_TOKEN=<debug token from step 4> \
node scripts/smoke.mjs
```

Expected output ends with the issue URL and `idempotent: retry returned the same issue #<n>`. Then close the issue and delete its media:

```bash
gh api -X PATCH repos/surpdeh/belllap/issues/<n> -f state=closed
npx wrangler r2 object delete belllap-reports/reports/<reportId>/trace-smoke.jsonl --remote
npx wrangler r2 object delete belllap-reports/reports/<reportId>/manifest.json --remote
```

## Local development

See [`worker/README.md`](worker/README.md).
