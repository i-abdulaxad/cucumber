import {useEffect} from 'react';
import {QueryClientProvider, type QueryClient} from '@tanstack/react-query';
import {RouterProvider} from '@tanstack/react-router';
import {RouterProvider as AriaRouterProvider} from 'react-aria-components';
import {subscribe} from '../auth/session';
import type {AppRouter} from './router';

const PUBLIC_PATHS = new Set(['/login', '/register']);

/** D4: when a session ends, the query cache is emptied and the user goes to login (keeping the location on 401). */
function useSessionEnd(router: AppRouter, queryClient: QueryClient) {
    useEffect(
        () =>
            subscribe((session, reason) => {
                if (session || reason === null) return;
                queryClient.clear();
                const {pathname, searchStr} = router.state.location;
                const navigation =
                    reason === 'logout' || PUBLIC_PATHS.has(pathname)
                        ? router.navigate({to: '/login'})
                        : router.navigate({to: '/login', search: {next: `${pathname}${searchStr}`}});
                // Clear again once the signed-in layout has unmounted, so none of its queries survive.
                void navigation.then(() => queryClient.clear());
            }),
        [router, queryClient],
    );
}

export function App({router, queryClient}: { router: AppRouter; queryClient: QueryClient }) {
    useSessionEnd(router, queryClient);
    return (
        <QueryClientProvider client={queryClient}>
            {/* react-aria links and menu items navigate through the app router (no full reload). */}
            <AriaRouterProvider navigate={(href) => router.history.push(href)}>
                <RouterProvider router={router}/>
            </AriaRouterProvider>
        </QueryClientProvider>
    );
}
