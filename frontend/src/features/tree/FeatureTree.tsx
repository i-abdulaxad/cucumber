import {useEffect, useMemo, useState} from 'react';
import type {Key} from 'react-aria-components';
import {ListChecks} from 'lucide-react';
import {Tree, type UiTreeNode} from '../../ui/Tree';
import {buildTree, folderIds, type FeatureSummary, type TreeNode} from './buildTree';
import {treeStrings as t} from './strings';

function toUiNodes(nodes: TreeNode[]): UiTreeNode[] {
    return nodes.map((node) =>
        node.kind === 'folder'
            ? {id: node.id, kind: 'folder', label: node.name, children: toUiNodes(node.children)}
            : {
                id: node.id,
                kind: 'file',
                label: node.name,
                meta: (
                    <>
                        {node.feature.tags.length > 0 ? (
                            <span className="hidden truncate font-mono text-xs text-text-muted sm:inline">{node.feature.tags.join(' ')}</span>
                        ) : null}
                        <span className="inline-flex items-center gap-1 text-xs tabular-nums text-text-muted">
                            <ListChecks aria-hidden="true" size={12}/>
                            <span className="sr-only">{t.testCases(node.feature.testCaseCount)}</span>
                            <span aria-hidden="true">{node.feature.testCaseCount}</span>
                        </span>
                    </>
                ),
            },
    );
}

/** D10 / AC-5.1: the virtualized tree, folders expanded by default. */
export function FeatureTree({features, selectedId, onOpen}: { features: readonly FeatureSummary[]; selectedId: string | null; onOpen: (id: string) => void }) {
    const tree = useMemo(() => buildTree(features), [features]);
    const nodes = useMemo(() => toUiNodes(tree), [tree]);
    const folders = useMemo(() => folderIds(tree), [tree]);
    const [collapsed, setCollapsed] = useState<Set<Key>>(new Set());
    useEffect(() => setCollapsed(new Set()), [features]);
    const expanded = useMemo(() => folders.filter((id) => !collapsed.has(id)), [folders, collapsed]);
    return (
        <Tree
            label={t.treeLabel}
            nodes={nodes}
            folderIds={folders}
            expandedKeys={expanded}
            onExpandedChange={(keys) => setCollapsed(new Set(folders.filter((id) => !keys.has(id))))}
            selectedKey={selectedId}
            onSelect={onOpen}
        />
    );
}
