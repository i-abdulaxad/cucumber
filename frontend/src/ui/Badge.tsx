import type {ReactNode} from 'react';

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger';

const tones: Record<BadgeTone, string> = {
    neutral: 'border-border bg-surface-hover text-text-muted',
    primary: 'border-border bg-primary-soft text-primary',
    success: 'border-border bg-status-passed-bg text-status-passed-fg',
    warning: 'border-border bg-status-pending-bg text-status-pending-fg',
    danger: 'border-border bg-status-failed-bg text-status-failed-fg',
};

/** DS-9 Badge: always carries text; an optional icon is decorative. */
export function Badge({tone = 'neutral', icon, children}: { tone?: BadgeTone; icon?: ReactNode; children: ReactNode }) {
    return (
        <span
            className={`inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-xs font-medium ${tones[tone]}`}>
      {icon}
            {children}
    </span>
    );
}
