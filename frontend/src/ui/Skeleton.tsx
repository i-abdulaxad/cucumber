import {cx} from './cx';

/** DS-9 Skeleton: decorative shapes; the surrounding region carries aria-busy and the status text. */
export function Skeleton({shape = 'line', width = 'full'}: {
    shape?: 'line' | 'block' | 'row';
    width?: 'full' | 'half' | 'third'
}) {
    return (
        <span
            aria-hidden="true"
            data-skeleton=""
            className={cx(
                'block animate-shimmer rounded-md bg-surface-hover',
                shape === 'line' && 'h-3',
                shape === 'row' && 'h-9',
                shape === 'block' && 'h-24',
                width === 'full' && 'w-full',
                width === 'half' && 'w-1/2',
                width === 'third' && 'w-1/3',
            )}
        />
    );
}

/** Skeleton rows shaped like a list or table. */
export function SkeletonRows({rows = 4}: { rows?: number }) {
    return (
        <div className="flex flex-col gap-3 p-4">
            {Array.from({length: rows}, (_, i) => (
                <div key={i} className="flex items-center gap-3">
                    <span aria-hidden="true" data-skeleton=""
                          className="block size-8 shrink-0 animate-shimmer rounded-md bg-surface-hover"/>
                    <div className="flex flex-1 flex-col gap-2">
                        <Skeleton width={i % 2 === 0 ? 'third' : 'half'}/>
                        <Skeleton width="full"/>
                    </div>
                </div>
            ))}
        </div>
    );
}
