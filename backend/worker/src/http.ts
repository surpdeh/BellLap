export const json = (status: number, body: unknown, headers: Record<string, string> = {}): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers },
  });

export const fail = (status: number, error: string, extra: Record<string, unknown> = {}): Response =>
  json(status, { error, ...extra });

export class BodyTooLarge extends Error {}

/** Reads the body as text, giving up once it passes `max` bytes whatever Content-Length claimed. */
export async function readBounded(req: Request, max: number): Promise<string> {
  const declared = Number(req.headers.get('Content-Length') ?? '0');
  if (declared > max) throw new BodyTooLarge();
  if (!req.body) return '';
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      throw new BodyTooLarge();
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    all.set(c, offset);
    offset += c.byteLength;
  }
  return new TextDecoder().decode(all);
}
