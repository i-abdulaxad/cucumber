import {useEffect} from 'react';

/** The page's single h1. It can take focus after navigation (D17) and names the document. */
export function PageHeading({children}: { children: string }) {
    useEffect(() => {
        document.title = `${children} · Cucumber`;
    }, [children]);
    return (
        <h1 tabIndex={-1} className="rounded-sm text-2xl font-semibold tracking-tight text-text">
            {children}
        </h1>
    );
}
