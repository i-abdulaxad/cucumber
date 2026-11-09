import type {ReactNode} from 'react';
import {Inbox, type LucideIcon} from 'lucide-react';

/** DS-8 EmptyState: an icon in a muted circle, a title, why it's empty, and the action only if the role allows it. */
export function EmptyState({title, children, action, icon: Icon = Inbox}: {
    title: string;
    children?: ReactNode;
    action?: ReactNode;
    icon?: LucideIcon
}) {
    return (
        <div className="flex flex-col items-center px-4 py-12 text-center">
      <span
          className="mb-4 inline-flex size-10 items-center justify-center rounded-full bg-surface-hover text-text-muted">
        <Icon aria-hidden="true" size={24}/>
      </span>
            <p className="text-md font-semibold text-text">{title}</p>
            {children ? <div className="mt-1 max-w-md text-base text-text-muted">{children}</div> : null}
            {action ? <div className="mt-4 flex items-center gap-3">{action}</div> : null}
        </div>
    );
}
