import type {ReactNode} from 'react';
import {ChevronRight, FileText, Folder, FolderOpen} from 'lucide-react';
import {
    Button as AriaButton,
    Collection,
    ListLayout,
    Tree as AriaTree,
    TreeItem,
    TreeItemContent,
    Virtualizer,
    type Key,
    type Selection,
} from 'react-aria-components';
import {cx} from './cx';
import {uiStrings} from './strings';

export interface UiTreeNode {
    id: string;
    kind: 'folder' | 'file';
    /** The row's text and its type-ahead value. */
    label: string;
    /** Extra content on the right of a file row (tags, counts). */
    meta?: ReactNode;
    children?: UiTreeNode[];
}

const ROW_HEIGHT = 28;

function Indent({level}: { level: number }) {
    return (
        <>
            {Array.from({length: Math.max(0, level - 1)}, (_, i) => (
                <span key={i} aria-hidden="true" className="w-4 shrink-0"/>
            ))}
        </>
    );
}

function renderNode(node: UiTreeNode) {
    return (
        <TreeItem
            key={node.id}
            id={node.id}
            textValue={node.label}
            className="group flex h-7 cursor-default items-center rounded-md px-2 text-sm text-text outline-none data-hovered:bg-surface-hover data-selected:bg-surface-hover data-selected:font-medium data-focus-visible:outline-2 data-focus-visible:-outline-offset-2 data-focus-visible:outline-focus-ring"
        >
            <TreeItemContent>
                {({isExpanded, hasChildItems, level, isSelected}) => (
                    <div className="flex min-w-0 flex-1 items-center gap-1.5">
                        <Indent level={level}/>
                        {hasChildItems ? (
                            <AriaButton
                                slot="chevron"
                                aria-label={isExpanded ? uiStrings.collapse : uiStrings.expand}
                                className="inline-flex size-4 shrink-0 items-center justify-center rounded-sm text-text-muted data-hovered:text-text"
                            >
                                <ChevronRight aria-hidden="true" size={14} className={cx('transition duration-120 ease-standard', isExpanded && 'rotate-90')}/>
                            </AriaButton>
                        ) : (
                            <span aria-hidden="true" className="w-4 shrink-0"/>
                        )}
                        {node.kind === 'folder' ? (
                            isExpanded ? (
                                <FolderOpen aria-hidden="true" size={16} className="shrink-0 text-text-muted"/>
                            ) : (
                                <Folder aria-hidden="true" size={16} className="shrink-0 text-text-muted"/>
                            )
                        ) : (
                            <FileText aria-hidden="true" size={16} className={cx('shrink-0', isSelected ? 'text-primary' : 'text-text-muted')}/>
                        )}
                        <span className="truncate">{node.label}</span>
                        {node.meta ? <span className="ml-auto flex shrink-0 items-center gap-2 pl-2">{node.meta}</span> : null}
                    </div>
                )}
            </TreeItemContent>
            {node.children ? <Collection items={node.children}>{renderNode}</Collection> : null}
        </TreeItem>
    );
}

export interface TreeProps {
    label: string;
    nodes: UiTreeNode[];
    expandedKeys: Iterable<Key>;
    onExpandedChange: (keys: Set<Key>) => void;
    selectedKey: string | null;
    /** Files are selectable; folders only expand. */
    onSelect: (id: string) => void;
    folderIds: Iterable<Key>;
}

/** DS-9 Tree: 28 px rows, chevrons, folder and file icons, virtualized (D10, AC-5.5). */
export function Tree({label, nodes, expandedKeys, onExpandedChange, selectedKey, onSelect, folderIds}: TreeProps) {
    function onSelectionChange(selection: Selection) {
        if (selection === 'all') return;
        const [first] = [...selection];
        if (first !== undefined) onSelect(String(first));
    }
    return (
        <Virtualizer layout={ListLayout} layoutOptions={{rowHeight: ROW_HEIGHT}}>
            <AriaTree
                aria-label={label}
                items={nodes}
                expandedKeys={expandedKeys}
                onExpandedChange={onExpandedChange}
                selectionMode="single"
                disallowEmptySelection
                selectedKeys={selectedKey ? [selectedKey] : []}
                onSelectionChange={onSelectionChange}
                disabledKeys={folderIds}
                disabledBehavior="selection"
                className="h-full min-h-0 overflow-auto px-2 py-1 outline-none"
            >
                {renderNode}
            </AriaTree>
        </Virtualizer>
    );
}
