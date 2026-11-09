// F3: CodeMirror 6 with the legacy Gherkin mode. Token colours come from theme.css (cm-gherkin-* classes on DS-1
// tokens), applied by a small decoration plugin keyed on the legacy mode's token names.
import {StreamLanguage, syntaxTree} from '@codemirror/language';
import {gherkin} from '@codemirror/legacy-modes/mode/gherkin';
import {RangeSetBuilder, type Extension} from '@codemirror/state';
import {Decoration, ViewPlugin, type DecorationSet, type EditorView, type ViewUpdate} from '@codemirror/view';

// Node names the legacy Gherkin mode produces (StreamLanguage), and the theme.css class for each.
const TOKEN_CLASSES: Record<string, string> = {
    keyword: 'keyword',
    tagName: 'tag',
    comment: 'comment',
    string: 'string',
    variableName: 'variable',
};
const marks = new Map(Object.entries(TOKEN_CLASSES).map(([node, name]) => [node, Decoration.mark({class: `cm-gherkin-${name}`})]));

function decorate(view: EditorView): DecorationSet {
    const builder = new RangeSetBuilder<Decoration>();
    const tree = syntaxTree(view.state);
    for (const {from, to} of view.visibleRanges) {
        tree.iterate({
            from,
            to,
            enter(node) {
                const mark = marks.get(node.type.name);
                if (mark && node.to > node.from) builder.add(node.from, node.to, mark);
            },
        });
    }
    return builder.finish();
}

const highlight = ViewPlugin.fromClass(
    class {
        decorations: DecorationSet;

        constructor(view: EditorView) {
            this.decorations = decorate(view);
        }

        update(update: ViewUpdate) {
            if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
                this.decorations = decorate(update.view);
            }
        }
    },
    {decorations: (plugin) => plugin.decorations},
);

export function gherkinLanguage(): Extension {
    return [StreamLanguage.define(gherkin), highlight];
}
