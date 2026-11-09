import {useState, type FormEvent} from 'react';
import {useMutation, useQueryClient} from '@tanstack/react-query';
import {scenarioApi, unwrap} from '../../api/client';
import {splitFieldErrors, uiErrorOf} from '../../api/problem';
import type {components} from '../../api/generated/scenario-service';
import {Button} from '../../ui/Button';
import {Dialog} from '../../ui/Dialog';
import {InlineAlert} from '../../ui/InlineAlert';
import {TextField} from '../../ui/TextField';
import {errorText} from '../../ui/errorText';
import {showToast} from '../../ui/toastStore';
import {starterSource} from './queries';
import {treeStrings as t} from './strings';

/** AC-5.4: create a feature from a path; field errors stay at the path field. */
export function CreateFeatureDialog({projectId, isOpen, onOpenChange, onCreated}: {
    projectId: string;
    isOpen: boolean;
    onOpenChange: (open: boolean) => void;
    onCreated: (feature: components['schemas']['Feature']) => void;
}) {
    const queryClient = useQueryClient();
    const [path, setPath] = useState('');
    const [pathErrors, setPathErrors] = useState<string[] | undefined>();
    const [formError, setFormError] = useState<string | null>(null);

    const create = useMutation({
        mutationFn: (value: string) =>
            unwrap(scenarioApi.POST('/api/projects/{projectId}/features', {params: {path: {projectId}}, body: {path: value, source: starterSource(value)}})),
        onSuccess: (feature) => {
            void queryClient.invalidateQueries({queryKey: ['features', projectId]});
            void queryClient.invalidateQueries({queryKey: ['tags', projectId]});
            showToast('success', t.created);
            setPath('');
            onCreated(feature);
            onOpenChange(false);
        },
        onError: (error) => {
            const ui = uiErrorOf(error);
            if (ui.code === 'FEATURE_PATH_TAKEN') setPathErrors([t.pathTaken]);
            else if (ui.kind === 'validation') {
                const split = splitFieldErrors(ui, ['path', 'source']);
                setPathErrors(split.fields.path);
                setFormError([...(split.fields.source ?? []), ...split.form].join(' ') || null);
            } else setFormError(errorText(ui));
        },
    });

    function onSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setFormError(null);
        if (path.trim() === '') {
            setPathErrors([t.pathRequired]);
            return;
        }
        setPathErrors(undefined);
        create.mutate(path);
    }

    return (
        <Dialog isOpen={isOpen} onOpenChange={onOpenChange} title={t.createTitle} size="md">
            <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
                {formError ? (
                    <InlineAlert tone="danger" role="alert">
                        {formError}
                    </InlineAlert>
                ) : null}
                <TextField id="new-feature-path" label={t.pathLabel} hint={t.pathHint} value={path} onChange={setPath} errors={pathErrors} required/>
                <div className="flex justify-end gap-2">
                    <Button variant="secondary" onPress={() => onOpenChange(false)}>
                        {t.cancel}
                    </Button>
                    <Button type="submit" variant="primary" loading={create.isPending}>
                        {t.create}
                    </Button>
                </div>
            </form>
        </Dialog>
    );
}
