// The route tree. Later batches add routes here (additive only).
import { createRootRouteWithContext, createRoute, redirect } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { getSession } from '../auth/session';
import { safeNext } from '../auth/next';
import { LoginPage } from '../features/session/LoginPage';
import { RegisterPage } from '../features/session/RegisterPage';
import { Shell } from './Shell';
import { OrgsHome } from './pages/OrgsHome';
import { FeaturesPlaceholder, NewOrgPlaceholder, NotFoundPage, ProjectsPlaceholder } from './pages/Placeholders';

export interface RouterContext {
  queryClient: QueryClient;
}

function stringParam(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Shell,
  notFoundComponent: NotFoundPage,
});

function redirectIfSignedIn(next: string | undefined) {
  if (getSession()) throw redirect({ href: safeNext(next), replace: true });
}

export const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: (search: Record<string, unknown>): { next?: string } => {
    const next = stringParam(search.next);
    return next ? { next } : {};
  },
  beforeLoad: ({ search }) => redirectIfSignedIn(search.next),
  component: function LoginRoute() {
    return <LoginPage next={loginRoute.useSearch().next} />;
  },
});

export const registerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/register',
  beforeLoad: () => redirectIfSignedIn(undefined),
  component: RegisterPage,
});

/** Every route below needs a session; without one, go to login and come back (AC-1.1, D4). */
export const authedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'authed',
  beforeLoad: ({ location }) => {
    if (!getSession()) {
      throw redirect({ to: '/login', search: { next: `${location.pathname}${location.searchStr}` }, replace: true });
    }
  },
});

export const homeRoute = createRoute({ getParentRoute: () => authedRoute, path: '/', component: OrgsHome });
export const orgsRoute = createRoute({ getParentRoute: () => authedRoute, path: '/orgs', component: OrgsHome });
export const newOrgRoute = createRoute({ getParentRoute: () => authedRoute, path: '/orgs/new', component: NewOrgPlaceholder });
export const orgProjectsRoute = createRoute({
  getParentRoute: () => authedRoute,
  path: '/orgs/$orgId/projects',
  component: ProjectsPlaceholder,
});
export const projectFeaturesRoute = createRoute({
  getParentRoute: () => authedRoute,
  path: '/projects/$projectId/features',
  validateSearch: (search: Record<string, unknown>): { tag?: string } => {
    const tag = stringParam(search.tag);
    return tag ? { tag } : {};
  },
  component: function FeaturesRoute() {
    return <FeaturesPlaceholder tag={projectFeaturesRoute.useSearch().tag} />;
  },
});

export const routeTree = rootRoute.addChildren([
  loginRoute,
  registerRoute,
  authedRoute.addChildren([homeRoute, orgsRoute, newOrgRoute, orgProjectsRoute, projectFeaturesRoute]),
]);
