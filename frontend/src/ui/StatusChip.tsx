import {
    CircleCheck,
    CircleDashed,
    CircleQuestionMark,
    CircleX,
    Clock,
    GitFork,
    SkipForward,
    type LucideIcon
} from 'lucide-react';
import type {components} from '../api/generated/report-service';
import {uiStrings} from './strings';

export type ExecutionStatus = components['schemas']['ExecutionStatus'] | 'UNKNOWN';

const styles: Record<string, { className: string; Icon: LucideIcon }> = {
    PASSED: {className: 'bg-status-passed-bg text-status-passed-fg', Icon: CircleCheck},
    FAILED: {className: 'bg-status-failed-bg text-status-failed-fg', Icon: CircleX},
    SKIPPED: {className: 'bg-status-skipped-bg text-status-skipped-fg', Icon: SkipForward},
    PENDING: {className: 'bg-status-pending-bg text-status-pending-fg', Icon: Clock},
    UNDEFINED: {className: 'bg-status-undefined-bg text-status-undefined-fg', Icon: CircleQuestionMark},
    AMBIGUOUS: {className: 'bg-status-ambiguous-bg text-status-ambiguous-fg', Icon: GitFork},
    UNKNOWN: {className: 'bg-status-unknown-bg text-status-unknown-fg', Icon: CircleDashed},
};

/** DS-2: an icon and the status word, never colour alone. */
export function StatusChip({status}: { status: ExecutionStatus }) {
    const style = styles[status] ?? styles.UNKNOWN;
    if (!style) return null;
    const {className, Icon} = style;
    return (
        <span
            className={`inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 text-xs font-medium tabular-nums ${className}`}>
      <Icon aria-hidden="true" size={12}/>
            {uiStrings.status[status] ?? status}
    </span>
    );
}
