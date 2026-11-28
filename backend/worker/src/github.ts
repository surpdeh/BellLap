import { SignJWT, importPKCS8 } from 'jose';
import type { Config } from './env';
import type { FiledIssue } from './manifest';

const API = 'https://api.github.com';

export class GitHubError extends Error {
  constructor(
    readonly step: 'token' | 'issue',
    readonly status: number,
  ) {
    super(`GitHub ${step} request failed with ${status}`);
  }
}

const headers = (auth: string): Record<string, string> => ({
  Accept: 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
  'User-Agent': 'belllap-bugreport-worker',
  'Content-Type': 'application/json',
  Authorization: `Bearer ${auth}`,
});

// Secrets pasted through some UIs arrive with literal "\n" in place of newlines.
const normalisePem = (pem: string): string => pem.replace(/\\n/g, '\n').trim();

async function appJwt(gh: Config['github'], nowS: number): Promise<string> {
  const key = await importPKCS8(normalisePem(gh.privateKey), 'RS256');
  return new SignJWT({})
    .setProtectedHeader({ alg: 'RS256', typ: 'JWT' })
    .setIssuedAt(nowS - 60)
    .setExpirationTime(nowS + 540)
    .setIssuer(gh.appId)
    .sign(key);
}

async function installationToken(gh: Config['github'], nowS: number): Promise<string> {
  const res = await fetch(`${API}/app/installations/${encodeURIComponent(gh.installationId)}/access_tokens`, {
    method: 'POST',
    headers: headers(await appJwt(gh, nowS)),
    body: JSON.stringify({ repositories: [gh.repo], permissions: { issues: 'write' } }),
  });
  if (!res.ok) throw new GitHubError('token', res.status);
  const { token } = await res.json<{ token: string }>();
  return token;
}

export interface NewIssue {
  title: string;
  body: string;
}

export async function createIssue(gh: Config['github'], issue: NewIssue, now: Date): Promise<FiledIssue> {
  const token = await installationToken(gh, Math.floor(now.getTime() / 1000));
  const res = await fetch(`${API}/repos/${encodeURIComponent(gh.owner)}/${encodeURIComponent(gh.repo)}/issues`, {
    method: 'POST',
    headers: headers(token),
    body: JSON.stringify({ title: issue.title, body: issue.body, labels: ['field-report'] }),
  });
  if (!res.ok) throw new GitHubError('issue', res.status);
  const created = await res.json<{ number: number; html_url: string }>();
  return { number: created.number, url: created.html_url };
}
