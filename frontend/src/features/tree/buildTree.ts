// D10: the feature tree is built on the client from listFeatures `path` values, split on "/". Folders come first,
// then files, each ordered by lower-cased code points, like the API.
import type {components} from '../../api/generated/scenario-service';

export type FeatureSummary = components['schemas']['FeatureSummary'];

export interface FolderNode {
    kind: 'folder';
    id: string;
    name: string;
    path: string;
    children: TreeNode[];
}

export interface FileNode {
    kind: 'file';
    id: string;
    name: string;
    path: string;
    feature: FeatureSummary;
}

export type TreeNode = FolderNode | FileNode;

/** Compares by Unicode code points (not UTF-16 units), after lower-casing. */
export function compareCodePoints(a: string, b: string): number {
    const x = [...a.toLowerCase()];
    const y = [...b.toLowerCase()];
    const length = Math.min(x.length, y.length);
    for (let i = 0; i < length; i += 1) {
        const diff = (x[i]?.codePointAt(0) ?? 0) - (y[i]?.codePointAt(0) ?? 0);
        if (diff !== 0) return diff;
    }
    return x.length - y.length;
}

function sortNodes(nodes: TreeNode[]): TreeNode[] {
    nodes.sort((a, b) => {
        if (a.kind !== b.kind) return a.kind === 'folder' ? -1 : 1;
        return compareCodePoints(a.name, b.name) || compareCodePoints(a.id, b.id);
    });
    for (const node of nodes) if (node.kind === 'folder') sortNodes(node.children);
    return nodes;
}

export function buildTree(features: readonly FeatureSummary[]): TreeNode[] {
    const root: TreeNode[] = [];
    const folders = new Map<string, FolderNode>();
    for (const feature of features) {
        const segments = feature.path.split('/');
        const fileName = segments.pop() ?? feature.path;
        let siblings = root;
        let prefix = '';
        for (const segment of segments) {
            prefix = prefix ? `${prefix}/${segment}` : segment;
            let folder = folders.get(prefix);
            if (!folder) {
                folder = {kind: 'folder', id: `folder:${prefix}`, name: segment, path: prefix, children: []};
                folders.set(prefix, folder);
                siblings.push(folder);
            }
            siblings = folder.children;
        }
        siblings.push({kind: 'file', id: feature.id, name: fileName, path: feature.path, feature});
    }
    return sortNodes(root);
}

export function flattenFiles(nodes: readonly TreeNode[]): FeatureSummary[] {
    return nodes.flatMap((node) => (node.kind === 'file' ? [node.feature] : flattenFiles(node.children)));
}

export function folderIds(nodes: readonly TreeNode[]): string[] {
    return nodes.flatMap((node) => (node.kind === 'folder' ? [node.id, ...folderIds(node.children)] : []));
}
