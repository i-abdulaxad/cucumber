import {useEffect, useRef, useState, useSyncExternalStore} from 'react';
import {CircleCheck, CircleX, Info, X, type LucideIcon} from 'lucide-react';
import {Button} from './Button';
import {dismissToast, getToasts, subscribeToasts, type ToastItem, type ToastTone} from './toastStore';
import {uiStrings} from './strings';

const AUTO_DISMISS_MS = 5000;
const icons: Record<ToastTone, LucideIcon> = {success: CircleCheck, error: CircleX, info: Info};
const iconTone: Record<ToastTone, string> = {success: 'text-success', error: 'text-danger', info: 'text-info'};

function ToastCard({toast}: { toast: ToastItem }) {
    const [paused, setPaused] = useState(false);
    const remaining = useRef(AUTO_DISMISS_MS);
    const cardRef = useRef<HTMLDivElement>(null);
    // Pause the auto-dismiss while the pointer or focus is on the toast (a timing aid, not an interaction).
    useEffect(() => {
        const card = cardRef.current;
        if (!card) return undefined;
        const pause = () => setPaused(true);
        const resume = () => setPaused(false);
        card.addEventListener('mouseenter', pause);
        card.addEventListener('mouseleave', resume);
        card.addEventListener('focusin', pause);
        card.addEventListener('focusout', resume);
        return () => {
            card.removeEventListener('mouseenter', pause);
            card.removeEventListener('mouseleave', resume);
            card.removeEventListener('focusin', pause);
            card.removeEventListener('focusout', resume);
        };
    }, []);
    useEffect(() => {
        if (toast.tone === 'error' || paused) return undefined;
        const started = Date.now();
        const timer = setTimeout(() => dismissToast(toast.id), remaining.current);
        return () => {
            clearTimeout(timer);
            remaining.current -= Date.now() - started;
        };
    }, [toast, paused]);
    const Icon = icons[toast.tone];
    return (
        <div
            ref={cardRef}
            className="pointer-events-auto flex w-full items-start gap-3 rounded-lg border border-border bg-surface-overlay p-3 text-base text-text shadow-md data-entering:animate-pop-in sm:w-80"
        >
            <Icon aria-hidden="true" size={16} className={`mt-0.5 shrink-0 ${iconTone[toast.tone]}`}/>
            <p className="flex-1">{toast.message}</p>
            <Button variant="ghost" size="sm" iconOnly aria-label={uiStrings.dismiss} onPress={() => dismissToast(toast.id)}>
                <X aria-hidden="true" size={16}/>
            </Button>
        </div>
    );
}

/** DS-9 Toast region: bottom-right (top on mobile); polite for success and info, assertive for errors. */
export function ToastRegion() {
    const toasts = useSyncExternalStore(subscribeToasts, getToasts, getToasts);
    const polite = toasts.filter((t) => t.tone !== 'error');
    const assertive = toasts.filter((t) => t.tone === 'error');
    return (
        <div className="pointer-events-none fixed inset-x-4 top-4 z-50 flex flex-col items-end gap-2 sm:inset-x-auto sm:bottom-4 sm:right-4 sm:top-auto">
            <div role="status" aria-live="polite" className="flex w-full flex-col items-end gap-2">
                {polite.map((t) => (
                    <ToastCard key={t.id} toast={t}/>
                ))}
            </div>
            <div role="alert" aria-live="assertive" className="flex w-full flex-col items-end gap-2">
                {assertive.map((t) => (
                    <ToastCard key={t.id} toast={t}/>
                ))}
            </div>
        </div>
    );
}
