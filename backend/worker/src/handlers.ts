import { MAX_REQUEST_BYTES, SUBMIT_STALE_MS } from './caps';
import type { Config } from './env';
import { GitHubError, createIssue } from './github';
import { BodyTooLarge, fail, json, readBounded } from './http';
import { verifyAppCheck } from './integrity';
import { renderIssue } from './issue';
import { attachmentKey, loadManifest, saveManifest, withStatus, type ReportManifest } from './manifest';
import { presignPut } from './presign';
import { CreateReportRequest, flattenIssues } from './schema';

export const APP_CHECK_HEADER = 'X-Firebase-AppCheck';

/** Shared gate for both endpoints: the per-IP limit first (cheap), then App Check. */
async function gate(req: Request, cfg: Config): Promise<Response | null> {
  const ip = req.headers.get('CF-Connecting-IP') ?? 'unknown';
  if (!(await cfg.ipLimiter.limit({ key: ip })).success) return fail(429, 'rate_limited');
  if (!(await verifyAppCheck(req.headers.get(APP_CHECK_HEADER), cfg.firebase))) return fail(401, 'integrity_failed');
  return null;
}

const installAllowed = async (cfg: Config, installId: string): Promise<boolean> =>
  (await cfg.installLimiter.limit({ key: installId })).success;

export async function createReport(req: Request, cfg: Config, now: Date): Promise<Response> {
  const denied = await gate(req, cfg);
  if (denied) return denied;

  let raw: unknown;
  try {
    raw = JSON.parse(await readBounded(req, MAX_REQUEST_BYTES));
  } catch (e) {
    if (e instanceof BodyTooLarge) return fail(413, 'payload_too_large', { maxBytes: MAX_REQUEST_BYTES });
    return fail(400, 'invalid_request', { issues: [{ path: '', message: 'body is not valid JSON' }] });
  }
  const parsed = CreateReportRequest.safeParse(raw);
  if (!parsed.success) return fail(400, 'invalid_request', { issues: flattenIssues(parsed.error) });
  const body = parsed.data;

  if (!(await installAllowed(cfg, body.installId))) return fail(429, 'rate_limited');

  const reportId = crypto.randomUUID();
  const manifest: ReportManifest = {
    reportId,
    createdAt: now.toISOString(),
    installId: body.installId,
    appVersion: body.appVersion,
    device: body.device,
    description: body.description,
    attachments: body.attachments.map((a) => ({ ...a, key: attachmentKey(reportId, a.name) })),
    status: { state: 'awaiting_uploads' },
  };
  await saveManifest(cfg.reports, manifest);
  const uploads = await Promise.all(manifest.attachments.map((a) => presignPut(cfg.r2, a, now)));
  return json(201, { reportId, uploads });
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export async function submitReport(req: Request, cfg: Config, reportId: string, now: Date): Promise<Response> {
  const denied = await gate(req, cfg);
  if (denied) return denied;

  const loaded = UUID.test(reportId) ? await loadManifest(cfg.reports, reportId) : null;
  if (!loaded) return fail(404, 'not_found');
  const { manifest, etag } = loaded;
  if (!(await installAllowed(cfg, manifest.installId))) return fail(429, 'rate_limited');

  const { status } = manifest;
  if (status.state === 'filed') return json(200, { issue: status.issue });
  if (status.state === 'submitting' && now.getTime() - Date.parse(status.since) < SUBMIT_STALE_MS) {
    return fail(409, 'in_progress');
  }

  const heads = await Promise.all(manifest.attachments.map(async (a) => ({ a, head: await cfg.reports.head(a.key) })));
  const missing = heads.filter((h) => !h.head).map((h) => h.a.name);
  if (missing.length > 0) return fail(409, 'uploads_missing', { missing });
  const wrongSize = heads.filter((h) => h.head && h.head.size !== h.a.size).map((h) => h.a);
  if (wrongSize.length > 0) {
    await cfg.reports.delete(wrongSize.map((a) => a.key));
    return fail(422, 'size_mismatch', { names: wrongSize.map((a) => a.name) });
  }

  const claimed = withStatus(manifest, { state: 'submitting', since: now.toISOString(), claim: crypto.randomUUID() });
  const claimEtag = await saveManifest(cfg.reports, claimed, etag);
  if (claimEtag === null) return fail(409, 'in_progress');

  let issue;
  try {
    issue = await createIssue(cfg.github, renderIssue(manifest, cfg.r2), now);
  } catch (e) {
    await saveManifest(cfg.reports, withStatus(manifest, { state: 'awaiting_uploads' }), claimEtag).catch(() => null);
    const detail = e instanceof GitHubError ? { step: e.step, status: e.status } : {};
    return fail(502, 'github_failed', detail);
  }
  await saveManifest(cfg.reports, withStatus(manifest, { state: 'filed', issue }));
  return json(201, { issue });
}
