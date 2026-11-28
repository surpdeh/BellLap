import type { Config } from './env';
import type { ReportManifest } from './manifest';
import type { NewIssue } from './github';

const TITLE_MAX = 80;

// Control characters and newlines would break the title or a table row.
const oneLine = (s: string): string => s.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim();

function title(description: string): string {
  const first = description.split(/\r?\n/).map(oneLine).find((l) => l !== '') ?? '(no description)';
  const chars = Array.from(first);
  const text = chars.length > TITLE_MAX ? `${chars.slice(0, TITLE_MAX - 1).join('').trimEnd()}…` : first;
  return `Field report: ${text}`;
}

/** An inline code span safe inside a table cell: no backticks to close it early, pipes escaped for GFM tables. */
const cell = (s: string): string => `\`${oneLine(s).replace(/`/g, "'").replace(/\|/g, '\\|') || ' '}\``;

function fenced(text: string): string {
  const longestRun = Math.max(0, ...Array.from(text.matchAll(/`+/g), (m) => m[0].length));
  const fence = '`'.repeat(Math.max(3, longestRun + 1));
  return `${fence}text\n${text}\n${fence}`;
}

export function humanSize(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return unit === 0 ? `${value} B` : `${value.toFixed(1)} ${units[unit]}`;
}

export function dashboardUrl(r2: Pick<Config['r2'], 'accountId' | 'bucket'>, key: string): string {
  return `https://dash.cloudflare.com/${r2.accountId}/r2/default/buckets/${r2.bucket}/objects/${encodeURIComponent(key)}/details`;
}

export function renderIssue(m: ReportManifest, r2: Pick<Config['r2'], 'accountId' | 'bucket'>): NewIssue {
  const lines: string[] = [
    '| Field | Value |',
    '|---|---|',
    `| Report | ${cell(m.reportId)} |`,
    `| App version | ${cell(m.appVersion)} |`,
    `| Device | ${cell(m.device)} |`,
    `| Created | ${cell(m.createdAt)} |`,
    `| Install | ${cell(m.installId.slice(0, 8))} |`,
    '',
    '### Description',
    '',
    fenced(m.description),
    '',
    '### Attachments',
    '',
  ];
  if (m.attachments.length === 0) {
    lines.push('None.');
  } else {
    lines.push('| Name | Kind | Size |', '|---|---|---|');
    for (const a of m.attachments) {
      lines.push(`| [\`${a.name}\`](${dashboardUrl(r2, a.key)}) | ${a.kind} | ${humanSize(a.size)} |`);
    }
    lines.push(
      '',
      'The media is private. Maintainers fetch it with Wrangler:',
      '',
      '```sh',
      ...m.attachments.map((a) => `npx wrangler r2 object get ${r2.bucket}/${a.key} --remote --file ${a.name}`),
      '```',
    );
  }
  return { title: title(m.description), body: lines.join('\n') };
}
