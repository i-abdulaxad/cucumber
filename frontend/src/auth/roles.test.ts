import {describe, expect, it} from 'vitest';
import {hasOrgRole, hasProjectRole, orgRank, projectRank} from './roles';

describe('roles (D5)', () => {
    it('ranks organization roles OWNER > ADMIN > MEMBER > READER', () => {
        expect([orgRank('OWNER'), orgRank('ADMIN'), orgRank('MEMBER'), orgRank('READER')]).toEqual([4, 3, 2, 1]);
    });
    it('ranks project roles ADMIN > MEMBER > TESTER > READER', () => {
        expect([projectRank('ADMIN'), projectRank('MEMBER'), projectRank('TESTER'), projectRank('READER')]).toEqual([4, 3, 2, 1]);
    });
    it.each([
        ['OWNER', 'ADMIN', true],
        ['ADMIN', 'ADMIN', true],
        ['MEMBER', 'ADMIN', false],
        ['READER', 'MEMBER', false],
    ] as const)('org %s has at least %s: %s', (role, min, expected) => {
        expect(hasOrgRole(role, min)).toBe(expected);
    });
    it.each([
        ['ADMIN', 'MEMBER', true],
        ['MEMBER', 'MEMBER', true],
        ['TESTER', 'MEMBER', false],
        ['TESTER', 'TESTER', true],
        ['READER', 'TESTER', false],
    ] as const)('project %s has at least %s: %s', (role, min, expected) => {
        expect(hasProjectRole(role, min)).toBe(expected);
    });
    it('a missing role allows nothing', () => {
        expect(hasOrgRole(undefined, 'READER')).toBe(false);
        expect(hasProjectRole(null, 'READER')).toBe(false);
    });
});
