const MiB = 1024 * 1024;

export interface KindCaps {
  readonly maxBytes: number;
  readonly maxCount: number;
  readonly contentTypes: readonly string[];
}

export const ATTACHMENT_KINDS = {
  capabilities: { maxBytes: 1 * MiB, maxCount: 1, contentTypes: ['application/json'] },
  trace: {
    maxBytes: 10 * MiB,
    maxCount: 8,
    contentTypes: ['application/json', 'application/x-ndjson', 'text/plain', 'application/zip', 'application/gzip'],
  },
  bell: { maxBytes: 10 * MiB, maxCount: 1, contentTypes: ['audio/wav', 'audio/mp4', 'audio/ogg'] },
  video: { maxBytes: 100 * MiB, maxCount: 2, contentTypes: ['video/mp4'] },
} as const satisfies Record<string, KindCaps>;

export type AttachmentKind = keyof typeof ATTACHMENT_KINDS;
export const KIND_NAMES = Object.keys(ATTACHMENT_KINDS) as [AttachmentKind, ...AttachmentKind[]];

export const kindCaps = (kind: AttachmentKind): KindCaps => ATTACHMENT_KINDS[kind];

export const MAX_TOTAL_BYTES = 200 * MiB;
export const MAX_ATTACHMENTS = 12;
export const MAX_DESCRIPTION = 8000;
export const UPLOAD_URL_TTL_S = 900;
export const ISSUE_BODY_LIMIT = 65000;
export const MAX_REQUEST_BYTES = 64 * 1024;
export const SUBMIT_STALE_MS = 5 * 60 * 1000;
