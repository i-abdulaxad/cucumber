import {Monitor, Moon, Sun} from 'lucide-react';
import {Button} from './Button';
import {MenuButton, MenuItem} from './Menu';
import {uiStrings} from './strings';
import {useTheme} from './useTheme';
import type {ThemePref} from './prefs';

const icons = {system: Monitor, light: Sun, dark: Moon} as const;

function isThemePref(value: unknown): value is ThemePref {
    return value === 'system' || value === 'light' || value === 'dark';
}

/** DS-6 theme menu: System, Light, Dark, with the current one checked. */
export function ThemeMenu() {
    const {pref, setPref} = useTheme();
    const Icon = icons[pref];
    return (
        <MenuButton
            label={uiStrings.theme.label}
            placement="bottom end"
            selectionMode="single"
            selectedKey={pref}
            onSelect={(key) => {
                if (isThemePref(key)) setPref(key);
            }}
            trigger={
                <Button variant="ghost" iconOnly aria-label={uiStrings.theme.label}>
                    <Icon aria-hidden="true" size={16}/>
                </Button>
            }
        >
            <MenuItem id="system" icon={<Monitor aria-hidden="true" size={16}/>}>
                {uiStrings.theme.system}
            </MenuItem>
            <MenuItem id="light" icon={<Sun aria-hidden="true" size={16}/>}>
                {uiStrings.theme.light}
            </MenuItem>
            <MenuItem id="dark" icon={<Moon aria-hidden="true" size={16}/>}>
                {uiStrings.theme.dark}
            </MenuItem>
        </MenuButton>
    );
}
