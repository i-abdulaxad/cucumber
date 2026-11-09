// D12 (SE1, scenario-service 1.0.7): parseErrors' `column` counts Unicode code points. These helpers turn a
// (line, column) pair into a UTF-16 offset for CodeMirror, clamped to the line's end.
import type {Diagnostic} from '@codemirror/lint';
import type {ParseError} from '../../api/problem';

function lineBounds(doc: string, line: number): {start: number; end: number} {
    let start = 0;
    for (let current = 1; current < line; current += 1) {
        const next = doc.indexOf('\n', start);
        if (next < 0) break;
        start = next + 1;
    }
    const end = doc.indexOf('\n', start);
    return {start, end: end < 0 ? doc.length : end};
}

/** The UTF-16 offset of 1-based (line, column), where column counts code points; clamped to the line end. */
export function offsetFor(doc: string, line: number, column: number): number {
    const {start, end} = lineBounds(doc, Math.max(1, line));
    let offset = start;
    for (let col = 1; col < column && offset < end; col += 1) {
        const code = doc.codePointAt(offset) ?? 0;
        offset += code > 0xffff ? 2 : 1;
    }
    return Math.min(offset, end);
}

/** One error diagnostic per parse error, covering the character at its position (or empty at a line end). */
export function toDiagnostics(doc: string, errors: readonly ParseError[]): Diagnostic[] {
    return errors.map((error) => {
        const from = offsetFor(doc, error.line, error.column);
        const {end} = lineBounds(doc, Math.max(1, error.line));
        const code = doc.codePointAt(from) ?? 0;
        const to = from < end ? from + (code > 0xffff ? 2 : 1) : from;
        return {from, to, severity: 'error', source: 'Gherkin', message: error.message};
    });
}

/** AC-6.2: the live-region sentence, for example "1 Gherkin error; first on line 4". */
export function gherkinSummary(errors: readonly ParseError[]): string {
    const first = errors.reduce((min, e) => Math.min(min, e.line), Number.POSITIVE_INFINITY);
    const noun = errors.length === 1 ? 'Gherkin error' : 'Gherkin errors';
    return `${errors.length} ${noun}; first on line ${first}`;
}
