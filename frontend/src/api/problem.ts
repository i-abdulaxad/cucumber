// D7: the only module that interprets error bodies. It turns a response (or a network failure) into a UiError.
// Branch on `status` first, then `code`. A gateway Problem has no `code`.
import type { components as Auth } from './generated/auth-service';
import type { components as Scenario } from './generated/scenario-service';
import type { components as Report } from './generated/report-service';
import type { components as Gateway } from './generated/api-gateway';

export type KnownCode =
  | Auth['schemas']['ErrorCode']
  | Scenario['schemas']['ErrorCode']
  | Report['schemas']['ErrorCode'];

/** Every code the contracts declare. The type check below fails when a contract adds or removes one. */
const KNOWN_CODES = [
  'VALIDATION_FAILED',
  'MALFORMED_REQUEST',
  'UNAUTHENTICATED',
  'INVALID_CREDENTIALS',
  'FORBIDDEN',
  'INVITATION_EMAIL_MISMATCH',
  'NOT_FOUND',
  'INVITATION_NOT_FOUND',
  'MEMBER_NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'NOT_ACCEPTABLE',
  'EMAIL_ALREADY_REGISTERED',
  'ALREADY_MEMBER',
  'INVITATION_PENDING',
  'SEAT_LIMIT_REACHED',
  'LAST_OWNER',
  'INVITATION_NOT_PENDING',
  'CONFLICT',
  'INVITATION_EXPIRED',
  'INVITATION_REVOKED',
  'INVITATION_ALREADY_ACCEPTED',
  'UNSUPPORTED_MEDIA_TYPE',
  'ROLE_CEILING',
  'SELF_ROLE_CHANGE',
  'INTERNAL_ERROR',
  'PROJECT_NAME_TAKEN',
  'PROJECT_ARCHIVED',
  'NOT_ORG_MEMBER',
  'PROJECT_ROLE_NOT_ALLOWED',
  'SERVICE_UNAVAILABLE',
  'FEATURE_NOT_FOUND',
  'VERSION_NOT_FOUND',
  'FEATURE_PATH_TAKEN',
  'VERSION_CONFLICT',
  'FEATURE_ARCHIVED',
  'GHERKIN_INVALID',
  'ACCESS_CHECK_UNAVAILABLE',
  'UPLOAD_TOKEN_INVALID',
  'UPLOAD_TOKEN_NOT_FOUND',
  'RUN_NOT_FOUND',
  'RESULT_NOT_FOUND',
  'UPLOAD_TOKEN_LIMIT',
  'RUN_CLOSED',
  'RUN_NOT_MANUAL',
  'FEATURE_CHANGED',
  'UPLOAD_TOO_LARGE',
  'CUCUMBER_JSON_INVALID',
  'UPLOAD_EMPTY',
  'UPLOAD_TOO_MANY_TEST_CASES',
  'FEATURE_NOT_PLANNABLE',
  'RUN_EMPTY',
  'RUN_TOO_LARGE',
  'PROJECT_CHECK_UNAVAILABLE',
  'SCENARIO_SERVICE_UNAVAILABLE',
] as const satisfies readonly KnownCode[];

type MissingCodes = Exclude<KnownCode, (typeof KNOWN_CODES)[number]>;
const allCodesListed: [MissingCodes] extends [never] ? true : MissingCodes = true;
void allCodesListed;

const CODE_SET: ReadonlySet<string> = new Set(KNOWN_CODES);

function isKnownCode(value: unknown): value is KnownCode {
  return typeof value === 'string' && CODE_SET.has(value);
}

export type FieldProblem =
  | Auth['schemas']['InvalidField']
  | Scenario['schemas']['InvalidField']
  | Report['schemas']['InvalidField']
  | Report['schemas']['InvalidJsonMember'];

export type ParseError = Scenario['schemas']['ParseError'];

/**
 * The Problem union, normalised: every contract's Problem plus the gateway's (`code: null`). Members a contract
 * declares only for some codes are empty or null when absent.
 */
export interface Problem extends Pick<Gateway['schemas']['Problem'], 'status' | 'title' | 'detail' | 'instance'> {
  code: KnownCode | null;
  errors: FieldProblem[];
  parseErrors: ParseError[];
  currentVersion: number | null;
  requestId: string | null;
}

const REQUEST_ID = /^[A-Za-z0-9._-]{1,128}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readFieldProblem(value: unknown): FieldProblem | null {
  if (!isRecord(value) || typeof value.message !== 'string') return null;
  if (typeof value.field === 'string') return { field: value.field, message: value.message };
  if (typeof value.pointer === 'string') return { pointer: value.pointer, message: value.message };
  return null;
}

function readParseError(value: unknown): ParseError | null {
  if (!isRecord(value)) return null;
  const { line, column, message } = value;
  if (typeof line !== 'number' || typeof column !== 'number' || typeof message !== 'string') return null;
  if (!Number.isInteger(line) || !Number.isInteger(column)) return null;
  return { line, column, message };
}

function readList<T>(value: unknown, read: (item: unknown) => T | null): T[] {
  if (!Array.isArray(value)) return [];
  const out: T[] = [];
  for (const item of value) {
    const parsed = read(item);
    if (parsed !== null) out.push(parsed);
  }
  return out;
}

/** Reads an RFC 9457 body. Returns null for anything that isn't a Problem. */
export function parseProblem(body: unknown): Problem | null {
  if (!isRecord(body)) return null;
  const { status, title, detail, instance, code, errors, parseErrors, currentVersion, requestId } = body;
  if (typeof status !== 'number' || !Number.isInteger(status) || typeof title !== 'string' || typeof detail !== 'string') {
    return null;
  }
  return {
    status,
    title,
    detail,
    instance: typeof instance === 'string' ? instance : '',
    code: isKnownCode(code) ? code : null,
    errors: readList(errors, readFieldProblem),
    parseErrors: readList(parseErrors, readParseError),
    currentVersion: typeof currentVersion === 'number' && Number.isInteger(currentVersion) ? currentVersion : null,
    requestId: typeof requestId === 'string' && REQUEST_ID.test(requestId) ? requestId : null,
  };
}

export type UiErrorKind =
  | 'unauthenticated'
  | 'forbidden'
  | 'refused'
  | 'not-found'
  | 'archived'
  | 'lock-conflict'
  | 'validation'
  | 'malformed'
  | 'unavailable'
  | 'server'
  | 'network'
  | 'specific';

export interface UiError {
  kind: UiErrorKind;
  /** The HTTP status, or null for a network failure. */
  status: number | null;
  code: KnownCode | null;
  retryable: boolean;
  requestId: string | null;
  /** The server's `detail`, only for codes the UI has no text of its own for (`specific`). */
  detail: string | null;
  problem: Problem | null;
}

function isProblemContentType(contentType: string | null): boolean {
  if (contentType === null) return false;
  const type = contentType.split(';')[0]?.trim().toLowerCase();
  return type === 'application/problem+json' || type === 'application/json';
}

function kindFor(status: number, code: KnownCode | null): UiErrorKind {
  if (status === 502 || status === 503 || status === 504) return 'unavailable';
  if (status >= 500) return 'server';
  if (status === 401) return code === 'INVALID_CREDENTIALS' ? 'specific' : 'unauthenticated';
  if (status === 403) {
    if (code === null) return 'refused';
    return code === 'FORBIDDEN' ? 'forbidden' : 'specific';
  }
  if (status === 404) return 'not-found';
  if (status === 400) {
    if (code === 'VALIDATION_FAILED') return 'validation';
    if (code === null || code === 'MALFORMED_REQUEST') return 'malformed';
    return 'specific';
  }
  if (status === 409 && code === 'PROJECT_ARCHIVED') return 'archived';
  if (status === 409 && code === 'CONFLICT') return 'lock-conflict';
  return 'specific';
}

const RETRYABLE: ReadonlySet<UiErrorKind> = new Set(['lock-conflict', 'unavailable', 'network']);

export function networkError(): UiError {
  return { kind: 'network', status: null, code: null, retryable: true, requestId: null, detail: null, problem: null };
}

/** AC-9.1: one mapper from a response to what the UI shows. Decided by the response status, then the code. */
export function toUiError(input: { status: number; body: unknown; contentType: string | null }): UiError {
  const { status } = input;
  const problem = isProblemContentType(input.contentType) ? parseProblem(input.body) : null;
  if (problem === null) {
    if (status === 502 || status === 503 || status === 504) {
      return { kind: 'unavailable', status, code: null, retryable: true, requestId: null, detail: null, problem: null };
    }
    return { ...networkError(), status };
  }
  const kind = kindFor(status, problem.code);
  return {
    kind,
    status,
    code: problem.code,
    retryable: RETRYABLE.has(kind),
    requestId: problem.requestId,
    detail: kind === 'specific' ? problem.detail : null,
    problem,
  };
}

/** Thrown by the API helpers; carries the mapped UiError. */
export class ApiError extends Error {
  readonly ui: UiError;

  constructor(ui: UiError) {
    super(ui.code ?? ui.kind);
    this.name = 'ApiError';
    this.ui = ui;
  }
}

/** Any thrown value as a UiError; anything that isn't an ApiError is the generic error. */
export function uiErrorOf(error: unknown): UiError {
  return error instanceof ApiError ? error.ui : networkError();
}

function fieldName(entry: FieldProblem): string {
  if ('field' in entry) return entry.field;
  return entry.pointer.replace(/^\//, '').split('/')[0] ?? '';
}

/** D7: `errors[]` entries by form field (`field` or `pointer`); unmatched entries become form-level messages. */
export function splitFieldErrors(
  error: UiError,
  fields: readonly string[],
): { fields: Partial<Record<string, string[]>>; form: string[] } {
  const known = new Set(fields);
  const byField: Partial<Record<string, string[]>> = {};
  const form: string[] = [];
  for (const entry of error.problem?.errors ?? []) {
    const name = fieldName(entry);
    if (known.has(name)) (byField[name] ??= []).push(entry.message);
    else form.push(entry.message);
  }
  return { fields: byField, form };
}
