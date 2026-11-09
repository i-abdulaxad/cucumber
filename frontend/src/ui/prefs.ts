// DS-6, DS-7: the only module that touches localStorage. It holds UI preferences only, never a secret.
export const THEME_KEY = 'cucumber.ui.theme';
export const SIDEBAR_KEY = 'cucumber.ui.sidebar';

export type ThemePref = 'system' | 'light' | 'dark';
export type Theme = 'light' | 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';

function read(key: string): string | null {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

function write(key: string, value: string): void {
    try {
        localStorage.setItem(key, value);
    } catch {
        // Storage unavailable: the preference lasts for this page only.
    }
}

export function readThemePref(): ThemePref {
    const value = read(THEME_KEY);
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

export function writeThemePref(pref: ThemePref): void {
    write(THEME_KEY, pref);
}

export function resolveTheme(pref: ThemePref, osPrefersDark: boolean): Theme {
    if (pref === 'system') return osPrefersDark ? 'dark' : 'light';
    return pref;
}

export function osPrefersDark(): boolean {
    return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

export function applyTheme(theme: Theme): void {
    document.documentElement.dataset.theme = theme;
}

/** Calls back when the OS colour scheme changes (for the System setting). */
export function watchOsTheme(onChange: (prefersDark: boolean) => void): () => void {
    if (typeof window.matchMedia !== 'function') return () => undefined;
    const query = window.matchMedia(DARK_QUERY);
    const listener = (event: MediaQueryListEvent) => onChange(event.matches);
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
}

export function readSidebarCollapsed(): boolean {
    return read(SIDEBAR_KEY) === 'collapsed';
}

export function writeSidebarCollapsed(collapsed: boolean): void {
    write(SIDEBAR_KEY, collapsed ? 'collapsed' : 'expanded');
}
