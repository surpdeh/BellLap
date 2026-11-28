import { AwsClient } from 'aws4fetch';
import { UPLOAD_URL_TTL_S } from './caps';
import type { Config } from './env';

export interface UploadTicket {
  name: string;
  method: 'PUT';
  url: string;
  headers: { 'Content-Type': string; 'Content-Length': string };
  expiresAt: string;
}

export interface PresignTarget {
  name: string;
  key: string;
  contentType: string;
  size: number;
}

const amzDate = (d: Date): string => d.toISOString().replace(/[:-]|\.\d{3}/g, '');

export async function presignPut(r2: Config['r2'], target: PresignTarget, now: Date): Promise<UploadTicket> {
  const client = new AwsClient({
    accessKeyId: r2.accessKeyId,
    secretAccessKey: r2.secretAccessKey,
    service: 's3',
    region: 'auto',
  });
  const path = target.key.split('/').map(encodeURIComponent).join('/');
  const url = `https://${r2.accountId}.r2.cloudflarestorage.com/${r2.bucket}/${path}?X-Amz-Expires=${UPLOAD_URL_TTL_S}`;
  const headers = { 'Content-Type': target.contentType, 'Content-Length': String(target.size) };
  // aws4fetch skips content-type and content-length by default. allHeaders signs them, so R2 rejects an upload
  // whose size or type differs from what the client declared.
  const signed = await client.sign(new Request(url, { method: 'PUT', headers }), {
    aws: { signQuery: true, allHeaders: true, datetime: amzDate(now) },
  });
  return {
    name: target.name,
    method: 'PUT',
    url: signed.url,
    headers,
    expiresAt: new Date(now.getTime() + UPLOAD_URL_TTL_S * 1000).toISOString(),
  };
}
