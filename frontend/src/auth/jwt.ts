// D2/D4: the JWT payload is decoded by hand (no library). The signature is never checked here; the gateway and the
// services verify it. The claims are used only for the session's expiry and the header's email, never for permissions.

export interface JwtClaims {
    sub: string;
    email: string | null;
    /** Seconds since the epoch. */
    exp: number;
}

const BASE64URL = /^[A-Za-z0-9_-]+$/;

function decodeBase64Url(segment: string): string | null {
    if (!BASE64URL.test(segment) || segment.length % 4 === 1) return null;
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(segment.length / 4) * 4, '=');
    try {
        const binary = atob(base64);
        const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
        return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
    } catch {
        return null;
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function decodeJwt(token: string): JwtClaims | null {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const json = decodeBase64Url(parts[1] ?? '');
    if (json === null) return null;
    let payload: unknown;
    try {
        payload = JSON.parse(json);
    } catch {
        return null;
    }
    if (!isRecord(payload)) return null;
    const {sub, email, exp} = payload;
    if (typeof sub !== 'string' || sub.length === 0) return null;
    if (typeof exp !== 'number' || !Number.isFinite(exp)) return null;
    return {sub, email: typeof email === 'string' ? email : null, exp};
}
