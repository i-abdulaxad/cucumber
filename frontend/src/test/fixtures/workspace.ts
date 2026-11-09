// Batch B: the Acme workspace as each user sees it (spec glossary), typed against the generated auth-service schemas.
import type {components} from '../../api/generated/auth-service';
import {acme, anaCredentials, ids} from './auth';

type S = components['schemas'];

export const userIds = {
    ana: ids.ana,
    gus: '9c1e3a5b-7d2f-4b6a-8e0c-2a4c6e8a0b57',
    hal: '4e6a8c0e-2b4d-4f6a-8c0e-2b4d6f8a0c1e',
    dan: '7b9d1f3a-5c7e-4a9b-8d1f-3a5c7e9b1d3f',
} as const;

export const billingId = '5d7e9f1a-2b3c-4d5e-8f60-718293a4b5c6';

export const users = {
    ana: {id: userIds.ana, email: anaCredentials.email, fullName: 'Ana Ruiz', orgRole: 'OWNER', projectRole: 'ADMIN'},
    gus: {id: userIds.gus, email: 'gus@example.com', fullName: 'Gus Underwood', orgRole: 'MEMBER', projectRole: 'MEMBER'},
    hal: {id: userIds.hal, email: 'hal@example.com', fullName: 'Hal Jordan', orgRole: 'MEMBER', projectRole: 'TESTER'},
    dan: {id: userIds.dan, email: 'dan@example.com', fullName: 'Dan Brook', orgRole: 'READER', projectRole: 'READER'},
} as const satisfies Record<string, {id: string; email: string; fullName: string; orgRole: S['OrganizationRole']; projectRole: S['ProjectRole']}>;

export type UserKey = keyof typeof users;

export function orgFor(user: UserKey): S['Organization'] {
    return {...acme, myRole: users[user].orgRole};
}

export function projectsFor(user: UserKey): S['Project'][] {
    const role = users[user].projectRole;
    return [
        {id: ids.checkout, orgId: ids.acme, name: 'Checkout', createdAt: '2026-10-02T09:00:00Z', archivedAt: null, myRole: role},
        {id: billingId, orgId: ids.acme, name: 'Billing', createdAt: '2026-09-02T09:00:00Z', archivedAt: '2026-10-20T10:00:00Z', myRole: role},
    ];
}

export const acmeMembers: S['Member'][] = Object.values(users).map((u) => ({
    userId: u.id,
    email: u.email,
    fullName: u.fullName,
    role: u.orgRole,
    joinedAt: '2026-10-01T08:30:00Z',
}));
