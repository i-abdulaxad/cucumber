// D6: the one middleware every client shares. It adds the Bearer token and reports a 401 (D4).
import type { Middleware } from 'openapi-fetch';

/** Public operations that never need the token (gateway D4). acceptInvitation may use it, so it isn't listed. */
const NO_TOKEN_PATHS: ReadonlySet<string> = new Set(['/api/auth/login', '/api/auth/register']);

/** Login's own 401 is INVALID_CREDENTIALS, not an ended session (AC-1.2). The gateway never answers 401 on it. */
const LOGIN_PATH = '/api/auth/login';

export interface AuthMiddlewareDeps {
  getToken: () => string | null;
  onUnauthorized: () => void;
}

export function createAuthMiddleware(deps: AuthMiddlewareDeps): Middleware {
  return {
    onRequest({ request, schemaPath }) {
      const token = deps.getToken();
      if (token !== null && !NO_TOKEN_PATHS.has(schemaPath)) {
        request.headers.set('Authorization', `Bearer ${token}`);
      }
      return request;
    },
    onResponse({ response, schemaPath }) {
      if (response.status === 401 && schemaPath !== LOGIN_PATH) deps.onUnauthorized();
      return undefined;
    },
  };
}
