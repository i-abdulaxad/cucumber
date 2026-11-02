// The Acme fixture (spec glossary), typed against the generated auth-service schemas.
import type { components } from '../../api/generated/auth-service';
import type { components as Gateway } from '../../api/generated/api-gateway';

type S = components['schemas'];

export const ids = {
  ana: '6a0e2b4c-8d1f-4e3a-9b5c-7d2e0f1a3b46',
  fay: '1f2e3d4c-5b6a-4789-8a9b-0c1d2e3f4a5b',
  acme: '0b8e4a52-6c1f-4f0e-8d3a-7a2b9c1d5e60',
  checkout: '3f9b2d6e-1c4a-4b7e-8f0d-5a2c4e6b8d13',
} as const;

export const anaCredentials = { email: 'ana@example.com', password: 'Secret123!' } satisfies S['LoginRequest'];

export const fay = {
  id: ids.fay,
  email: 'fay@example.com',
  fullName: 'Fay Lin',
  status: 'ACTIVE',
  mfaEnabled: false,
  createdAt: '2026-11-02T09:00:00Z',
} satisfies S['User'];

export const acme = {
  id: ids.acme,
  name: 'Acme',
  plan: 'FREE',
  seatLimit: 5,
  createdAt: '2026-10-01T08:30:00.123456Z',
  myRole: 'OWNER',
} satisfies S['Organization'];

export function authProblem(
  status: number,
  code: S['ErrorCode'],
  instance: string,
  extra: Partial<Pick<S['Problem'], 'errors' | 'requestId' | 'detail'>> = {},
): S['Problem'] {
  return {
    type: 'about:blank',
    title: 'Error',
    status,
    detail: extra.detail ?? `Problem ${code}`,
    instance,
    code,
    ...(extra.errors ? { errors: extra.errors } : {}),
    ...(extra.requestId ? { requestId: extra.requestId } : {}),
  };
}

export function gatewayProblem(status: number, instance: string, requestId?: string): Gateway['schemas']['Problem'] & { requestId?: string } {
  return {
    type: 'about:blank',
    title: 'Gateway error',
    status,
    detail: 'A gateway error.',
    instance,
    ...(requestId ? { requestId } : {}),
  };
}
