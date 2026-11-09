// D9: data freshness and retries.
import {MutationCache, QueryCache, QueryClient} from '@tanstack/react-query';
import {uiErrorOf} from '../api/problem';
import {ROLE_QUERY_KEYS} from '../auth/roles';

function isRoleKey(key: readonly unknown[]): boolean {
    return ROLE_QUERY_KEYS.some((roleKey) => roleKey.every((part, i) => key[i] === part));
}

export function createQueryClient(): QueryClient {
    const client: QueryClient = new QueryClient({
        queryCache: new QueryCache({
            onError: (error, query) => {
                // AC-9.1: a 403 shows the forbidden state and refetches the role (but never loops on the role query itself).
                if (uiErrorOf(error).kind === 'forbidden' && !isRoleKey(query.queryKey)) {
                    for (const key of ROLE_QUERY_KEYS) void client.invalidateQueries({queryKey: [...key]});
                }
            },
        }),
        mutationCache: new MutationCache({
            onError: (error) => {
                const kind = uiErrorOf(error).kind;
                if (kind === 'forbidden') for (const key of ROLE_QUERY_KEYS) void client.invalidateQueries({queryKey: [...key]});
                // AC-9.1: 409 PROJECT_ARCHIVED shows the archived banner and refetches.
                if (kind === 'archived') void client.invalidateQueries();
            },
        }),
        defaultOptions: {
            queries: {
                staleTime: 30_000,
                refetchOnWindowFocus: true,
                // Retry once, only for a network failure, 502, 503 or 504.
                retry: (failureCount, error) => {
                    const kind = uiErrorOf(error).kind;
                    return failureCount < 1 && (kind === 'network' || kind === 'unavailable');
                },
            },
            mutations: {retry: false},
        },
    });
    return client;
}
