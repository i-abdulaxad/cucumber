// @vitest-environment node
// @US-10 @AC-10.4 / DS-12: no ad-hoc styling. src/ui/theme.css is the only stylesheet; no inline style, no arbitrary
// values and no palette colours in source files.
import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';
import {describe, expect, it} from 'vitest';

const srcDir = resolve(import.meta.dirname, '..');

function walk(dir) {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

const files = walk(srcDir).map((path) => relative(srcDir, path).split('\\').join('/'));
const sources = files.filter(
    (f) => /\.(ts|tsx)$/.test(f) && !/\.test\.(ts|tsx|js)$/.test(f) && !f.startsWith('api/generated/') && !f.startsWith('test/'),
);
const read = (f) => readFileSync(join(srcDir, f), 'utf8');

const PALETTE =
    /\b(?:bg|text|border|ring|outline|fill|stroke|divide|from|via|to|shadow|decoration|accent|caret|placeholder)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b|\b(?:bg|text|border)-(?:white|black)\b/;
const ARBITRARY = /(?:^|[\s"'`:])!?[a-z][a-z0-9-]*-\[[^\]\s]+\]/m;

describe('DS-12 no ad-hoc styling', () => {
    it('has exactly one stylesheet: src/ui/theme.css', () => {
        expect(files.filter((f) => f.endsWith('.css'))).toEqual(['ui/theme.css']);
    });

    it.each(sources)('%s has no inline style, arbitrary value or palette colour', (file) => {
        const text = read(file);
        expect(text).not.toMatch(/\bstyle=\{/);
        expect(text).not.toMatch(/\.module\.css/);
        expect(text).not.toMatch(ARBITRARY);
        expect(text).not.toMatch(PALETTE);
    });
});
