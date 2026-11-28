import { describe, expect, it } from 'vitest';
import { ISSUE_BODY_LIMIT, MAX_ATTACHMENTS, MAX_DESCRIPTION } from '../src/caps';
import { renderIssue } from '../src/issue';
import type { ReportManifest, StoredAttachment } from '../src/manifest';

const r2 = { accountId: '8e33116347c6438304959552670c559c', bucket: 'belllap-reports' };
const reportId = '0b7e9a4e-6d2f-4c1a-9f7e-2a1b3c4d5e6f';

function manifest(overrides: Partial<ReportManifest> = {}): ReportManifest {
  return {
    reportId,
    createdAt: '2026-11-28T10:00:00.000Z',
    installId: 'deadbeef-1111-4222-8333-444455556666',
    appVersion: '0.4.0',
    device: 'Pixel 8',
    description: 'Bell missed',
    attachments: [
      { name: 'trace-0.jsonl', kind: 'trace', contentType: 'application/x-ndjson', size: 2048, key: `reports/${reportId}/trace-0.jsonl` },
    ],
    status: { state: 'awaiting_uploads' },
    ...overrides,
  };
}

const fenceOf = (body: string): string => /^(`{3,})text$/m.exec(body)?.[1] ?? '';

describe('renderIssue', () => {
  it('titles the issue with the first line, cut to 80 chars with an ellipsis', () => {
    expect(renderIssue(manifest({ description: '  Bell missed  \nmore' }), r2).title).toBe('Field report: Bell missed');
    const long = renderIssue(manifest({ description: 'a'.repeat(200) }), r2).title;
    expect(long).toBe(`Field report: ${'a'.repeat(79)}…`);
    expect(Array.from(long.replace('Field report: ', ''))).toHaveLength(80);
    expect(renderIssue(manifest({ description: 'b'.repeat(80) }), r2).title).toBe(`Field report: ${'b'.repeat(80)}`);
  });

  it('shows only the first 8 chars of the install id', () => {
    const { body } = renderIssue(manifest(), r2);
    expect(body).toContain('`deadbeef`');
    expect(body).not.toContain('1111-4222');
  });

  it('fences the description with a fence longer than any backtick run', () => {
    const description = 'Closing early ```` then\n```\n@surpdeh [click](https://evil.example) <img src=x>\n```';
    const { body } = renderIssue(manifest({ description }), r2);
    const fence = fenceOf(body);
    expect(fence.length).toBe(5);
    const start = body.indexOf(`${fence}text\n`) + fence.length + 5;
    const end = body.indexOf(`\n${fence}\n`, start);
    expect(body.slice(start, end)).toBe(description);
    expect(body.split('\n').filter((l) => l === fence)).toHaveLength(1);
  });

  it('keeps metadata cells from breaking out of their code spans or the table', () => {
    const { body } = renderIssue(manifest({ device: 'Pixel `x` | @surpdeh\n# heading', appVersion: '1.0`' }), r2);
    expect(body).toContain("| Device | `Pixel 'x' \\| @surpdeh # heading` |");
    expect(body).toContain("| App version | `1.0'` |");
  });

  it('links attachments to the private dashboard and gives wrangler fetch hints', () => {
    const { body } = renderIssue(manifest(), r2);
    expect(body).toContain(
      `[\`trace-0.jsonl\`](https://dash.cloudflare.com/${r2.accountId}/r2/default/buckets/belllap-reports/objects/reports%2F${reportId}%2Ftrace-0.jsonl/details)`,
    );
    expect(body).toContain(
      `npx wrangler r2 object get belllap-reports/reports/${reportId}/trace-0.jsonl --remote --file trace-0.jsonl`,
    );
    expect(body).toContain('| 2.0 KiB |');
  });

  it('says so when there are no attachments', () => {
    expect(renderIssue(manifest({ attachments: [] }), r2).body).toContain('### Attachments\n\nNone.');
  });

  it('stays under the GitHub body limit in the worst case, with no public or presigned URLs', () => {
    const attachments: StoredAttachment[] = Array.from({ length: MAX_ATTACHMENTS }, (_, i) => {
      const name = `${String(i).padStart(2, '0')}${'n'.repeat(62)}`;
      return { name, kind: 'trace', contentType: 'text/plain', size: 10 * 1024 * 1024, key: `reports/${reportId}/${name}` };
    });
    const description = '`'.repeat(MAX_DESCRIPTION - 1) + '\n';
    const { body } = renderIssue(manifest({ description, attachments, device: '|'.repeat(128), appVersion: 'v'.repeat(32) }), r2);
    expect(attachments[0]?.name).toHaveLength(64);
    expect(body.length).toBeLessThan(ISSUE_BODY_LIMIT);
    expect(body).not.toMatch(/r2\.dev|r2\.cloudflarestorage\.com|X-Amz-/i);
  });
});
