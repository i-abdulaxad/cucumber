import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useRouter } from '@tanstack/react-router';
import { authApi, unwrap } from '../../api/client';
import { networkError, splitFieldErrors, uiErrorOf, type UiError } from '../../api/problem';
import { startSession } from '../../auth/session';
import { AppLink } from '../../ui/AppLink';
import { ErrorSummary, type SummaryItem } from '../../ui/ErrorSummary';
import { PageHeading } from '../../ui/PageHeading';
import { TextField } from '../../ui/TextField';
import { errorText } from '../../ui/errorText';
import { uiStrings } from '../../ui/strings';
import uiStyles from '../../ui/ui.module.css';
import { sessionStrings as t } from './strings';
import styles from './session.module.css';
import { codePoints, useFormErrors, utf8Bytes } from './useFormErrors';

type Field = 'fullName' | 'email' | 'password';
const FIELDS: readonly Field[] = ['fullName', 'email', 'password'];
const ids: Record<Field, string> = { fullName: 'register-full-name', email: 'register-email', password: 'register-password' };

/** Which step of "register, then log in" failed. */
class RegisterFlowError extends Error {
  readonly step: 'register' | 'login';
  readonly ui: UiError;

  constructor(step: 'register' | 'login', ui: UiError) {
    super(step);
    this.step = step;
    this.ui = ui;
  }
}

function summaryFor(error: UiError): SummaryItem[] {
  const items: SummaryItem[] = [{ message: errorText(error) }];
  if (error.detail) items.push({ message: error.detail });
  if (error.requestId) items.push({ message: uiStrings.reference(error.requestId) });
  return items;
}

/** D8: the client checks only required fields and the contract's length limits; the server checks the rest. */
function clientCheck(values: Record<Field, string>): Partial<Record<Field, string[]>> {
  const out: Partial<Record<Field, string[]>> = {};
  if (values.fullName.trim() === '') out.fullName = [t.required.fullName];
  else if (codePoints(values.fullName) > 100) out.fullName = [t.tooLong.fullName];
  if (values.email.trim() === '') out.email = [t.required.email];
  else if (values.email.length > 254) out.email = [t.tooLong.email];
  if (values.password === '') out.password = [t.required.password];
  else if (utf8Bytes(values.password) > 72) out.password = [t.tooLong.password];
  return out;
}

export function RegisterPage() {
  const router = useRouter();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [emailTaken, setEmailTaken] = useState(false);
  const [loginNeeded, setLoginNeeded] = useState(false);
  const errors = useFormErrors<Field>();

  const register = useMutation({
    mutationFn: async (body: Record<Field, string>): Promise<string> => {
      try {
        await unwrap(authApi.POST('/api/auth/register', { body }));
      } catch (error) {
        throw new RegisterFlowError('register', uiErrorOf(error));
      }
      try {
        const { accessToken } = await unwrap(authApi.POST('/api/auth/login', { body: { email: body.email, password: body.password } }));
        return accessToken;
      } catch (error) {
        throw new RegisterFlowError('login', uiErrorOf(error));
      }
    },
  });

  function fieldItems(fields: Partial<Record<Field, string[]>>): SummaryItem[] {
    return FIELDS.flatMap((f) => (fields[f] ?? []).map((message) => ({ message, fieldId: ids[f] })));
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (register.isPending) return;
    const values = { fullName, email, password };
    const local = clientCheck(values);
    setEmailTaken(false);
    setLoginNeeded(false);
    if (Object.keys(local).length > 0) {
      errors.show(local, fieldItems(local));
      return;
    }
    errors.clear();
    register.mutate(values, {
      onSuccess: (accessToken) => {
        if (!startSession(accessToken)) {
          errors.show({}, summaryFor(networkError()));
          return;
        }
        router.history.push('/orgs');
      },
      onError: (failure) => {
        if (failure instanceof RegisterFlowError && failure.step === 'login') {
          setLoginNeeded(true);
          errors.show({}, [{ message: t.createdLogInNow }]);
          return;
        }
        const ui = failure instanceof RegisterFlowError ? failure.ui : networkError();
        if (ui.code === 'EMAIL_ALREADY_REGISTERED') {
          setEmailTaken(true);
          errors.show({}, [{ message: t.emailTaken }]);
        } else if (ui.kind === 'validation') {
          const split = splitFieldErrors(ui, FIELDS);
          errors.show(split.fields, [...fieldItems(split.fields), ...split.form.map((message) => ({ message }))]);
        } else {
          errors.show({}, summaryFor(ui));
        }
      },
    });
  }

  return (
    <>
      <PageHeading>{t.registerTitle}</PageHeading>
      {errors.summary.length > 0 ? (
        <ErrorSummary items={errors.summary} summaryRef={errors.summaryRef}>
          {emailTaken || loginNeeded ? (
            <p>
              <AppLink href="/login">{t.toLogin}</AppLink>
            </p>
          ) : null}
        </ErrorSummary>
      ) : null}
      <form noValidate onSubmit={onSubmit} className={styles.form} aria-busy={register.isPending}>
        <TextField id={ids.fullName} label={t.fullName} autoComplete="name" value={fullName} onChange={setFullName} errors={errors.fields.fullName} required />
        <TextField id={ids.email} label={t.email} type="email" autoComplete="email" value={email} onChange={setEmail} errors={errors.fields.email} required />
        <TextField
          id={ids.password}
          label={t.password}
          type="password"
          autoComplete="new-password"
          hint={t.passwordHint}
          value={password}
          onChange={setPassword}
          errors={errors.fields.password}
          required
        />
        <button type="submit" className={uiStyles.button}>
          {t.createAccount}
        </button>
        <p role="status" className="visually-hidden">
          {register.isPending ? t.working : ''}
        </p>
      </form>
      <p className={styles.alt}>
        {t.haveAccount} <AppLink href="/login">{t.toLogin}</AppLink>
      </p>
    </>
  );
}
