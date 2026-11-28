import { describe, expect, it } from 'vitest';
import { presignPut } from '../src/presign';

const r2 = { accountId: 'acct123', bucket: 'belllap-reports', accessKeyId: 'AKID', secretAccessKey: 'SECRET' };
const target = { name: 'trace-0.jsonl', key: 'reports/r1/trace-0.jsonl', contentType: 'application/x-ndjson', size: 2048 };
const now = new Date('2026-11-28T10:00:00.000Z');

describe('presignPut', () => {
  it('signs content-length, content-type and host into a 900 s URL', async () => {
    const ticket = await presignPut(r2, target, now);
    const url = new URL(ticket.url);
    expect(url.origin).toBe('https://acct123.r2.cloudflarestorage.com');
    expect(url.pathname).toBe('/belllap-reports/reports/r1/trace-0.jsonl');
    expect(url.searchParams.get('X-Amz-Expires')).toBe('900');
    expect(url.searchParams.get('X-Amz-SignedHeaders')).toBe('content-length;content-type;host');
    expect(url.searchParams.get('X-Amz-Algorithm')).toBe('AWS4-HMAC-SHA256');
    expect(url.searchParams.get('X-Amz-Credential')).toBe('AKID/20261128/auto/s3/aws4_request');
    expect(url.searchParams.get('X-Amz-Date')).toBe('20261128T100000Z');
    expect(url.searchParams.get('X-Amz-Signature')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('returns the headers the client must send and the expiry', async () => {
    const ticket = await presignPut(r2, target, now);
    expect(ticket).toMatchObject({
      name: 'trace-0.jsonl',
      method: 'PUT',
      headers: { 'Content-Type': 'application/x-ndjson', 'Content-Length': '2048' },
      expiresAt: '2026-11-28T10:15:00.000Z',
    });
  });

  it('gives a different signature for a different declared size', async () => {
    const a = new URL((await presignPut(r2, target, now)).url).searchParams.get('X-Amz-Signature');
    const b = new URL((await presignPut(r2, { ...target, size: 2049 }, now)).url).searchParams.get('X-Amz-Signature');
    expect(a).not.toBe(b);
  });
});
