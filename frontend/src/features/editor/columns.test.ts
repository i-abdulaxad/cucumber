import {describe, expect, it} from 'vitest';
import {gherkinSummary, offsetFor, toDiagnostics} from './columns';

const doc = (line2: string) => `Feature: Pay\n${line2}\n    Given x\n`;

// @US-6 @AC-6.3 (D12, SE1): parseErrors columns are Unicode code points.
describe('AC-6.3 columns on non-ASCII lines', () => {
    it.each([
        ['  Scénario: Pay', 13, 'P'],
        // The spec's AC-6.3 row says 16, but the contract (scenario-service 1.0.7, ParseError.column) counts 💳 as one
        // column, so "P" is column 15; reported to pm as a spec discrepancy. 16 would be UTF-16 units.
        ['  Scenario: 💳 Pay', 15, 'P'],
    ])('line %j, column %i starts before %j', (line, column, char) => {
        const text = doc(line);
        const offset = offsetFor(text, 2, column);
        expect(text.slice(offset, offset + 1)).toBe(char);
    });

    it('does not count UTF-16 units: column 16 on the 💳 line is the character after "P"', () => {
        const text = doc('  Scenario: 💳 Pay');
        const offset = offsetFor(text, 2, 16);
        expect(text.slice(offset, offset + 1)).toBe('a');
    });

    it('clamps a column past the end of the line to the line end', () => {
        const text = doc('  Scenario: Pay');
        const lineEnd = text.indexOf('\n', text.indexOf('\n') + 1);
        expect(offsetFor(text, 2, 99)).toBe(lineEnd);
    });

    it('clamps a line past the end of the document', () => {
        expect(offsetFor('a\nb', 9, 1)).toBe(2);
    });
});

// @US-6 @AC-6.2: the diagnostic for (4, 3) and the live-region text.
describe('AC-6.2 Gherkin diagnostics', () => {
    const source = ['Feature: Pay', '', '  Scenario: Card', '  Whenn I pay', ''].join('\n');

    it('puts the (4, 3) error at line 4, column 3', () => {
        const [d] = toDiagnostics(source, [{line: 4, column: 3, message: "(4:3): expected: #EOF, #TableRow, ... got 'Whenn I pay'"}]);
        expect(d?.severity).toBe('error');
        expect(source.slice(d?.from, d?.to)).toBe('W');
        expect(source.slice(0, d?.from).split('\n').length).toBe(4);
        expect(d?.message).toContain('Whenn I pay');
    });

    it('marks a whole astral character', () => {
        const [d] = toDiagnostics('💳x', [{line: 1, column: 1, message: 'm'}]);
        expect('💳x'.slice(d?.from, d?.to)).toBe('💳');
    });

    it('summarises for the live region', () => {
        expect(gherkinSummary([{line: 4, column: 3, message: 'm'}])).toBe('1 Gherkin error; first on line 4');
        expect(
            gherkinSummary([
                {line: 2, column: 1, message: 'a'},
                {line: 5, column: 1, message: 'b'},
                {line: 7, column: 2, message: 'c'},
            ]),
        ).toBe('3 Gherkin errors; first on line 2');
    });
});
