import {afterEach, describe, expect, it} from 'vitest';
import {
    SIDEBAR_KEY,
    THEME_KEY,
    applyTheme,
    readSidebarCollapsed,
    readThemePref,
    resolveTheme,
    writeSidebarCollapsed,
    writeThemePref,
} from './prefs';

afterEach(() => document.documentElement.removeAttribute('data-theme'));

// DS-6: only prefs.ts reads or writes cucumber.ui.theme and cucumber.ui.sidebar.
describe('prefs (DS-6, DS-7)', () => {
    it('uses the agreed keys', () => {
        expect(THEME_KEY).toBe('cucumber.ui.theme');
        expect(SIDEBAR_KEY).toBe('cucumber.ui.sidebar');
    });

    it('defaults the theme to system, and ignores unknown values', () => {
        expect(readThemePref()).toBe('system');
        localStorage.setItem(THEME_KEY, 'neon');
        expect(readThemePref()).toBe('system');
    });

    it.each(['light', 'dark', 'system'] as const)('stores %s', (pref) => {
        writeThemePref(pref);
        expect(localStorage.getItem(THEME_KEY)).toBe(pref);
        expect(readThemePref()).toBe(pref);
    });

    it.each([
        ['system', true, 'dark'],
        ['system', false, 'light'],
        ['light', true, 'light'],
        ['dark', false, 'dark'],
    ] as const)('resolves %s with OS dark=%s to %s', (pref, osDark, applied) => {
        expect(resolveTheme(pref, osDark)).toBe(applied);
    });

    it('applies the theme on <html>', () => {
        applyTheme('dark');
        expect(document.documentElement.dataset.theme).toBe('dark');
    });

    it('stores the sidebar state', () => {
        expect(readSidebarCollapsed()).toBe(false);
        writeSidebarCollapsed(true);
        expect(localStorage.getItem(SIDEBAR_KEY)).toBe('collapsed');
        expect(readSidebarCollapsed()).toBe(true);
        writeSidebarCollapsed(false);
        expect(readSidebarCollapsed()).toBe(false);
    });
});
