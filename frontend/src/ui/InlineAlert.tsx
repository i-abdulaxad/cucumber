import type {ReactNode} from 'react';
import {Info, Lock, TriangleExclamationPoint, type LucideIcon} from 'lucide-react';
import {cx} from './cx';

const tones = {
    info: {className: 'border-border bg-primary-soft', icon: Info, iconClass: 'text-primary'},
    readonly: {className: 'border-border bg-surface-hover', icon: Lock, iconClass: 'text-text-muted'},
    danger: {className: 'border-danger bg-danger-soft', icon: TriangleExclamationPoint, iconClass: 'text-danger'},
} satisfies Record<string, { className: string; icon: LucideIcon; iconClass: string }>;

/** A banner inside a panel: read-only notices, form-level errors. `role` is set by the owner when it is announced. */
export function InlineAlert({tone = 'info', children, role, actions}: { tone?: keyof typeof tones; children: ReactNode; role?: 'alert' | 'status'; actions?: ReactNode }) {
    const {className, icon: Icon, iconClass} = tones[tone];
    return (
        <div role={role} className={cx('flex items-start gap-3 rounded-md border px-3 py-2 text-base text-text', className)}>
            <Icon aria-hidden="true" size={16} className={cx('mt-0.5 shrink-0', iconClass)}/>
            <div className="min-w-0 flex-1">{children}</div>
            {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </div>
    );
}
