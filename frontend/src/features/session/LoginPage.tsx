import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useRouter } from '@tanstack/react-router';
import { authApi, unwrap } from '../../api/client';
import { networkError, splitFieldErrors, uiErrorOf, type UiError } from '../../api/problem';
import { safeNext } from '../../auth/next';
import { lastEndReason, startSession } from '../../auth/session';
import { AppLink } from '../../ui/AppLink';
import { ErrorSummary, type SummaryItem } from '../../ui/ErrorSummary';
import { PageHeading } from '../../ui/PageHeading';
import { TextField } from '../../ui/TextField';
import { errorText } from '../../ui/errorText';
import { uiStrings } from '../../ui/strings';
import uiStyles from '../../ui/ui.module.css';
import { sessionStrings as t } from './strings';
import styles from './session.module.css';
import { useFormErrors } from './useFormErrors';

type Field = 'email' | 'password';
const FIELDS: readonly Field[] = ['email', 'password'];
const ids: Record<Field, string> = { email: 'login-email', password: 'login-password' };

function summaryFor(error: UiError): SummaryItem[] {
  const items: SummaryItem[] = [{ message: errorText(error) }];
  if (error.detail) items.push({ message: error.detail });
  if (error.requestId) items.push({ message: uiStrings.reference(error.requestId) });
  return items;
}

export function LoginPage({ next }: { next: string | undefined }) {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const errors = useFormErrors<Field>();
  const [endReason] = useState(lastEndReason);

  const login = useMutation({
    mutationFn: (body: { email: string; password: string }) => unwrap(authApi.POST('/api/auth/login', { body })),
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
        FIELDS.flatMap((f) => (missing[f] ?? []).map((message) => ({ message, fieldId: ids[f] }))),
      );
      return;
    }
    errors.clear();
    login.mutate(
      { email, password },
      {
        onSuccess: ({ accessToken }) => {
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
            errors.show({}, [{ message: t.invalidCredentials }]);
          } else if (ui.kind === 'validation') {
            const split = splitFieldErrors(ui, FIELDS);
            errors.show(split.fields, [
              ...FIELDS.flatMap((f) => (split.fields[f] ?? []).map((message) => ({ message, fieldId: ids[f] }))),
              ...split.form.map((message) => ({ message })),
            ]);
          } else {
            errors.show({}, summaryFor(ui));
          }
        },
      },
    );
  }

  return (
    <>
      <PageHeading>{t.loginTitle}</PageHeading>
      {endReason === 'expired' || endReason === 'unauthorized' ? (
        <p role="status" className={styles.notice}>
          {endReason === 'expired' ? t.sessionExpired : t.sessionEnded}
        </p>
      ) : null}
      {errors.summary.length > 0 ? <ErrorSummary items={errors.summary} summaryRef={errors.summaryRef} /> : null}
      <form noValidate onSubmit={onSubmit} className={styles.form} aria-busy={login.isPending}>
        <TextField id={ids.email} label={t.email} type="email" autoComplete="username" value={email} onChange={setEmail} errors={errors.fields.email} required />
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
        <button type="submit" className={uiStyles.button}>
          {t.logIn}
        </button>
        <p role="status" className="visually-hidden">
          {login.isPending ? t.working : ''}
        </p>
      </form>
      <p className={styles.alt}>
        {t.noAccount} <AppLink href="/register">{t.toRegister}</AppLink>
      </p>
    </>
  );
}
