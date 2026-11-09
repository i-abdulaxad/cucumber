// Batch A placeholders: later batches replace these pages (routes.tsx is additive).
import {Construction, SearchX} from 'lucide-react';
import {AppLink} from '../../ui/AppLink';
import {AuthLayout} from '../../ui/AuthLayout';
import {Badge} from '../../ui/Badge';
import {Card} from '../../ui/Card';
import {EmptyState} from '../../ui/EmptyState';
import {PageHeader} from '../../ui/PageHeader';
import {PageHeading} from '../../ui/PageHeading';
import type {Crumb} from '../../ui/Breadcrumbs';
import {appStrings} from '../strings';
import {useWorkspace} from '../useWorkspace';

function useCrumbs(area: string): Crumb[] {
    const {org, orgId, project} = useWorkspace();
    const crumbs: Crumb[] = [];
    if (org && orgId) crumbs.push({label: org.name, href: `/orgs/${orgId}/projects`});
    if (project) crumbs.push({label: project.name, href: `/projects/${project.id}/features`});
    crumbs.push({label: area});
    return crumbs;
}

function LaterBatch() {
    return (
        <Card padding="none">
            <EmptyState icon={Construction} title={appStrings.laterBatch}/>
        </Card>
    );
}

export function NewOrgPlaceholder() {
    return (
        <>
            <PageHeader title={appStrings.newOrgTitle}/>
            <LaterBatch/>
        </>
    );
}

export function ProjectsPlaceholder() {
    const crumbs = useCrumbs(appStrings.projectsTitle);
    return (
        <>
            <PageHeader title={appStrings.projectsTitle} breadcrumbs={crumbs}/>
            <LaterBatch/>
        </>
    );
}

export function FeaturesPlaceholder({tag}: { tag: string | undefined }) {
    const crumbs = useCrumbs(appStrings.featuresTitle);
    return (
        <>
            <PageHeader title={appStrings.featuresTitle} breadcrumbs={crumbs}/>
            {tag ? (
                <div className="mb-4">
                    <Badge tone="primary">{appStrings.tagFilter(tag)}</Badge>
                </div>
            ) : null}
            <LaterBatch/>
        </>
    );
}

export function NotFoundPage() {
    return (
        <AuthLayout>
            <div className="flex flex-col items-start gap-3">
                <span className="inline-flex size-10 items-center justify-center rounded-full bg-surface-hover text-text-muted">
                    <SearchX aria-hidden="true" size={24}/>
                </span>
                <PageHeading>{appStrings.notFoundTitle}</PageHeading>
                <p className="text-base text-text-muted">{appStrings.notFoundText}</p>
                <AppLink href="/">{appStrings.backToOrgs}</AppLink>
            </div>
        </AuthLayout>
    );
}
