import {Link} from '@tanstack/react-router';
import {useQuery} from '@tanstack/react-query';
import {Building, ChevronRight, Plus} from 'lucide-react';
import {authApi, unwrap} from '../../api/client';
import {AppLink} from '../../ui/AppLink';
import {Badge} from '../../ui/Badge';
import {Card} from '../../ui/Card';
import {EmptyState} from '../../ui/EmptyState';
import {PageHeader} from '../../ui/PageHeader';
import {QueryView} from '../../ui/QueryView';
import {appStrings} from '../strings';

/** The signed-in landing page (AC-1.3): the caller's organizations, with `myRole`. */
export function OrgsHome() {
    const query = useQuery({queryKey: ['orgs'], queryFn: () => unwrap(authApi.GET('/api/orgs'))});
    const hasOrgs = (query.data?.length ?? 0) > 0;
    return (
        <>
            <PageHeader
                title={appStrings.orgsTitle}
                actions={
                    hasOrgs ? (
                        <AppLink href="/orgs/new" variant="primary">
                            <Plus aria-hidden="true" size={16}/>
                            {appStrings.createOrg}
                        </AppLink>
                    ) : undefined
                }
            />
            <Card padding="none">
                <QueryView
                    query={query}
                    label={appStrings.orgsLabel}
                    isEmpty={(orgs) => orgs.length === 0}
                    empty={
                        <EmptyState
                            icon={Building}
                            title={appStrings.orgsEmpty}
                            action={
                                <AppLink href="/orgs/new" variant="primary">
                                    <Plus aria-hidden="true" size={16}/>
                                    {appStrings.createOrg}
                                </AppLink>
                            }
                        >
                            <p>{appStrings.orgsEmptyHelp}</p>
                        </EmptyState>
                    }
                >
                    {(orgs) => (
                        <ul className="divide-y divide-border">
                            {orgs.map((org) => (
                                <li key={org.id} className="group relative flex items-center gap-3 px-4 py-3 transition duration-120 ease-standard hover:bg-surface-hover">
                                    <span aria-hidden="true"
                                          className="inline-flex size-8 shrink-0 items-center justify-center rounded-md border border-border bg-surface text-sm font-semibold text-text-muted">
                                        {org.name.slice(0, 1).toUpperCase()}
                                    </span>
                                    <div className="flex min-w-0 flex-1 flex-col">
                                        <Link
                                            to="/orgs/$orgId/projects"
                                            params={{orgId: org.id}}
                                            className="truncate rounded-sm text-base font-medium text-text after:absolute after:inset-0"
                                        >
                                            {org.name}
                                        </Link>
                                    </div>
                                    <Badge>{appStrings.yourRole(org.myRole)}</Badge>
                                    <ChevronRight aria-hidden="true" size={16} className="text-text-muted"/>
                                </li>
                            ))}
                        </ul>
                    )}
                </QueryView>
            </Card>
        </>
    );
}
