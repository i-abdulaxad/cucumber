import {useCallback, useEffect, useState} from 'react';
import {
    applyTheme,
    osPrefersDark,
    readThemePref,
    resolveTheme,
    watchOsTheme,
    writeThemePref,
    type ThemePref
} from './prefs';

/** DS-6: the theme setting; System follows the OS live. */
export function useTheme() {
    const [pref, setPrefState] = useState<ThemePref>(readThemePref);

    useEffect(() => {
        applyTheme(resolveTheme(pref, osPrefersDark()));
        if (pref !== 'system') return undefined;
        return watchOsTheme((dark) => applyTheme(resolveTheme('system', dark)));
    }, [pref]);

    const setPref = useCallback((next: ThemePref) => {
        writeThemePref(next);
        setPrefState(next);
    }, []);

    return {pref, setPref};
}
