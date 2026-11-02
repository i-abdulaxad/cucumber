// D4: the only module that touches sessionStorage. The token lives in memory and is mirrored to sessionStorage
// (per tab). Never localStorage, a cookie, a URL or the console.
import { decodeJwt } from './jwt';

export const SESSION_STORAGE_KEY = 'cucumber.session';

export type EndReason = 'expired' | 'logout' | 'unauthorized';

export interface Session {
  token: string;
  userId: string;
  email: string | null;
  /** Milliseconds since the epoch. */
  expiresAt: number;
}

type Listener = (session: Session | null, reason: EndReason | null) => void;

const MAX_TIMER_MS = 2_147_483_647;

let current: Session | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;
let endReason: EndReason | null = null;
const listeners = new Set<Listener>();

function mirrorWrite(token: string | null): void {
  try {
    if (token === null) sessionStorage.removeItem(SESSION_STORAGE_KEY);
    else sessionStorage.setItem(SESSION_STORAGE_KEY, token);
  } catch {
    // Storage unavailable (for example a privacy mode): the session stays in memory only.
  }
}

function mirrorRead(): string | null {
  try {
    return sessionStorage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
}

function notify(reason: EndReason | null): void {
  for (const listener of [...listeners]) listener(current, reason);
}

function cancelTimer(): void {
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
}

function scheduleExpiry(session: Session): void {
  cancelTimer();
  const delay = Math.min(Math.max(0, session.expiresAt - Date.now()), MAX_TIMER_MS);
  timer = setTimeout(() => {
    timer = null;
    if (current !== session) return;
    if (Date.now() >= session.expiresAt) endSession('expired');
    else scheduleExpiry(session);
  }, delay);
}

function toSession(token: string, now: number): Session | null {
  const claims = decodeJwt(token);
  if (!claims) return null;
  const expiresAt = claims.exp * 1000;
  if (expiresAt <= now) return null;
  return { token, userId: claims.sub, email: claims.email, expiresAt };
}

/** Starts a session from a fresh login. Returns null, and stores nothing, for an invalid or expired token. */
export function startSession(token: string, now: number = Date.now()): Session | null {
  const session = toSession(token, now);
  if (!session) return null;
  current = session;
  endReason = null;
  mirrorWrite(token);
  scheduleExpiry(session);
  notify(null);
  return session;
}

/** On app start: reads the mirror; an invalid or expired token is discarded. */
export function restoreSession(now: number = Date.now()): Session | null {
  const token = mirrorRead();
  if (token === null) return null;
  const session = toSession(token, now);
  if (!session) {
    mirrorWrite(null);
    return null;
  }
  current = session;
  scheduleExpiry(session);
  return session;
}

export function getSession(): Session | null {
  return current;
}

export function getToken(): string | null {
  return current?.token ?? null;
}

/** Ends the session: memory, mirror and timer. Listeners are told once. */
export function endSession(reason: EndReason): void {
  const had = current !== null;
  cancelTimer();
  current = null;
  mirrorWrite(null);
  if (!had) return;
  endReason = reason;
  notify(reason);
}

/** The reason the last session ended, until the next session starts (for the login page's message). */
export function lastEndReason(): EndReason | null {
  return endReason;
}

/** The reason the last session ended, once. */
export function takeEndReason(): EndReason | null {
  const reason = endReason;
  endReason = null;
  return reason;
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Test support: forget everything without notifying anyone. */
export function clearSessionState(): void {
  cancelTimer();
  current = null;
  endReason = null;
  listeners.clear();
  mirrorWrite(null);
}
