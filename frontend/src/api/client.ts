// D6: one openapi-fetch client per service. Only src/api calls fetch.
import createClient from 'openapi-fetch';
import type {paths as AuthPaths} from './generated/auth-service';
import type {paths as ScenarioPaths} from './generated/scenario-service';
import type {paths as ReportPaths} from './generated/report-service';
import {createAuthMiddleware} from './middleware';
import {ApiError, networkError, toUiError} from './problem';
import {endSession, getToken} from '../auth/session';

const middleware = createAuthMiddleware({
    getToken,
    onUnauthorized: () => endSession('unauthorized'),
});

function makeClient<Paths extends object>() {
    // Same origin as the app: in development the Vite proxy forwards /api to the gateway (D3).
    // fetch is looked up per call so test interceptors installed later still apply.
    const client = createClient<Paths>({
        baseUrl: window.location.origin,
        fetch: (request) => globalThis.fetch(request)
    });
    client.use(middleware);
    return client;
}

export const authApi = makeClient<AuthPaths>();
export const scenarioApi = makeClient<ScenarioPaths>();
export const reportApi = makeClient<ReportPaths>();

interface FetchResultLike<D> {
    data?: D;
    error?: unknown;
    response: Response;
}

async function settle<D>(pending: Promise<FetchResultLike<D>>): Promise<FetchResultLike<D>> {
    let result: FetchResultLike<D>;
    try {
        result = await pending;
    } catch {
        throw new ApiError(networkError());
    }
    const {response} = result;
    if (!response.ok) {
        throw new ApiError(
            toUiError({status: response.status, body: result.error, contentType: response.headers.get('content-type')}),
        );
    }
    return result;
}

/** Resolves to the success body, or throws an ApiError carrying the mapped UiError (AC-9.1). */
export async function unwrap<D>(pending: Promise<FetchResultLike<D>>): Promise<D> {
    const result = await settle(pending);
    if (result.data === undefined) throw new ApiError(networkError());
    return result.data;
}

/** For operations that answer without a body (for example 204). */
export async function unwrapEmpty(pending: Promise<FetchResultLike<unknown>>): Promise<void> {
    await settle(pending);
}
