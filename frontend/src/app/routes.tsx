// The route tree. Later batches add routes here (additive only).
import {createRootRouteWithContext, createRoute, redirect} from '@tanstack/react-router';
import type {QueryClient} from '@tanstack/react-query';
import {getSession} from '../auth/session';
import {safeNext} from '../auth/next';
import {LoginPage} from '../features/session/LoginPage';
import {RegisterPage} from '../features/session/RegisterPage';
import {AppShell} from './AppShell';
import {Shell} from './Shell';
import {OrgsHome} from './pages/OrgsHome';
import {NewOrgPlaceholder, NotFoundPage, ProjectsPlaceholder} from './pages/Placeholders';
import {lazy, Suspense} from 'react';
import type {FeaturesSearch} from '../features/tree/FeaturesPage';
import {SkeletonRows} from '../ui/Skeleton';

// Batch B: the tree and the editor load with their route, not with the login page (NFR: initial JS budget).
const FeaturesPage = lazy(() => import('../features/tree/FeaturesPage').then((m) => ({default: m.FeaturesPage})));

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
    if (getSession()) throw redirect({href: safeNext(next), replace: true});
}

export const loginRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/login',
    validateSearch: (search: Record<string, unknown>): { next?: string } => {
        const next = stringParam(search.next);
        return next ? {next} : {};
    },
    beforeLoad: ({search}) => redirectIfSignedIn(search.next),
    component: function LoginRoute() {
        return <LoginPage next={loginRoute.useSearch().next}/>;
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
    component: AppShell,
    beforeLoad: ({location}) => {
        if (!getSession()) {
            throw redirect({to: '/login', search: {next: `${location.pathname}${location.searchStr}`}, replace: true});
        }
    },
});

export const homeRoute = createRoute({getParentRoute: () => authedRoute, path: '/', component: OrgsHome});
export const orgsRoute = createRoute({getParentRoute: () => authedRoute, path: '/orgs', component: OrgsHome});
export const newOrgRoute = createRoute({
    getParentRoute: () => authedRoute,
    path: '/orgs/new',
    component: NewOrgPlaceholder
});
export const orgProjectsRoute = createRoute({
    getParentRoute: () => authedRoute,
    path: '/orgs/$orgId/projects',
    component: ProjectsPlaceholder,
});
// Batch B: the feature tree and editor. The URL keeps the view, the tag and the selected feature (D10).
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export const projectFeaturesRoute = createRoute({
    getParentRoute: () => authedRoute,
    path: '/projects/$projectId/features',
    validateSearch: (search: Record<string, unknown>): FeaturesSearch => {
        const tag = stringParam(search.tag);
        const feature = stringParam(search.feature);
        return {
            ...(tag ? {tag} : {}),
            ...(search.view === 'archived' ? {view: 'archived' as const} : {}),
            ...(feature && CANONICAL_UUID.test(feature) ? {feature} : {}),
        };
    },
    component: function FeaturesRoute() {
        const {projectId} = projectFeaturesRoute.useParams();
        const search = projectFeaturesRoute.useSearch();
        return (
            <Suspense fallback={<SkeletonRows rows={6}/>}>
                <FeaturesPage projectId={projectId} search={search}/>
            </Suspense>
        );
    },
});

export const routeTree = rootRoute.addChildren([
    loginRoute,
    registerRoute,
    authedRoute.addChildren([homeRoute, orgsRoute, newOrgRoute, orgProjectsRoute, projectFeaturesRoute]),
]);
