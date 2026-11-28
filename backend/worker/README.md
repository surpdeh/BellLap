# belllap-bugreport Worker

Turns in-app bug reports into GitHub issues. Setup, the API contract and the smoke test are in [`../README.md`](../README.md).

## Commands

```bash
npm ci
npm run typecheck   # tsc over src/ and test/, then scripts/ with Node types
npm test            # vitest in the Workers runtime (Miniflare R2 and rate-limit bindings)
npm run deploy      # wrangler deploy; CI does this on push to main
```

Use Node 22 or later. If `npm install` crashes with `Cannot read properties of null (reading 'edgesOut')`, that is an npm 10 bug with Vitest's peer dependencies; run `npx npm@11 install` to change dependencies. `npm ci` from the lockfile works on npm 10.

## Layout

| File | Role |
|---|---|
| `src/index.ts` | Router: two `POST` routes, 404 and 405, config check |
| `src/handlers.ts` | Create and submit, the report state transitions |
| `src/caps.ts` | Size, count and type caps per attachment kind. The single source of truth |
| `src/schema.ts` | zod request schemas, driven by `caps.ts` |
| `src/manifest.ts` | Report state in R2 at `reports/<reportId>/manifest.json` |
| `src/integrity.ts` | Firebase App Check token verification |
| `src/presign.ts` | Presigned R2 `PUT` URLs that sign `Content-Type` and `Content-Length` |
| `src/github.ts` | GitHub App JWT, down-scoped installation token, issue creation |
| `src/issue.ts` | Pure issue title and body rendering |
| `src/env.ts` | Bindings, vars and secrets; `loadConfig` fails closed |
| `scripts/smoke.mjs` | Live smoke test against the deployed Worker |

## Tests

Tests call the Worker's `fetch` export in the Workers runtime with Miniflare's R2 binding. Outbound `fetch` (the App Check JWKS and GitHub) is stubbed with `vi.spyOn(globalThis, 'fetch')`. RSA keys for App Check tokens and the GitHub App are generated per run with jose. No real credentials are used.

Miniflare's rate-limit binding counts in windows aligned to the wall clock, so a window can roll over during a test. The over-limit tests inject a counting limiter through `env`. The happy path runs through the real bindings.
