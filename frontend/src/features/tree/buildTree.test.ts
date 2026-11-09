import {describe, expect, it} from 'vitest';
import {buildTree, flattenFiles, type TreeNode} from './buildTree';
import {address, legacy, payment, summary} from '../../test/fixtures/scenario';

function shape(nodes: TreeNode[]): unknown[] {
    return nodes.map((n) => (n.kind === 'folder' ? {[n.name]: shape(n.children)} : n.name));
}

// @US-5 @AC-5.1 (D10): folders from path segments; folders first, then files, by lower-cased code points.
describe('buildTree', () => {
    it('nests folders and orders folders before files', () => {
        const tree = buildTree([payment, address, summary('a1', 'features/zeta.feature', 'Zeta', 1, []), summary('a2', 'README.feature', 'Readme', 1, [])]);
        expect(shape(tree)).toEqual([{features: [{checkout: ['address.feature', 'payment.feature']}, 'zeta.feature']}, 'README.feature']);
    });

    it('orders by lower-cased code points, not by letter case or UTF-16 units', () => {
        const tree = buildTree([
            summary('b1', 'b.feature', 'B', 1, []),
            summary('b2', 'A.feature', 'A', 1, []),
            summary('b3', '\u{1F600}.feature', 'Emoji', 1, []),
            summary('b4', '～.feature', 'Fullwidth tilde', 1, []),
        ]);
        // U+FF5E sorts before U+1F600 by code point, although its UTF-16 unit is larger than the surrogate 0xD83D.
        expect(shape(tree)).toEqual(['A.feature', 'b.feature', '～.feature', '\u{1F600}.feature']);
    });

    it('treats a dot in a folder name as part of the name', () => {
        const tree = buildTree([summary('c1', 'features/v1.2/pay.feature', 'Pay', 1, []), summary('c2', 'features/v1.10/pay.feature', 'Pay', 1, [])]);
        expect(shape(tree)).toEqual([{features: [{'v1.10': ['pay.feature']}, {'v1.2': ['pay.feature']}]}]);
    });

    it('gives folders stable ids from their path and files their feature id', () => {
        const [features] = buildTree([payment]);
        expect(features?.id).toBe('folder:features');
        const checkout = features?.kind === 'folder' ? features.children[0] : undefined;
        expect(checkout?.id).toBe('folder:features/checkout');
        const file = checkout?.kind === 'folder' ? checkout.children[0] : undefined;
        expect(file?.kind === 'file' ? file.feature.id : null).toBe(payment.id);
    });

    it('lists files in tree order', () => {
        expect(flattenFiles(buildTree([payment, legacy, address])).map((f) => f.path)).toEqual([
            'features/checkout/address.feature',
            'features/checkout/payment.feature',
            'features/legacy.feature',
        ]);
    });
});
