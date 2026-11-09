import {describe, expect, it} from 'vitest';
import {networkError, parseProblem, splitFieldErrors, toUiError} from './problem';
import type {UiErrorKind} from './problem';

const json = 'application/problem+json';

function problem(status: number, code?: string, extra: Record<string, unknown> = {}) {
    return {
        type: 'about:blank',
        title: 'T',
        status,
        detail: 'Server detail',
        instance: '/api/x',
        ...(code ? {code} : {}),
        ...extra,
    };
}

// @US-9 @AC-9.1: one Problem mapper.
describe('AC-9.1 toUiError', () => {
    const rows: Array<[string, number, unknown, UiErrorKind, boolean]> = [
        ['401 any call but login', 401, problem(401, 'UNAUTHENTICATED'), 'unauthenticated', false],
        ['401 from the gateway (no code)', 401, problem(401), 'unauthenticated', false],
        ['403 FORBIDDEN', 403, problem(403, 'FORBIDDEN'), 'forbidden', false],
        ['403 without code (gateway CORS)', 403, problem(403), 'refused', false],
        ['404 NOT_FOUND', 404, problem(404, 'NOT_FOUND'), 'not-found', false],
        ['404 FEATURE_NOT_FOUND', 404, problem(404, 'FEATURE_NOT_FOUND'), 'not-found', false],
        ['404 RUN_NOT_FOUND', 404, problem(404, 'RUN_NOT_FOUND'), 'not-found', false],
        ['404 MEMBER_NOT_FOUND', 404, problem(404, 'MEMBER_NOT_FOUND'), 'not-found', false],
        ['404 without code (gateway, no route)', 404, problem(404), 'not-found', false],
        ['409 PROJECT_ARCHIVED', 409, problem(409, 'PROJECT_ARCHIVED'), 'archived', false],
        ['409 CONFLICT (lock timeout)', 409, problem(409, 'CONFLICT'), 'lock-conflict', true],
        ['400 VALIDATION_FAILED', 400, problem(400, 'VALIDATION_FAILED', {
            errors: [{
                field: 'name',
                message: 'Too long'
            }]
        }), 'validation', false],
        ['400 MALFORMED_REQUEST', 400, problem(400, 'MALFORMED_REQUEST'), 'malformed', false],
        ['400 without code (gateway firewall)', 400, problem(400), 'malformed', false],
        ['502 without code', 502, problem(502), 'unavailable', true],
        ['503 SERVICE_UNAVAILABLE', 503, problem(503, 'SERVICE_UNAVAILABLE'), 'unavailable', true],
        ['503 ACCESS_CHECK_UNAVAILABLE', 503, problem(503, 'ACCESS_CHECK_UNAVAILABLE'), 'unavailable', true],
        ['504 without code', 504, problem(504), 'unavailable', true],
        ['500 INTERNAL_ERROR', 500, problem(500, 'INTERNAL_ERROR'), 'server', false],
        ['500 without code (gateway)', 500, problem(500), 'server', false],
        ['a non-Problem body', 400, '<html>oops</html>', 'network', true],
        ['an empty body', 418, undefined, 'network', true],
        ['409 VERSION_CONFLICT', 409, problem(409, 'VERSION_CONFLICT', {currentVersion: 3}), 'specific', false],
        ['401 INVALID_CREDENTIALS (login)', 401, problem(401, 'INVALID_CREDENTIALS'), 'specific', false],
        ['409 EMAIL_ALREADY_REGISTERED', 409, problem(409, 'EMAIL_ALREADY_REGISTERED'), 'specific', false],
    ];

    it.each(rows)('%s', (_label, status, body, kind, retryable) => {
        const ui = toUiError({status, body, contentType: json});
        expect(ui.kind).toBe(kind);
        expect(ui.retryable).toBe(retryable);
    });

    it('a network failure is the generic error with Retry', () => {
        const ui = networkError();
        expect(ui.kind).toBe('network');
        expect(ui.retryable).toBe(true);
        expect(ui.requestId).toBeNull();
    });

    it('keeps requestId as the reference, with or without code', () => {
        expect(toUiError({
            status: 503,
            body: problem(503, undefined, {requestId: 'rid-1'}),
            contentType: json
        }).requestId).toBe('rid-1');
        expect(toUiError({
            status: 500,
            body: problem(500, 'INTERNAL_ERROR', {requestId: 'rid-2'}),
            contentType: json
        }).requestId).toBe('rid-2');
        expect(toUiError({
            status: 500,
            body: problem(500, 'INTERNAL_ERROR', {requestId: 'bad id!'}),
            contentType: json
        }).requestId).toBeNull();
    });

    it('keeps detail only for codes the UI has no text for', () => {
        expect(toUiError({
            status: 409,
            body: problem(409, 'SEAT_LIMIT_REACHED'),
            contentType: json
        }).detail).toBe('Server detail');
        expect(toUiError({status: 403, body: problem(403, 'FORBIDDEN'), contentType: json}).detail).toBeNull();
    });

    it('uses the response status, not the body status, to decide', () => {
        expect(toUiError({
            status: 503,
            body: problem(500, 'INTERNAL_ERROR'),
            contentType: json
        }).kind).toBe('unavailable');
    });

    it('accepts application/json Problems, but not other content types', () => {
        expect(toUiError({
            status: 403,
            body: problem(403, 'FORBIDDEN'),
            contentType: 'application/json'
        }).kind).toBe('forbidden');
        expect(toUiError({
            status: 403,
            body: problem(403, 'FORBIDDEN'),
            contentType: 'text/html'
        }).kind).toBe('network');
    });

    it('treats an unknown code like a Problem without code (decided by status)', () => {
        const ui = toUiError({status: 403, body: problem(403, 'SOMETHING_NEW'), contentType: json});
        expect(ui.kind).toBe('refused');
        expect(ui.code).toBeNull();
    });
});

describe('parseProblem', () => {
    it('reads parseErrors and currentVersion', () => {
        const p = parseProblem(
            problem(422, 'GHERKIN_INVALID', {parseErrors: [{line: 3, column: 5, message: 'expected step'}]}),
        );
        expect(p?.parseErrors).toEqual([{line: 3, column: 5, message: 'expected step'}]);
        expect(parseProblem(problem(409, 'VERSION_CONFLICT', {currentVersion: 4}))?.currentVersion).toBe(4);
    });

    it('drops malformed members instead of trusting them', () => {
        const p = parseProblem(
            problem(400, 'VALIDATION_FAILED', {
                errors: [{field: 'name', message: 'x'}, {field: 1, message: 'y'}, 'junk', {
                    pointer: '/a',
                    message: 'z'
                }],
                parseErrors: [{line: '1', column: 1, message: 'x'}],
                currentVersion: 'two',
            }),
        );
        expect(p?.errors).toEqual([
            {field: 'name', message: 'x'},
            {pointer: '/a', message: 'z'},
        ]);
        expect(p?.parseErrors).toEqual([]);
        expect(p?.currentVersion).toBeNull();
    });

    it.each([[null], [[]], ['text'], [{title: 'x'}], [{status: '400', title: 'x', detail: 'y'}], [{
        status: 400.5,
        title: 'x',
        detail: 'y'
    }]])(
        'rejects a non-Problem body %#',
        (body) => {
            expect(parseProblem(body)).toBeNull();
        },
    );
});

describe('splitFieldErrors (D7)', () => {
    it('maps errors to fields by field or pointer, and the rest to the form', () => {
        const ui = toUiError({
            status: 400,
            body: problem(400, 'VALIDATION_FAILED', {
                errors: [
                    {field: 'password', message: 'Too short'},
                    {field: 'password', message: 'Too common'},
                    {pointer: '/fullName', message: 'Blank'},
                    {field: 'plan', message: 'Unknown'},
                ],
            }),
            contentType: json,
        });
        expect(splitFieldErrors(ui, ['email', 'password', 'fullName'])).toEqual({
            fields: {password: ['Too short', 'Too common'], fullName: ['Blank']},
            form: ['Unknown'],
        });
    });
});
