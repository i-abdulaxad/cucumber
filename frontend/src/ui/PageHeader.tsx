import type {ReactNode} from 'react';
import {Breadcrumbs, type Crumb} from './Breadcrumbs';
import {PageHeading} from './PageHeading';

/** DS-8 PageHeader: breadcrumbs, the h1, an optional description, and the primary action on the right. */
export function PageHeader({title, description, breadcrumbs, actions}: {
    title: string;
    description?: string;
    breadcrumbs?: readonly Crumb[];
    actions?: ReactNode
}) {
    return (
        <header className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div className="flex min-w-0 flex-col gap-1.5">
                {breadcrumbs && breadcrumbs.length > 0 ? <Breadcrumbs items={breadcrumbs}/> : null}
                <PageHeading>{title}</PageHeading>
                {description ? <p className="text-base text-text-muted">{description}</p> : null}
            </div>
            {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </header>
    );
}
