import {useEffect, useId, useMemo, useRef, useState} from 'react';
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {useBlocker} from '@tanstack/react-router';
import {Archive, ArchiveRestore, Save, SearchX} from 'lucide-react';
import type {Diagnostic} from '@codemirror/lint';
import type {components} from '../../api/generated/scenario-service';
import type {components as AuthComponents} from '../../api/generated/auth-service';
import {scenarioApi, unwrap} from '../../api/client';
import {splitFieldErrors, uiErrorOf, type ParseError, type UiError} from '../../api/problem';
import {hasProjectRole} from '../../auth/roles';
import {Badge} from '../../ui/Badge';
import {Button} from '../../ui/Button';
import {ConfirmDialog, Dialog} from '../../ui/Dialog';
import {EmptyState} from '../../ui/EmptyState';
import {ErrorState} from '../../ui/ErrorState';
import {InlineAlert} from '../../ui/InlineAlert';
import {Skeleton} from '../../ui/Skeleton';
import {Tab, TabList, TabPanel, Tabs} from '../../ui/Tabs';
import {TextField} from '../../ui/TextField';
import {errorText} from '../../ui/errorText';
import {showToast} from '../../ui/toastStore';
import {uiStrings} from '../../ui/strings';
import {gherkinSummary, toDiagnostics} from './columns';
import {GherkinEditor} from './GherkinEditor';
import {editorStrings as t} from './strings';

type Feature = components['schemas']['Feature'];
type Project = AuthComponents['schemas']['Project'];
type Field = 'path' | 'message' | 'source';
const FIELDS: readonly Field[] = ['path', 'message', 'source'];

export const featureKey = (projectId: string, featureId: string) => ['feature', projectId, featureId] as const;

export interface FeatureEditorProps {
    projectId: string;
    featureId: string;
    /** The caller's project from auth-service (D5: `myRole`), or null while it loads. */
    project: Project | null;
    onClose: () => void;
}

interface Draft {
    source: string;
    path: string;
    message: string;
}

function readOnlyReason(project: Project | null, feature: Feature | undefined, archivedAfterSave: boolean): string | null {
    if (!project || !feature) return null;
    if (project.archivedAt) return t.readOnlyProject;
    if (feature.archivedAt || archivedAfterSave) return t.readOnlyFeature;
    if (!hasProjectRole(project.myRole, 'MEMBER')) return t.readOnlyRole(project.myRole);
    return null;
}

/** F3 / D11: the feature editor (Detail page template with Tabs). */
export function FeatureEditor({projectId, featureId, project, onClose}: FeatureEditorProps) {
    const queryClient = useQueryClient();
    const liveId = useId();
    const bannerId = useId();
    const query = useQuery({
        queryKey: featureKey(projectId, featureId),
        queryFn: () => unwrap(scenarioApi.GET('/api/projects/{projectId}/features/{featureId}', {params: {path: {projectId, featureId}}})),
    });
    const feature = query.data;

    const [docKey, setDocKey] = useState('');
    const [draft, setDraft] = useState<Draft>({source: '', path: '', message: ''});
    const [baseVersion, setBaseVersion] = useState(0);
    const [loadedSource, setLoadedSource] = useState('');
    const [loadedPath, setLoadedPath] = useState('');
    const [diagnostics, setDiagnosticsState] = useState<Diagnostic[]>([]);
    const [live, setLive] = useState('');
    const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string[]>>>({});
    const [formError, setFormError] = useState<UiError | null>(null);
    const [conflictVersion, setConflictVersion] = useState<number | null>(null);
    const [unsavedText, setUnsavedText] = useState<string | null>(null);
    const [archivedAfterSave, setArchivedAfterSave] = useState(false);
    const [confirmArchive, setConfirmArchive] = useState(false);
    const [restoreError, setRestoreError] = useState<string | null>(null);

    function load(f: Feature) {
        setDraft({source: f.source, path: f.path, message: ''});
        setLoadedSource(f.source);
        setLoadedPath(f.path);
        setBaseVersion(f.currentVersion);
        setDocKey(`${f.id}:${f.currentVersion}:${Date.now()}`);
        setDiagnosticsState([]);
        setFieldErrors({});
        setFormError(null);
    }

    // Load the feature the first time it arrives (and when another feature is opened).
    const loadedFor = useRef<string | null>(null);
    useEffect(() => {
        if (feature && loadedFor.current !== feature.id) {
            loadedFor.current = feature.id;
            load(feature);
        }
    }, [feature]);

    const reason = readOnlyReason(project, feature, archivedAfterSave);
    const readOnly = reason !== null || project === null;
    const dirty = feature !== undefined && (draft.source !== loadedSource || draft.path !== loadedPath);
    const dirtyRef = useRef(dirty);
    dirtyRef.current = dirty;

    const blocker = useBlocker({
        shouldBlockFn: ({current, next}) => {
            if (!dirtyRef.current) return false;
            const currentFeature = 'feature' in current.search ? current.search.feature : undefined;
            const nextFeature = 'feature' in next.search ? next.search.feature : undefined;
            return current.pathname !== next.pathname || currentFeature !== nextFeature;
        },
        enableBeforeUnload: () => dirtyRef.current,
        withResolver: true,
    });

    const save = useMutation({
        mutationFn: (body: components['schemas']['SaveFeatureRequest']) =>
            unwrap(scenarioApi.PUT('/api/projects/{projectId}/features/{featureId}', {params: {path: {projectId, featureId}}, body})),
        onSuccess: (saved) => {
            queryClient.setQueryData(featureKey(projectId, featureId), saved);
            void queryClient.invalidateQueries({queryKey: ['features', projectId]});
            if (saved.currentVersion === baseVersion) {
                showToast('info', t.noChanges);
                setDraft((d) => ({...d, message: ''}));
                return;
            }
            if (saved.source !== draft.source) {
                // The server stores exactly what was sent; if it ever differs, show what is stored.
                load(saved);
            } else {
                setLoadedSource(saved.source);
                setLoadedPath(saved.path);
                setBaseVersion(saved.currentVersion);
                setDraft((d) => ({...d, path: saved.path, message: ''}));
            }
            showToast('success', t.saved);
        },
        onError: (error) => {
            const ui = uiErrorOf(error);
            const problem = ui.problem;
            if (ui.code === 'GHERKIN_INVALID' && problem) {
                const errors: ParseError[] = problem.parseErrors;
                setDiagnosticsState(toDiagnostics(draft.source, errors));
                setLive(gherkinSummary(errors));
            } else if (ui.code === 'VERSION_CONFLICT') {
                setConflictVersion(problem?.currentVersion ?? baseVersion + 1);
            } else if (ui.code === 'FEATURE_PATH_TAKEN') {
                setFieldErrors({path: [t.pathTaken]});
            } else if (ui.code === 'FEATURE_ARCHIVED') {
                setArchivedAfterSave(true);
                void query.refetch();
            } else if (ui.kind === 'validation') {
                const split = splitFieldErrors(ui, FIELDS);
                setFieldErrors(split.fields);
                if (split.form.length > 0) setFormError(ui);
            } else {
                setFormError(ui);
            }
        },
    });

    function onSave() {
        if (readOnly || save.isPending || !feature) return;
        // Diagnostics stay until the next save attempt (D11).
        setDiagnosticsState([]);
        setLive('');
        setFieldErrors({});
        setFormError(null);
        save.mutate({source: draft.source, path: draft.path, baseVersion, ...(draft.message.trim() ? {message: draft.message} : {})});
    }

    async function reloadLatest() {
        setUnsavedText(draft.source);
        setConflictVersion(null);
        const latest = await queryClient.fetchQuery({
            queryKey: featureKey(projectId, featureId),
            queryFn: () => unwrap(scenarioApi.GET('/api/projects/{projectId}/features/{featureId}', {params: {path: {projectId, featureId}}})),
            staleTime: 0,
        });
        load(latest);
    }

    const archive = useMutation({
        mutationFn: () => unwrap(scenarioApi.POST('/api/projects/{projectId}/features/{featureId}/archive', {params: {path: {projectId, featureId}}})),
        onSuccess: (archived) => {
            queryClient.setQueryData(featureKey(projectId, featureId), archived);
            void queryClient.invalidateQueries({queryKey: ['features', projectId]});
            void queryClient.invalidateQueries({queryKey: ['tags', projectId]});
            setConfirmArchive(false);
            showToast('success', t.archived);
        },
        onError: (error) => {
            setConfirmArchive(false);
            setFormError(uiErrorOf(error));
        },
    });

    const restore = useMutation({
        mutationFn: () => unwrap(scenarioApi.POST('/api/projects/{projectId}/features/{featureId}/restore', {params: {path: {projectId, featureId}}})),
        onSuccess: (restored) => {
            queryClient.setQueryData(featureKey(projectId, featureId), restored);
            void queryClient.invalidateQueries({queryKey: ['features', projectId]});
            void queryClient.invalidateQueries({queryKey: ['tags', projectId]});
            setArchivedAfterSave(false);
            setRestoreError(null);
            showToast('success', t.restored);
        },
        onError: (error) => {
            const ui = uiErrorOf(error);
            setRestoreError(ui.code === 'FEATURE_PATH_TAKEN' ? t.pathTaken : errorText(ui));
        },
    });

    const describedBy = useMemo(() => [liveId, reason ? bannerId : null].filter(Boolean).join(' '), [liveId, bannerId, reason]);

    if (query.isPending) {
        return (
            <div aria-busy="true" className="flex flex-col gap-3 p-4">
                <span className="sr-only">{t.loading}</span>
                <Skeleton width="third"/>
                <Skeleton shape="row"/>
                <Skeleton shape="block"/>
                <Skeleton shape="block"/>
            </div>
        );
    }
    if (query.isError) {
        const error = uiErrorOf(query.error);
        if (error.kind === 'not-found') {
            return (
                <div role="alert">
                    <EmptyState
                        icon={SearchX}
                        title={t.notFound}
                        action={
                            <Button variant="secondary" onPress={onClose}>
                                {t.backToTree}
                            </Button>
                        }
                    />
                </div>
            );
        }
        return <ErrorState error={error} onRetry={() => void query.refetch()}/>;
    }
    if (!feature) return null;

    const canArchive = !readOnly && !feature.archivedAt;
    const canRestore =
        project !== null && !project.archivedAt && hasProjectRole(project.myRole, 'MEMBER') && (feature.archivedAt !== null || archivedAfterSave);

    return (
        <div className="flex h-full min-h-0 flex-col">
            <header className="flex flex-col gap-3 border-b border-border p-4">
                <div className="flex flex-wrap items-center gap-2">
                    <h2 className="min-w-0 truncate text-lg font-semibold text-text">{feature.name || feature.path}</h2>
                    <Badge>{t.version(baseVersion)}</Badge>
                    {dirty ? (
                        <Badge tone="warning">
                            <span aria-hidden="true">●</span> {t.unsaved}
                        </Badge>
                    ) : null}
                    <div className="ml-auto flex items-center gap-2">
                        {canRestore ? (
                            <Button variant="secondary" onPress={() => restore.mutate()} loading={restore.isPending}>
                                <ArchiveRestore aria-hidden="true" size={16}/>
                                {t.restore}
                            </Button>
                        ) : null}
                        {canArchive ? (
                            <Button variant="ghost" onPress={() => setConfirmArchive(true)}>
                                <Archive aria-hidden="true" size={16}/>
                                {t.archive}
                            </Button>
                        ) : null}
                        {readOnly ? null : (
                            <Button variant="primary" onPress={onSave} loading={save.isPending}>
                                <Save aria-hidden="true" size={16}/>
                                {t.save}
                            </Button>
                        )}
                    </div>
                </div>
                {reason ? (
                    <div id={bannerId}>
                        <InlineAlert tone="readonly">{reason}</InlineAlert>
                    </div>
                ) : null}
                {restoreError ? (
                    <InlineAlert tone="danger" role="alert">
                        {restoreError}
                    </InlineAlert>
                ) : null}
                {formError ? (
                    <InlineAlert tone="danger" role="alert">
                        <p>{errorText(formError)}</p>
                        {formError.detail ? <p className="text-text-muted">{formError.detail}</p> : null}
                        {formError.problem?.errors.map((e, i) => (
                            <p key={i}>{e.message}</p>
                        ))}
                        {formError.requestId ? <p className="font-mono text-xs text-text-muted">{uiStrings.reference(formError.requestId)}</p> : null}
                    </InlineAlert>
                ) : null}
                {readOnly ? null : (
                    <div className="grid gap-3 md:grid-cols-2">
                        <TextField
                            id="feature-path"
                            label={t.pathLabel}
                            value={draft.path}
                            onChange={(path) => setDraft((d) => ({...d, path}))}
                            errors={fieldErrors.path}
                        />
                        <TextField
                            id="feature-message"
                            label={t.messageLabel}
                            value={draft.message}
                            onChange={(message) => setDraft((d) => ({...d, message}))}
                            errors={fieldErrors.message}
                            hint={t.saveHint}
                        />
                    </div>
                )}
                {fieldErrors.source ? (
                    <InlineAlert tone="danger" role="alert">
                        {fieldErrors.source.join(' ')}
                    </InlineAlert>
                ) : null}
            </header>
            <p id={liveId} role="status" aria-live="polite" className="sr-only">
                {live}
            </p>
            <Tabs>
                <TabList label={t.tabsLabel}>
                    <Tab id="editor">{t.tabEditor}</Tab>
                    <Tab id="history">{t.tabHistory}</Tab>
                    <Tab id="test-cases">{t.tabTestCases}</Tab>
                </TabList>
                <TabPanel id="editor">
                    <div className="flex min-h-0 flex-1 flex-col">
                        {unsavedText !== null ? (
                            <section aria-label={t.yourText} className="flex max-h-48 flex-col gap-2 border-b border-border bg-surface-hover p-3">
                                <div className="flex items-center justify-between gap-2">
                                    <h3 className="text-sm font-semibold text-text">{t.yourText}</h3>
                                    <Button size="sm" variant="secondary" onPress={() => setUnsavedText(null)}>
                                        {t.dismiss}
                                    </Button>
                                </div>
                                <p className="text-xs text-text-muted">{t.yourTextHelp}</p>
                                <pre className="min-h-0 overflow-auto rounded-md border border-border bg-surface-raised p-2 font-mono text-xs text-text">
                                    {unsavedText}
                                </pre>
                            </section>
                        ) : null}
                        <div className="min-h-64 flex-1">
                            {docKey ? (
                                <GherkinEditor
                                    docKey={docKey}
                                    initialDoc={draft.source}
                                    readOnly={readOnly}
                                    diagnostics={diagnostics}
                                    label={t.sourceLabel}
                                    describedBy={describedBy}
                                    onChange={(source) => setDraft((d) => ({...d, source}))}
                                    onSave={onSave}
                                />
                            ) : null}
                        </div>
                    </div>
                </TabPanel>
                <TabPanel id="history">
                    <p className="p-4 text-base text-text-muted">{t.laterBatch}</p>
                </TabPanel>
                <TabPanel id="test-cases">
                    <p className="p-4 text-base text-text-muted">{t.laterBatch}</p>
                </TabPanel>
            </Tabs>

            <Dialog
                isOpen={conflictVersion !== null}
                onOpenChange={(open) => {
                    if (!open) setConflictVersion(null);
                }}
                title={t.conflictTitle}
                role="alertdialog"
                footer={
                    <>
                        <Button variant="secondary" onPress={() => setConflictVersion(null)}>
                            {t.keepEditing}
                        </Button>
                        <Button variant="primary" onPress={() => void reloadLatest()}>
                            {t.reload}
                        </Button>
                    </>
                }
            >
                <p>{conflictVersion !== null ? t.conflictBody(conflictVersion) : null}</p>
            </Dialog>

            <ConfirmDialog
                isOpen={confirmArchive}
                onOpenChange={setConfirmArchive}
                title={t.archiveTitle}
                confirmLabel={t.archive}
                cancelLabel={t.cancel}
                danger
                loading={archive.isPending}
                onConfirm={() => archive.mutate()}
            >
                <p>{t.archiveBody(feature.path)}</p>
            </ConfirmDialog>

            <ConfirmDialog
                isOpen={blocker.status === 'blocked'}
                onOpenChange={(open) => {
                    if (!open && blocker.status === 'blocked') blocker.reset();
                }}
                title={t.leaveTitle}
                confirmLabel={t.discard}
                cancelLabel={t.keepEditing}
                danger
                onConfirm={() => {
                    if (blocker.status === 'blocked') {
                        dirtyRef.current = false;
                        blocker.proceed();
                    }
                }}
            >
                <p>{t.leaveBody}</p>
            </ConfirmDialog>
        </div>
    );
}
