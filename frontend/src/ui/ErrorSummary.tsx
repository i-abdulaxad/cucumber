import {useId, type ReactNode, type Ref} from 'react';
import {TriangleExclamationPoint} from 'lucide-react';
import {uiStrings} from './strings';

export interface SummaryItem {
    message: string;
    /** The input this message belongs to; activating the item focuses it. */
    fieldId?: string;
}

/** D17: after a failed submit, this summary takes focus; each field error links to its input. */
export function ErrorSummary({items, children, summaryRef}: {
    items: readonly SummaryItem[];
    children?: ReactNode;
    summaryRef?: Ref<HTMLDivElement>
}) {
    const titleId = useId();
    return (
        <div
            role="alert"
            tabIndex={-1}
            ref={summaryRef}
            aria-labelledby={titleId}
            className="mb-5 flex gap-3 rounded-lg border border-danger bg-danger-soft p-3 text-base text-text"
        >
            <TriangleExclamationPoint aria-hidden="true" size={16} className="mt-0.5 shrink-0 text-danger"/>
            <div className="flex min-w-0 flex-col gap-1">
                <h2 id={titleId} className="font-semibold">
                    {uiStrings.summaryTitle}
                </h2>
                <ul className="flex flex-col gap-0.5">
                    {items.map((item, index) => (
                        <li key={`${item.fieldId ?? 'form'}-${index}`}>
                            {item.fieldId ? (
                                <a
                                    href={`#${item.fieldId}`}
                                    className="underline underline-offset-2"
                                    onClick={(event) => {
                                        event.preventDefault();
                                        if (item.fieldId) document.getElementById(item.fieldId)?.focus();
                                    }}
                                >
                                    {item.message}
                                </a>
                            ) : (
                                item.message
                            )}
                        </li>
                    ))}
                </ul>
                {children}
            </div>
        </div>
    );
}
