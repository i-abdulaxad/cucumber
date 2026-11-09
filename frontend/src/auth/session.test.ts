import {afterEach, describe, expect, it, vi} from 'vitest';
import {
    SESSION_STORAGE_KEY,
    endSession,
    getSession,
    getToken,
    restoreSession,
    startSession,
    subscribe,
    takeEndReason,
} from './session';
import {makeJwt, nowSeconds, tokenFor} from '../test/jwt';

afterEach(() => vi.useRealTimers());

describe('session storage (D4)', () => {
    it('keeps the token in memory and mirrors it only to sessionStorage', () => {
        const token = tokenFor('u1', 'ana@example.com', 3600);
        expect(startSession(token)).not.toBeNull();
        expect(getToken()).toBe(token);
        expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(token);
        expect(SESSION_STORAGE_KEY).toBe('cucumber.session');
        expect(localStorage.length).toBe(0);
        expect(document.cookie).toBe('');
    });

    it('refuses a token that is invalid or already expired, and stores nothing', () => {
        expect(startSession('not-a-jwt')).toBeNull();
        expect(startSession(tokenFor('u1', 'a@example.com', -1))).toBeNull();
        expect(getSession()).toBeNull();
        expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    });
});

// @US-1 @AC-1.5
describe('AC-1.5 session on start', () => {
    it('a token with exp in 1 hour: logged in', () => {
        const token = tokenFor('u1', 'ana@example.com', 3600);
        sessionStorage.setItem(SESSION_STORAGE_KEY, token);
        expect(restoreSession()?.email).toBe('ana@example.com');
        expect(getToken()).toBe(token);
    });

    it.each([
        ['a token with exp 1 s ago', () => tokenFor('u1', 'ana@example.com', -1)],
        ['a token with exp equal to now', () => makeJwt({sub: 'u1', exp: nowSeconds()})],
        ['"not-a-jwt"', () => 'not-a-jwt'],
    ])('%s: the mirror is cleared', (_label, token) => {
        sessionStorage.setItem(SESSION_STORAGE_KEY, token());
        expect(restoreSession()).toBeNull();
        expect(getSession()).toBeNull();
        expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    });

    it('nothing: no session', () => {
        expect(restoreSession()).toBeNull();
    });
});

// @US-1 @AC-1.6
describe('AC-1.6 expiry and logout', () => {
    it('ends the session with reason "expired" when exp passes', () => {
        vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout', 'Date']});
        vi.setSystemTime(new Date('2026-11-02T10:00:00Z'));
        const ends: string[] = [];
        const unsubscribe = subscribe((session, reason) => {
            if (!session && reason) ends.push(reason);
        });
        startSession(makeJwt({sub: 'u1', exp: nowSeconds() + 2}));
        vi.advanceTimersByTime(1999);
        expect(getSession()).not.toBeNull();
        vi.advanceTimersByTime(1);
        expect(getSession()).toBeNull();
        expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
        expect(ends).toEqual(['expired']);
        expect(takeEndReason()).toBe('expired');
        expect(takeEndReason()).toBeNull();
        unsubscribe();
    });

    it('logout clears memory and the mirror and cancels the expiry timer', () => {
        vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout', 'Date']});
        startSession(tokenFor('u1', 'ana@example.com', 5));
        endSession('logout');
        expect(getToken()).toBeNull();
        expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
        expect(takeEndReason()).toBe('logout');
        vi.advanceTimersByTime(10_000);
        expect(takeEndReason()).toBeNull();
    });

    it('ending twice notifies once', () => {
        const listener = vi.fn();
        subscribe(listener);
        startSession(tokenFor('u1', 'ana@example.com', 60));
        listener.mockClear();
        endSession('unauthorized');
        endSession('unauthorized');
        expect(listener).toHaveBeenCalledTimes(1);
    });
});
