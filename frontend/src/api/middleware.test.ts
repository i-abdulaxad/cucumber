import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import createClient from 'openapi-fetch';
import type { paths } from './generated/auth-service';
import { createAuthMiddleware } from './middleware';
import { server, problemHeaders } from '../test/msw';
import { authProblem, gatewayProblem, ids } from '../test/fixtures/auth';

function client(token: string | null, onUnauthorized = vi.fn()) {
  const c = createClient<paths>({ baseUrl: window.location.origin, fetch: (r) => globalThis.fetch(r) });
  c.use(createAuthMiddleware({ getToken: () => token, onUnauthorized }));
  return { c, onUnauthorized };
}

describe('auth middleware (D6)', () => {
  it('adds the Bearer token to protected calls', async () => {
    let seen: string | null = null;
    server.use(
      http.get('/api/orgs', ({ request }) => {
        seen = request.headers.get('Authorization');
        return HttpResponse.json([]);
      }),
    );
    await client('tok.en.x').c.GET('/api/orgs');
    expect(seen).toBe('Bearer tok.en.x');
  });

  it('sends no Authorization header to login or register', async () => {
    const seen: Array<string | null> = [];
    server.use(
      http.post('/api/auth/login', ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return HttpResponse.json({ accessToken: 'a.b.c' });
      }),
    );
    await client('tok.en.x').c.POST('/api/auth/login', { body: { email: 'a@example.com', password: 'x' } });
    expect(seen).toEqual([null]);
  });

  it('turns a 401 from any call but login into onUnauthorized (AC-1.4)', async () => {
    server.use(http.get('/api/orgs', () => HttpResponse.json(gatewayProblem(401, '/api/orgs'), { status: 401, headers: problemHeaders })));
    const { c, onUnauthorized } = client('tok.en.x');
    await c.GET('/api/orgs');
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('also for a 401 from a service (code UNAUTHENTICATED)', async () => {
    const path = `/api/orgs/${ids.acme}`;
    server.use(http.get(path, () => HttpResponse.json(authProblem(401, 'UNAUTHENTICATED', path), { status: 401, headers: problemHeaders })));
    const { c, onUnauthorized } = client('tok.en.x');
    await c.GET('/api/orgs/{orgId}', { params: { path: { orgId: ids.acme } } });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("does not end the session on login's own 401 INVALID_CREDENTIALS (AC-1.2)", async () => {
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json(authProblem(401, 'INVALID_CREDENTIALS', '/api/auth/login'), { status: 401, headers: problemHeaders }),
      ),
    );
    const { c, onUnauthorized } = client(null);
    await c.POST('/api/auth/login', { body: { email: 'a@example.com', password: 'wrong' } });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
