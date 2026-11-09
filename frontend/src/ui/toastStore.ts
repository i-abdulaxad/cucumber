// DS-9 Toast store: success and info toasts dismiss after 5 s (paused on hover and focus); errors stay until dismissed.
export type ToastTone = 'success' | 'error' | 'info';

export interface ToastItem {
    id: number;
    tone: ToastTone;
    message: string;
}

type Listener = (toasts: readonly ToastItem[]) => void;

let toasts: readonly ToastItem[] = [];
let nextId = 1;
const listeners = new Set<Listener>();

function emit() {
    for (const listener of listeners) listener(toasts);
}

export function showToast(tone: ToastTone, message: string): number {
    const id = nextId;
    nextId += 1;
    toasts = [...toasts, {id, tone, message}].slice(-4);
    emit();
    return id;
}

export function dismissToast(id: number): void {
    toasts = toasts.filter((t) => t.id !== id);
    emit();
}

export function getToasts(): readonly ToastItem[] {
    return toasts;
}

export function subscribeToasts(listener: Listener): () => void {
    listeners.add(listener);
    return () => {
        listeners.delete(listener);
    };
}
