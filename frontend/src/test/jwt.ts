// Builds unsigned test JWTs (the UI never verifies signatures; the gateway does).
function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Claims of any shape, so tests can also build malformed tokens. */
export type TestClaims = Record<string, unknown>;

export function makeJwt(claims: TestClaims): string {
    return `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify(claims))}.c2lnbmF0dXJl`;
}

export function payloadSegment(token: string): string {
    return token.split('.')[1] ?? '';
}

export const nowSeconds = (): number => Math.floor(Date.now() / 1000);

export function tokenFor(sub: string, email: string, secondsLeft: number): string {
    const now = nowSeconds();
    return makeJwt({sub, email, iat: now, exp: now + secondsLeft});
}
