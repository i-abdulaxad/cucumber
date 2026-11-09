// @vitest-environment node
// @US-10 @AC-10.3: every DS-1 and DS-2 pair meets its stated minimum, read from src/ui/theme.css for both themes.
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {describe, expect, it} from 'vitest';

function tokens(css, theme) {
    const marker = `/* tokens:${theme} */`;
    const start = css.indexOf(marker);
    if (start < 0) throw new Error(`theme.css has no ${marker} block`);
    const open = css.indexOf('{', start);
    const close = css.indexOf('}', open);
    const block = css.slice(open + 1, close);
    const out = new Map();
    for (const match of block.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?)\s*;/g)) {
        const [, name, value] = match;
        if (name && value) out.set(name, value.toLowerCase());
    }
    return out;
}

function parse(hex) {
    const n = (i) => parseInt(hex.slice(i, i + 2), 16);
    return {rgb: [n(1), n(3), n(5)], alpha: hex.length === 9 ? n(7) / 255 : 1};
}

function over(top, base) {
    const t = parse(top);
    const b = parse(base).rgb;
    const mix = (i) => Math.round(t.rgb[i] * t.alpha + b[i] * (1 - t.alpha));
    return [mix(0), mix(1), mix(2)];
}

function luminance([r, g, b]) {
    const channel = (c) => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function ratio(a, b) {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return ((hi ?? 0) + 0.05) / ((lo ?? 0) + 0.05);
}

const css = readFileSync(resolve(import.meta.dirname, 'theme.css'), 'utf8');

const surfaces = ['surface', 'surface-raised', 'surface-overlay'];
const statuses = ['passed', 'failed', 'skipped', 'pending', 'undefined', 'ambiguous', 'unknown'];

function pairs() {
    const list = [];
    for (const fg of ['text', 'text-muted']) for (const bg of surfaces) list.push([fg, bg, 4.5]);
    for (const fg of ['primary', 'danger', 'warning', 'success', 'info']) list.push([fg, 'surface-raised', 4.5]);
    list.push(['on-primary', 'primary', 4.5], ['on-primary', 'primary-hover', 4.5], ['on-danger', 'danger', 4.5]);
    for (const fg of ['focus-ring', 'border-control']) for (const bg of ['surface', 'surface-raised']) list.push([fg, bg, 3]);
    for (const s of statuses) list.push([`status-${s}-fg`, `status-${s}-bg`, 4.5]);
    // Tokens added for DS-9 components: the Tooltip's inverted surface and Avatar initials on each hue.
    list.push(['text-inverse', 'surface-inverse', 4.5]);
    for (let i = 1; i <= 6; i += 1) list.push(['on-avatar', `avatar-${i}`, 4.5]);
    list.push(['text', 'danger-soft', 4.5], ['text', 'primary-soft', 4.5], ['primary', 'primary-soft', 4.5]);
    return list;
}

describe.each(['light', 'dark'])('AC-10.3 contrast, %s theme', (theme) => {
    const t = tokens(css, theme);
    const get = (name) => {
        const value = t.get(name);
        if (!value) throw new Error(`theme.css (${theme}) has no --${name}`);
        return value;
    };

    it.each(pairs())('%s on %s ≥ %s:1', (fg, bg, min) => {
        const base = get('surface-raised');
        const background = over(get(bg), base);
        const foreground = over(get(fg), `#${background.map((c) => c.toString(16).padStart(2, '0')).join('')}`);
        expect(ratio(foreground, background)).toBeGreaterThanOrEqual(min);
    });
});
