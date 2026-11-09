import {lazy, Suspense, useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {useNavigate} from '@tanstack/react-router';
import {ArrowLeft, Archive, FileText, Plus, Tags} from 'lucide-react';
import {uiErrorOf} from '../../api/problem';
import {hasProjectRole} from '../../auth/roles';
import {useWorkspace} from '../../app/useWorkspace';
import {Button} from '../../ui/Button';
import type {Crumb} from '../../ui/Breadcrumbs';
import {EmptyState} from '../../ui/EmptyState';
import {ErrorState} from '../../ui/ErrorState';
import {PageHeader} from '../../ui/PageHeader';
import {SkeletonRows} from '../../ui/Skeleton';
import {Tab, TabList, TabPanel, Tabs} from '../../ui/Tabs';
import {ToastRegion} from '../../ui/Toast';
import {ToggleChip} from '../../ui/ToggleChip';
import {cx} from '../../ui/cx';
import {uiStrings} from '../../ui/strings';
import {CreateFeatureDialog} from './CreateFeatureDialog';
import {FeatureTree} from './FeatureTree';
import {featuresKey, listFeatures, listTags, tagsKey} from './queries';
import {treeStrings as t} from './strings';

// The editor and CodeMirror load only when a feature is opened (NFR: code-split).
const FeatureEditor = lazy(() => import('../editor/FeatureEditor').then((m) => ({default: m.FeatureEditor})));

export interface FeaturesSearch {
    tag?: string;
    view?: 'archived';
    feature?: string;
}

/** F4 / D10: the feature tree beside the editor; the view, tag and selected feature live in the URL. */
export function FeaturesPage({projectId, search}: { projectId: string; search: FeaturesSearch }) {
    const navigate = useNavigate();
    const workspace = useWorkspace();
    const project = workspace.project;
    const canWrite = project !== null && project.archivedAt === null && hasProjectRole(project.myRole, 'MEMBER');
    const archived = search.view === 'archived';
    const [creating, setCreating] = useState(false);

    const features = useQuery({queryKey: featuresKey(projectId, archived, search.tag), queryFn: () => listFeatures(projectId, archived, search.tag)});
    const tags = useQuery({queryKey: tagsKey(projectId), queryFn: () => listTags(projectId)});

    function setSearch(patch: Partial<Record<keyof FeaturesSearch, string | undefined>>) {
        void navigate({
            to: '/projects/$projectId/features',
            params: {projectId},
            search: (prev) => {
                const next = {...prev, ...patch};
                return {
                    ...(next.tag ? {tag: next.tag} : {}),
                    ...(next.view === 'archived' ? {view: 'archived' as const} : {}),
                    ...(next.feature ? {feature: next.feature} : {}),
                };
            },
        });
    }

    const crumbs: Crumb[] = [];
    if (workspace.org && workspace.orgId) crumbs.push({label: workspace.org.name, href: `/orgs/${workspace.orgId}/projects`});
    if (project) crumbs.push({label: project.name, href: `/projects/${project.id}/features`});
    crumbs.push({label: t.title});

    function treeArea() {
        if (features.isPending) {
            return (
                <div role="status" className="min-h-0 flex-1">
                    <span className="sr-only">{uiStrings.loading}</span>
                    <SkeletonRows rows={6}/>
                </div>
            );
        }
        if (features.isError) return <ErrorState error={uiErrorOf(features.error)} onRetry={() => void features.refetch()}/>;
        if (features.data.length === 0) {
            if (archived) return <EmptyState icon={Archive} title={t.noArchived}/>;
            if (search.tag) return <EmptyState icon={Tags} title={t.noTagged(search.tag)}/>;
            return (
                <EmptyState
                    icon={FileText}
                    title={t.noFeatures}
                    action={
                        canWrite ? (
                            <Button variant="primary" onPress={() => setCreating(true)}>
                                <Plus aria-hidden="true" size={16}/>
                                {t.newFeature}
                            </Button>
                        ) : undefined
                    }
                >
                    <p>{canWrite ? t.noFeaturesHelp : t.noFeaturesReader}</p>
                </EmptyState>
            );
        }
        return (
            <div className="min-h-0 flex-1">
                <FeatureTree features={features.data} selectedId={search.feature ?? null} onOpen={(id) => setSearch({feature: id})}/>
            </div>
        );
    }

    return (
        <>
            <PageHeader
                title={t.title}
                breadcrumbs={crumbs}
                actions={
                    canWrite && features.isSuccess && features.data.length > 0 ? (
                        <Button variant="primary" onPress={() => setCreating(true)}>
                            <Plus aria-hidden="true" size={16}/>
                            {t.newFeature}
                        </Button>
                    ) : undefined
                }
            />
            <div className="grid gap-4 lg:workspace-height lg:grid-cols-12">
                <section
                    aria-label={t.panelLabel}
                    aria-busy={features.isPending}
                    className={cx(
                        'flex h-112 min-h-0 flex-col overflow-hidden rounded-lg border border-border bg-surface-raised shadow-sm lg:col-span-4 lg:h-auto',
                        search.feature && 'hidden lg:flex',
                    )}
                >
                    <Tabs selectedKey={archived ? 'archived' : 'active'} onSelectionChange={(key) => setSearch({view: key === 'archived' ? 'archived' : undefined})}>
                        <TabList label={t.viewsLabel}>
                            <Tab id="active">{t.active}</Tab>
                            <Tab id="archived">{t.archived}</Tab>
                        </TabList>
                        {search.tag ? (
                            <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-1.5 text-xs text-text-muted">
                                <span>{t.tagFilter(search.tag)}</span>
                                <Button variant="ghost" size="sm" onPress={() => setSearch({tag: undefined})}>
                                    {t.clearFilter}
                                </Button>
                            </div>
                        ) : null}
                        {tags.data && tags.data.length > 0 ? (
                            <div role="group" aria-label={t.tagsLabel} className="flex flex-wrap gap-1.5 border-b border-border px-4 py-2">
                                {tags.data.map((tag) => (
                                    <ToggleChip
                                        key={tag.name}
                                        isSelected={search.tag === tag.name}
                                        onChange={(selected) => setSearch({tag: selected ? tag.name : undefined})}
                                    >
                                        {tag.name}
                                    </ToggleChip>
                                ))}
                            </div>
                        ) : null}
                        <TabPanel id="active">{archived ? null : treeArea()}</TabPanel>
                        <TabPanel id="archived">{archived ? treeArea() : null}</TabPanel>
                    </Tabs>
                </section>
                <section
                    aria-label={t.selectFeature}
                    className={cx(
                        'min-h-0 overflow-hidden rounded-lg border border-border bg-surface-raised shadow-sm lg:col-span-8',
                        !search.feature && 'hidden lg:block',
                    )}
                >
                    {search.feature ? (
                        <div className="flex h-full min-h-0 flex-col">
                            <div className="border-b border-border p-2 lg:hidden">
                                <Button variant="ghost" size="sm" onPress={() => setSearch({feature: undefined})}>
                                    <ArrowLeft aria-hidden="true" size={16}/>
                                    {t.backToTree}
                                </Button>
                            </div>
                            <Suspense fallback={<SkeletonRows rows={3}/>}>
                                <FeatureEditor
                                    key={search.feature}
                                    projectId={projectId}
                                    featureId={search.feature}
                                    project={project}
                                    onClose={() => setSearch({feature: undefined})}
                                />
                            </Suspense>
                        </div>
                    ) : (
                        <EmptyState icon={FileText} title={t.selectFeature}>
                            <p>{t.selectFeatureHelp}</p>
                        </EmptyState>
                    )}
                </section>
            </div>
            <CreateFeatureDialog
                projectId={projectId}
                isOpen={creating}
                onOpenChange={setCreating}
                onCreated={(feature) => setSearch({feature: feature.id, view: undefined, tag: undefined})}
            />
            <ToastRegion/>
        </>
    );
}
