import type { AttachmentSpec } from './schema';

export const MANIFEST_NAME = 'manifest.json';

export type ReportStatus =
  | { state: 'awaiting_uploads' }
  // `claim` is a nonce: R2 etags are content hashes, so two claims with the same `since` would otherwise both pass
  // the etagMatches check.
  | { state: 'submitting'; since: string; claim: string }
  | { state: 'filed'; issue: FiledIssue };

export interface FiledIssue {
  number: number;
  url: string;
}

export type StoredAttachment = AttachmentSpec & { key: string };

export interface ReportManifest {
  reportId: string;
  createdAt: string;
  installId: string;
  appVersion: string;
  device: string;
  description: string;
  attachments: StoredAttachment[];
  status: ReportStatus;
}

export const reportPrefix = (reportId: string): string => `reports/${reportId}/`;
export const manifestKey = (reportId: string): string => `${reportPrefix(reportId)}${MANIFEST_NAME}`;
export const attachmentKey = (reportId: string, name: string): string => `${reportPrefix(reportId)}${name}`;

export interface LoadedManifest {
  manifest: ReportManifest;
  etag: string;
}

export async function loadManifest(bucket: R2Bucket, reportId: string): Promise<LoadedManifest | null> {
  const obj = await bucket.get(manifestKey(reportId));
  if (!obj) return null;
  return { manifest: await obj.json<ReportManifest>(), etag: obj.etag };
}

/** Writes the manifest. With `etag`, only if the stored copy is unchanged; returns the new etag, or null on a lost race. */
export async function saveManifest(bucket: R2Bucket, manifest: ReportManifest, etag?: string): Promise<string | null> {
  const put = await bucket.put(manifestKey(manifest.reportId), JSON.stringify(manifest), {
    httpMetadata: { contentType: 'application/json' },
    ...(etag === undefined ? {} : { onlyIf: { etagMatches: etag } }),
  });
  return put ? put.etag : null;
}

export const withStatus = (manifest: ReportManifest, status: ReportStatus): ReportManifest => ({ ...manifest, status });
