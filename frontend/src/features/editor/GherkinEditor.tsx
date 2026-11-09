import {useEffect, useRef} from 'react';
import {defaultKeymap, history, historyKeymap} from '@codemirror/commands';
import {lintKeymap, openLintPanel, setDiagnostics, type Diagnostic} from '@codemirror/lint';
import {Compartment, EditorState} from '@codemirror/state';
import {drawSelection, EditorView, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers} from '@codemirror/view';
import {gherkinLanguage} from './gherkin';

export interface GherkinEditorProps {
    /** Changing the key replaces the document with `initialDoc` (a load or a reload). */
    docKey: string;
    initialDoc: string;
    readOnly: boolean;
    diagnostics: readonly Diagnostic[];
    label: string;
    /** Ids of elements that describe the editor (the live region, the read-only banner). */
    describedBy?: string;
    onChange: (doc: string) => void;
    onSave: () => void;
}

function readOnlyExtension(readOnly: boolean) {
    return [EditorState.readOnly.of(readOnly), EditorView.contentAttributes.of({'aria-readonly': readOnly ? 'true' : 'false'})];
}

/** D11: CodeMirror 6 with the Gherkin mode; Ctrl/Cmd+S saves; F8 moves to the next diagnostic. */
export function GherkinEditor({docKey, initialDoc, readOnly, diagnostics, label, describedBy, onChange, onSave}: GherkinEditorProps) {
    const hostRef = useRef<HTMLDivElement>(null);
    const viewRef = useRef<EditorView | null>(null);
    const readOnlyCompartment = useRef(new Compartment());
    const callbacks = useRef({onChange, onSave});
    callbacks.current = {onChange, onSave};
    const initialReadOnly = useRef(readOnly);
    initialReadOnly.current = readOnly;

    useEffect(() => {
        const host = hostRef.current;
        if (!host) return undefined;
        const view = new EditorView({
            parent: host,
            state: EditorState.create({
                doc: initialDoc,
                extensions: [
                    lineNumbers(),
                    highlightActiveLineGutter(),
                    highlightActiveLine(),
                    drawSelection(),
                    history(),
                    gherkinLanguage(),
                    EditorView.lineWrapping,
                    keymap.of([
                        {
                            key: 'Mod-s',
                            preventDefault: true,
                            run: () => {
                                callbacks.current.onSave();
                                return true;
                            },
                        },
                        ...lintKeymap,
                        ...historyKeymap,
                        ...defaultKeymap,
                    ]),
                    EditorView.contentAttributes.of({
                        'aria-label': label,
                        'aria-multiline': 'true',
                        ...(describedBy ? {'aria-describedby': describedBy} : {}),
                    }),
                    readOnlyCompartment.current.of(readOnlyExtension(initialReadOnly.current)),
                    EditorView.updateListener.of((update) => {
                        if (update.docChanged) callbacks.current.onChange(update.state.doc.toString());
                    }),
                ],
            }),
        });
        viewRef.current = view;
        return () => {
            view.destroy();
            viewRef.current = null;
        };
        // The document is replaced only when docKey changes (a load or a reload); initialDoc is read at that moment.
    }, [docKey, label, describedBy]);

    useEffect(() => {
        viewRef.current?.dispatch({effects: readOnlyCompartment.current.reconfigure(readOnlyExtension(readOnly))});
    }, [readOnly]);

    useEffect(() => {
        const view = viewRef.current;
        if (!view) return;
        view.dispatch(setDiagnostics(view.state, [...diagnostics]));
        if (diagnostics.length > 0) openLintPanel(view);
    }, [diagnostics, docKey]);

    return <div ref={hostRef} className="gherkin-editor h-full min-h-0"/>;
}
