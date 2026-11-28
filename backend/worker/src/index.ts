import { loadConfig, type Config, type Env } from './env';
import { createReport, submitReport } from './handlers';
import { fail, json } from './http';

interface Route {
  pattern: RegExp;
  handle: (req: Request, env: Env, params: string[]) => Promise<Response>;
}

const withConfig =
  (fn: (req: Request, cfg: Config, params: string[]) => Promise<Response>) =>
  async (req: Request, env: Env, params: string[]): Promise<Response> => {
    const cfg = loadConfig(env);
    return cfg ? fn(req, cfg, params) : fail(503, 'not_configured');
  };

const ROUTES: Route[] = [
  { pattern: /^\/v1\/reports$/, handle: withConfig((req, cfg) => createReport(req, cfg, new Date())) },
  {
    pattern: /^\/v1\/reports\/([^/]+)\/submit$/,
    handle: withConfig((req, cfg, [id]) => submitReport(req, cfg, id ?? '', new Date())),
  },
];

export async function handle(req: Request, env: Env): Promise<Response> {
  const { pathname } = new URL(req.url);
  for (const route of ROUTES) {
    const match = route.pattern.exec(pathname);
    if (!match) continue;
    if (req.method !== 'POST') return json(405, { error: 'method_not_allowed' }, { Allow: 'POST' });
    try {
      return await route.handle(req, env, match.slice(1));
    } catch (e) {
      console.error('unhandled', e);
      return fail(500, 'internal');
    }
  }
  return fail(404, 'not_found');
}

export default { fetch: (req, env) => handle(req, env) } satisfies ExportedHandler<Env>;
