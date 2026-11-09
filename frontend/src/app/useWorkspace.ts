import { useQueries, useQuery } from '@tanstack/react-query';
import { useParams } from '@tanstack/react-router';
import { authApi, unwrap } from '../api/client';
import { useSession } from '../auth/useSession';

/**
 * The org and project the current route is in, from auth-service (D5: roles come from `myRole`). A project route
 * has no org id, so the project is looked up in the caller's organizations' project lists.
 */
export function useWorkspace() {
    const session = useSession();
    const params = useParams({ strict: false });
    const routeOrgId = 'orgId' in params && typeof params.orgId === 'string' ? params.orgId : null;
    const routeProjectId = 'projectId' in params && typeof params.projectId === 'string' ? params.projectId : null;

    const orgsQuery = useQuery({
        queryKey: ['orgs'],
        queryFn: () => unwrap(authApi.GET('/api/orgs')),
        enabled: session !== null,
    });
    const orgs = orgsQuery.data ?? [];

    const candidateOrgIds = routeOrgId ? [routeOrgId] : routeProjectId ? orgs.map((o) => o.id) : [];
    const projectQueries = useQueries({
        queries: candidateOrgIds.map((orgId) => ({
            queryKey: ['projects', orgId],
            queryFn: () => unwrap(authApi.GET('/api/orgs/{orgId}/projects', { params: { path: { orgId } } })),
            enabled: session !== null,
        })),
    });
    const allProjects = projectQueries.flatMap((q) => q.data ?? []);
    const project = routeProjectId ? (allProjects.find((p) => p.id === routeProjectId) ?? null) : null;
    const orgId = routeOrgId ?? project?.orgId ?? null;
    const org = orgId ? (orgs.find((o) => o.id === orgId) ?? null) : null;
    const projects = orgId ? allProjects.filter((p) => p.orgId === orgId) : [];

    const membersQuery = useQuery({
        queryKey: ['orgs', orgId, 'members'],
        queryFn: () => unwrap(authApi.GET('/api/orgs/{orgId}/members', { params: { path: { orgId: orgId ?? '' } } })),
        enabled: session !== null && orgId !== null,
    });
    const me = session ? (membersQuery.data?.find((m) => m.userId === session.userId) ?? null) : null;

    return {
        session,
        orgs,
        orgsLoading: orgsQuery.isPending,
        org,
        orgId,
        projects,
        projectsLoading: projectQueries.some((q) => q.isPending),
        project,
        projectId: routeProjectId,
        me,
    };
}
