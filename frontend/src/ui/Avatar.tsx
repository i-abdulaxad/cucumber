import {cx} from './cx';

const hues = ['bg-avatar-1', 'bg-avatar-2', 'bg-avatar-3', 'bg-avatar-4', 'bg-avatar-5', 'bg-avatar-6'];

export function initialsOf(fullName: string | null | undefined, email: string | null | undefined): string {
    const words = (fullName ?? '').trim().split(/\s+/).filter(Boolean);
    if (words.length >= 2) return `${words[0]?.[0] ?? ''}${words[1]?.[0] ?? ''}`.toUpperCase();
    if (words.length === 1) return (words[0]?.slice(0, 2) ?? '').toUpperCase();
    return (email?.trim()[0] ?? '?').toUpperCase();
}

function hueOf(seed: string): string {
    let hash = 0;
    for (const char of seed) hash = (hash * 31 + (char.codePointAt(0) ?? 0)) >>> 0;
    return hues[hash % hues.length] ?? 'bg-avatar-1';
}

/** DS-9 Avatar: initials on a colour hashed from the user id. Decorative; the owner labels the control. */
export function Avatar({userId, fullName, email, size = 'md'}: {
    userId: string;
    fullName?: string | null;
    email?: string | null;
    size?: 'sm' | 'md'
}) {
    return (
        <span
            aria-hidden="true"
            className={cx(
                'inline-flex shrink-0 select-none items-center justify-center rounded-full font-semibold text-on-avatar',
                size === 'sm' ? 'size-6 text-xs' : 'size-8 text-sm',
                hueOf(userId),
            )}
        >
      {initialsOf(fullName, email)}
    </span>
    );
}
