import {useState, type FormEvent} from 'react';
import {useMutation} from '@tanstack/react-query';
import {useRouter} from '@tanstack/react-router';
import {authApi, unwrap} from '../../api/client';
import {networkError, splitFieldErrors, uiErrorOf, type UiError} from '../../api/problem';
import {safeNext} from '../../auth/next';
import {lastEndReason, startSession} from '../../auth/session';
import {AppLink} from '../../ui/AppLink';
import {AuthLayout} from '../../ui/AuthLayout';
import {Button} from '../../ui/Button';
import {ErrorSummary, type SummaryItem} from '../../ui/ErrorSummary';
import {PageHeading} from '../../ui/PageHeading';
import {TextField} from '../../ui/TextField';
import {errorText} from '../../ui/errorText';
import {uiStrings} from '../../ui/strings';
import {sessionStrings as t} from './strings';
import {useFormErrors} from './useFormErrors';

type Field = 'email' | 'password';
const FIELDS: readonly Field[] = ['email', 'password'];
const ids: Record<Field, string> = {email: 'login-email', password: 'login-password'};

function summaryFor(error: UiError): SummaryItem[] {
    const items: SummaryItem[] = [{message: errorText(error)}];
    if (error.detail) items.push({message: error.detail});
    if (error.requestId) items.push({message: uiStrings.reference(error.requestId)});
    return items;
}

export function LoginPage({next}: { next: string | undefined }) {
    const router = useRouter();
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const errors = useFormErrors<Field>();
    const [endReason] = useState(lastEndReason);

    const login = useMutation({
        mutationFn: (body: { email: string; password: string }) => unwrap(authApi.POST('/api/auth/login', {body})),
    });

    function onSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (login.isPending) return;
        const missing: Partial<Record<Field, string[]>> = {};
        if (email.trim() === '') missing.email = [t.required.email];
        if (password === '') missing.password = [t.required.password];
        if (Object.keys(missing).length > 0) {
            errors.show(
                missing,
                FIELDS.flatMap((f) => (missing[f] ?? []).map((message) => ({message, fieldId: ids[f]}))),
            );
            return;
        }
        errors.clear();
        login.mutate(
            {email, password},
            {
                onSuccess: ({accessToken}) => {
                    if (!startSession(accessToken)) {
                        errors.show({}, summaryFor(networkError()));
                        return;
                    }
                    router.history.push(safeNext(next));
                },
                onError: (error) => {
                    const ui = uiErrorOf(error);
                    if (ui.code === 'INVALID_CREDENTIALS') {
                        setPassword('');
                        errors.show({}, [{message: t.invalidCredentials}]);
                    } else if (ui.kind === 'validation') {
                        const split = splitFieldErrors(ui, FIELDS);
                        errors.show(split.fields, [
                            ...FIELDS.flatMap((f) => (split.fields[f] ?? []).map((message) => ({
                                message,
                                fieldId: ids[f]
                            }))),
                            ...split.form.map((message) => ({message})),
                        ]);
                    } else {
                        errors.show({}, summaryFor(ui));
                    }
                },
            },
        );
    }

    return (
        <AuthLayout
            footer={
                <>
                    {t.noAccount} <AppLink href="/register">{t.toRegister}</AppLink>
                </>
            }
        >
            <div className="mb-6">
                <PageHeading>{t.loginTitle}</PageHeading>
            </div>
            {endReason === 'expired' || endReason === 'unauthorized' ? (
                <p role="status" className="mb-5 rounded-lg border border-border bg-primary-soft px-3 py-2 text-base text-text">
                    {endReason === 'expired' ? t.sessionExpired : t.sessionEnded}
                </p>
            ) : null}
            {errors.summary.length > 0 ? <ErrorSummary items={errors.summary} summaryRef={errors.summaryRef}/> : null}
            <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4" aria-busy={login.isPending}>
                <TextField id={ids.email} label={t.email} type="email" autoComplete="username" value={email}
                           onChange={setEmail} errors={errors.fields.email} required/>
                <TextField
                    id={ids.password}
                    label={t.password}
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={setPassword}
                    errors={errors.fields.password}
                    required
                />
                <div className="pt-2">
                    <Button type="submit" variant="primary" size="lg" fullWidth loading={login.isPending}>
                        {t.logIn}
                    </Button>
                </div>
                <p role="status" className="sr-only">
                    {login.isPending ? t.working : ''}
                </p>
            </form>
        </AuthLayout>
    );
}
