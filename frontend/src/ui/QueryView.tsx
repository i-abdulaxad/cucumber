import {useEffect, useState, type ReactNode} from 'react';
import type {UseQueryResult} from '@tanstack/react-query';
import {uiErrorOf} from '../api/problem';
import {ErrorState} from './ErrorState';
import {SkeletonRows} from './Skeleton';
import {uiStrings} from './strings';

const BUSY_DELAY_MS = 300;

function DelayedBusy({skeleton}: { skeleton: ReactNode }) {
    const [visible, setVisible] = useState(false);
    useEffect(() => {
        const timer = setTimeout(() => setVisible(true), BUSY_DELAY_MS);
        return () => clearTimeout(timer);
    }, []);
    return visible ? (
        <div role="status">
            <span className="sr-only">{uiStrings.loading}</span>
            {skeleton}
        </div>
    ) : null;
}

export interface QueryViewProps<T> {
    query: UseQueryResult<T>;
    /** Accessible name of the region. */
    label: string;
    isEmpty?: (data: T) => boolean;
    empty?: ReactNode;
    back?: { href: string; label: string };
    /** Skeleton shaped like the final layout (DS-8); rows by default. */
    skeleton?: ReactNode;
    children: (data: T) => ReactNode;
}

/** AC-9.2: loading (aria-busy, skeleton after 300 ms, no layout jump), empty, error, forbidden, not-found, ready. */
export function QueryView<T>({query, label, isEmpty, empty, back, skeleton, children}: QueryViewProps<T>) {
    let content: ReactNode;
    if (query.isPending) content = <DelayedBusy skeleton={skeleton ?? <SkeletonRows/>}/>;
    else if (query.isError) {
        content = <ErrorState error={uiErrorOf(query.error)} onRetry={() => void query.refetch()} back={back}/>;
    } else if (isEmpty?.(query.data)) content = empty;
    else content = children(query.data);
    return (
        <section aria-label={label} aria-busy={query.isPending} className="region">
            {content}
        </section>
    );
}
