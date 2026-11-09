// Batch B: Checkout's features (spec glossary), typed against the generated scenario-service schemas.
import type {components} from '../../api/generated/scenario-service';
import {ids} from './auth';
import {userIds} from './workspace';

type S = components['schemas'];

export const featureIds = {
    payment: '2d7f3a91-5b0c-4e6d-8a1f-9c3b5e7d1f20',
    address: '6e8a0c2e-4b6d-4f8a-9c0e-2b4d6f8a0c2e',
    legacy: '8f0b2d4f-6a8c-4e0b-8d2f-4a6c8e0b2d4f',
    refund: '1a3c5e7a-9b1d-4f3a-8c5e-7a9b1d3f5a7c',
} as const;

export const paymentSourceV2 = [
    '@checkout',
    'Feature: Payment',
    '',
    '  Scenario: Pay by card',
    '    Given a cart with 2 items',
    '    When I pay by card',
    '    Then the order is confirmed',
    '',
].join('\n');

export const paymentSourceV3 = paymentSourceV2.replace('the order is confirmed', 'the order is confirmed by email');

export const addressSource = [
    '@smoke',
    'Feature: Address',
    '',
    '  Scenario: Add an address',
    '    Given I am signed in',
    '    When I add an address',
    '    Then it is saved',
    '',
].join('\n');

const base = {
    projectId: ids.checkout,
    language: 'en',
    createdBy: userIds.gus,
    createdAt: '2026-10-05T09:00:00Z',
    updatedBy: userIds.gus,
    updatedAt: '2026-10-28T14:30:00.25Z',
} as const;

export function summary(
    id: string,
    path: string,
    name: string,
    currentVersion: number,
    tags: string[],
    archivedAt: string | null = null,
): S['FeatureSummary'] {
    return {...base, id, path, name, currentVersion, tags, testCaseCount: 1, archivedAt};
}

export const payment = summary(featureIds.payment, 'features/checkout/payment.feature', 'Payment', 2, ['@checkout']);
export const address = summary(featureIds.address, 'features/checkout/address.feature', 'Address', 1, ['@smoke']);
export const legacy = summary(featureIds.legacy, 'features/legacy.feature', 'Legacy', 4, [], '2026-10-15T08:00:00Z');

export function detail(s: S['FeatureSummary'], source: string, overrides: Partial<S['Feature']> = {}): S['Feature'] {
    const {testCaseCount: _count, ...rest} = s;
    void _count;
    return {...rest, description: '', source, children: [], ...overrides};
}

export const paymentDetail = detail(payment, paymentSourceV2);
export const addressDetail = detail(address, addressSource);
export const legacyDetail = detail(legacy, 'Feature: Legacy\n');

export const tags: S['Tag'][] = [
    {name: '@checkout', featureCount: 1},
    {name: '@smoke', featureCount: 1},
];

export function scenarioProblem(
    status: number,
    code: S['ErrorCode'],
    instance: string,
    extra: Partial<Pick<S['Problem'], 'errors' | 'parseErrors' | 'currentVersion' | 'requestId'>> = {},
): S['Problem'] {
    return {type: 'about:blank', title: 'Error', status, detail: `Problem ${code}`, instance, code, ...extra};
}

/** 2,000 features in 40 folders (AC-5.5). */
export function manyFeatures(): S['FeatureSummary'][] {
    const out: S['FeatureSummary'][] = [];
    for (let f = 0; f < 40; f += 1) {
        for (let i = 0; i < 50; i += 1) {
            const n = f * 50 + i;
            const id = `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
            out.push(summary(id, `features/area-${String(f).padStart(2, '0')}/feature-${String(i).padStart(2, '0')}.feature`, `Feature ${n}`, 1, []));
        }
    }
    out.push(payment);
    return out;
}
