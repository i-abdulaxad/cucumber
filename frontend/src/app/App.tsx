import { useEffect } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { subscribe } from '../auth/session';
import type { AppRouter } from './router';
import './app.css';

const PUBLIC_PATHS = new Set(['/login', '/register']);

/** D4: when a session ends, the query cache is emptied and the user goes to login (keeping the location on 401). */
function useSessionEnd(router: AppRouter, queryClient: QueryClient) {
  useEffect(
    () =>
      subscribe((session, reason) => {
        if (session || reason === null) return;
        queryClient.clear();
        const { pathname, searchStr } = router.state.location;
        if (reason === 'logout' || PUBLIC_PATHS.has(pathname)) {
          void router.navigate({ to: '/login' });
        } else {
          void router.navigate({ to: '/login', search: { next: `${pathname}${searchStr}` } });
        }
      }),
    [router, queryClient],
  );
}

export function App({ router, queryClient }: { router: AppRouter; queryClient: QueryClient }) {
  useSessionEnd(router, queryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
