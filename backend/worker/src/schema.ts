import { z } from 'zod';
import { KIND_NAMES, MAX_ATTACHMENTS, MAX_DESCRIPTION, MAX_TOTAL_BYTES, kindCaps, type AttachmentKind } from './caps';
import { MANIFEST_NAME } from './manifest';

export const ATTACHMENT_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export const AttachmentSpec = z
  .object({
    name: z
      .string()
      .regex(ATTACHMENT_NAME)
      .refine((n) => n !== MANIFEST_NAME, { message: `"${MANIFEST_NAME}" is reserved` }),
    kind: z.enum(KIND_NAMES),
    contentType: z.string().max(128),
    size: z.number().int().min(1),
  })
  .superRefine((a, ctx) => {
    const caps = kindCaps(a.kind);
    if (a.size > caps.maxBytes) {
      ctx.addIssue({ code: 'custom', path: ['size'], message: `${a.kind} attachments are at most ${caps.maxBytes} bytes` });
    }
    if (!caps.contentTypes.includes(a.contentType)) {
      ctx.addIssue({
        code: 'custom',
        path: ['contentType'],
        message: `${a.kind} attachments must be one of ${caps.contentTypes.join(', ')}`,
      });
    }
  });
export type AttachmentSpec = z.infer<typeof AttachmentSpec>;

export const CreateReportRequest = z
  .object({
    installId: z.uuid(),
    appVersion: z.string().min(1).max(32),
    device: z.string().min(1).max(128),
    description: z.string().min(1).max(MAX_DESCRIPTION),
    attachments: z.array(AttachmentSpec).max(MAX_ATTACHMENTS),
  })
  .superRefine((r, ctx) => {
    const counts = new Map<AttachmentKind, number>();
    const seen = new Set<string>();
    let total = 0;
    r.attachments.forEach((a, i) => {
      counts.set(a.kind, (counts.get(a.kind) ?? 0) + 1);
      if (seen.has(a.name)) {
        ctx.addIssue({ code: 'custom', path: ['attachments', i, 'name'], message: `duplicate name ${a.name}` });
      }
      seen.add(a.name);
      total += a.size;
    });
    for (const [kind, count] of counts) {
      const { maxCount } = kindCaps(kind);
      if (count > maxCount) {
        ctx.addIssue({ code: 'custom', path: ['attachments'], message: `at most ${maxCount} ${kind} attachment(s)` });
      }
    }
    if (total > MAX_TOTAL_BYTES) {
      ctx.addIssue({ code: 'custom', path: ['attachments'], message: `attachments total at most ${MAX_TOTAL_BYTES} bytes` });
    }
  });
export type CreateReportRequest = z.infer<typeof CreateReportRequest>;

export interface FlatIssue {
  path: string;
  message: string;
}

export const flattenIssues = (error: z.ZodError): FlatIssue[] =>
  error.issues.map((i) => ({ path: i.path.map(String).join('.'), message: i.message }));
