import type {ReactNode} from 'react';
import {cx} from './cx';

/** DS-8 Card: a raised surface with a subtle border and the `sm` elevation. */
export function Card({children, padding = 'md', title, actions}: {
    children: ReactNode;
    padding?: 'none' | 'md' | 'lg';
    title?: string;
    actions?: ReactNode
}) {
    return (
        <section className="overflow-hidden rounded-lg border border-border bg-surface-raised shadow-sm">
            {title ? (
                <header className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
                    <h2 className="text-md font-semibold">{title}</h2>
                    {actions}
                </header>
            ) : null}
            <div className={cx(padding === 'md' && 'p-4', padding === 'lg' && 'p-6')}>{children}</div>
        </section>
    );
}
