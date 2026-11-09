import {
    RotateCw,
    SearchX,
    ServerCrash,
    ShieldBan,
    TriangleExclamationPoint,
    WifiOff,
    type LucideIcon
} from 'lucide-react';
import type {UiError, UiErrorKind} from '../api/problem';
import {AppLink} from './AppLink';
import {Button} from './Button';
import {errorText} from './errorText';
import {uiStrings} from './strings';

export interface ErrorStateProps {
    error: UiError;
    onRetry?: () => void;
    back?: { href: string; label: string };
}

const icons: Partial<Record<UiErrorKind, LucideIcon>> = {
    forbidden: ShieldBan,
    refused: ShieldBan,
    'not-found': SearchX,
    unavailable: WifiOff,
    network: WifiOff,
    server: ServerCrash,
};

/** AC-9.1 / DS-8: the error, forbidden and not-found states, centred, announced as an alert; never colour alone. */
export function ErrorState({error, onRetry, back}: ErrorStateProps) {
    const link = back ?? (error.kind === 'not-found' ? {href: '/', label: uiStrings.backToStart} : undefined);
    const Icon = icons[error.kind] ?? TriangleExclamationPoint;
    const retry = error.retryable && onRetry;
    return (
        <div role="alert" className="flex flex-col items-center px-4 py-12 text-center">
      <span
          className="mb-4 inline-flex size-10 items-center justify-center rounded-full bg-surface-hover text-text-muted">
        <Icon aria-hidden="true" size={20}/>
      </span>
            <p className="text-md font-semibold text-text">{errorText(error)}</p>
            {error.detail ? <p className="mt-1 max-w-md text-base text-text-muted">{error.detail}</p> : null}
            {error.requestId ?
                <p className="mt-2 font-mono text-xs text-text-muted">{uiStrings.reference(error.requestId)}</p> : null}
            {retry || link ? (
                <div className="mt-4 flex items-center gap-3">
                    {retry ? (
                        <Button variant="secondary" onPress={onRetry}>
                            <RotateCw aria-hidden="true" size={16}/>
                            {uiStrings.retry}
                        </Button>
                    ) : null}
                    {link ? <AppLink href={link.href}>{link.label}</AppLink> : null}
                </div>
            ) : null}
        </div>
    );
}
