import type { UiError } from '../api/problem';
import { AppLink } from './AppLink';
import { errorText } from './errorText';
import { uiStrings } from './strings';
import styles from './ui.module.css';

export interface ErrorStateProps {
  error: UiError;
  onRetry?: () => void;
  back?: { href: string; label: string };
}

/** AC-9.1: the error, forbidden and not-found states. Announced as an alert; never colour alone. */
export function ErrorState({ error, onRetry, back }: ErrorStateProps) {
  const link = back ?? (error.kind === 'not-found' ? { href: '/', label: uiStrings.backToStart } : undefined);
  return (
    <div role="alert" className={styles.alert}>
      <p className={styles.alertTitle}>{errorText(error)}</p>
      {error.detail ? <p>{error.detail}</p> : null}
      {error.requestId ? <p className={styles.reference}>{uiStrings.reference(error.requestId)}</p> : null}
      {(error.retryable && onRetry) || link ? (
        <div className={styles.actions}>
          {error.retryable && onRetry ? (
            <button type="button" className={styles.secondary} onClick={onRetry}>
              {uiStrings.retry}
            </button>
          ) : null}
          {link ? <AppLink href={link.href}>{link.label}</AppLink> : null}
        </div>
      ) : null}
    </div>
  );
}
