import { useEffect, type MouseEvent } from 'react';
import { Outlet, useRouter } from '@tanstack/react-router';
import { shouldFocusHeading } from './focus';
import { appStrings } from './strings';

function focusMain(event: MouseEvent<HTMLAnchorElement>) {
    event.preventDefault();
    document.getElementById('main')?.focus();
}

/** Root layout: the skip link first; each layout below renders one main landmark. Focus moves to the h1 after navigation (D17). */
export function Shell() {
    const router = useRouter();

    useEffect(
        () =>
            router.subscribe('onResolved', (event) => {
                if (!event.fromLocation || !event.pathChanged) return;
                if (!shouldFocusHeading()) return;
                setTimeout(() => {
                    const heading = document.querySelector<HTMLElement>('main h1');
                    (heading ?? document.getElementById('main'))?.focus();
                }, 0);
            }),
        [router],
    );

    return (
        <>
            <a
                href="#main"
                onClick={focusMain}
                className="fixed left-2 top-2 z-50 -translate-y-16 rounded-md border border-border bg-surface-raised px-3 py-2 text-base font-medium text-primary shadow-md focus:translate-y-0"
            >
                {appStrings.skipLink}
            </a>
            <Outlet />
        </>
    );
}
