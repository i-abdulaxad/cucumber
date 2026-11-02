// D4: `next` is accepted only as an in-app path: it starts with "/" and not with "//" (or "/\", which browsers treat
// the same). Anything else, including absolute and javascript: URLs, lands on "/".
const CONTROL = /[\u0000-\u001F\u007F]/;

export function safeNext(next: unknown): string {
  if (typeof next !== 'string' || next.length === 0) return '/';
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/';
  if (CONTROL.test(next)) return '/';
  if (next === '/login' || next.startsWith('/login?') || next === '/register' || next.startsWith('/register?')) return '/';
  return next;
}
