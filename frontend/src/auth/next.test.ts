import {describe, expect, it} from 'vitest';
import {safeNext} from './next';

// @US-1 @AC-1.7 (unit level)
describe('safeNext', () => {
    it.each([
        ['/orgs', '/orgs'],
        ['/projects/x/features?tag=%40smoke', '/projects/x/features?tag=%40smoke'],
        ['//evil.example', '/'],
        ['/\\evil.example', '/'],
        ['https://evil.example', '/'],
        ['javascript:alert(1)', '/'],
        ['orgs', '/'],
        ['', '/'],
        ['/orgs\n', '/'],
        ['/login?next=%2Forgs', '/'],
        [undefined, '/'],
        [42, '/'],
    ])('%j → %s', (next, expected) => {
        expect(safeNext(next)).toBe(expected);
    });
});
