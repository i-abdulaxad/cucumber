import {describe, expect, it} from 'vitest';
import {decodeJwt} from './jwt';
import {makeJwt, payloadSegment} from '../test/jwt';

describe('decodeJwt (D4: sub, email and exp decoded by hand)', () => {
    it('decodes a base64url payload that contains "-" and "_"', () => {
        // These characters make standard base64 emit "+" and "/", which base64url turns into "-" and "_".
        const token = makeJwt({
            sub: 'b5f1c2d3-0000-4000-8000-000000000001',
            email: 'ÿÿ~~??>>@example.com',
            exp: 4102444800
        });
        const segment = payloadSegment(token);
        expect(segment).toMatch(/-/);
        expect(segment).toMatch(/_/);
        expect(segment).not.toMatch(/[+/=]/);
        expect(decodeJwt(token)).toEqual({
            sub: 'b5f1c2d3-0000-4000-8000-000000000001',
            email: 'ÿÿ~~??>>@example.com',
            exp: 4102444800
        });
    });

    it('decodes UTF-8 in claims', () => {
        expect(decodeJwt(makeJwt({sub: 'u1', email: 'zoë@example.com', exp: 10}))?.email).toBe('zoë@example.com');
    });

    it('treats a missing email as null', () => {
        expect(decodeJwt(makeJwt({sub: 'u1', exp: 10}))).toEqual({sub: 'u1', email: null, exp: 10});
    });

    it.each([
        ['not-a-jwt', 'not-a-jwt'],
        ['two segments', 'a.b'],
        ['four segments', 'a.b.c.d'],
        ['payload not base64url', 'eyJhbGciOiJIUzUxMiJ9.***.sig'],
        ['payload not JSON', `eyJhbGciOiJIUzUxMiJ9.${btoa('nope')}.sig`],
        ['payload is an array', makeJwt({sub: 'x', exp: 1}).replace(/\.[^.]+\./, `.${btoa('[1]')}.`)],
        ['no sub', makeJwt({exp: 10})],
        ['empty sub', makeJwt({sub: '', exp: 10})],
        ['no exp', makeJwt({sub: 'u1'})],
        ['string exp', makeJwt({sub: 'u1', exp: '10'})],
        ['non-finite exp', makeJwt({sub: 'u1', exp: 1e400})],
        ['empty string', ''],
    ])('rejects %s', (_label, token) => {
        expect(decodeJwt(token)).toBeNull();
    });
});
