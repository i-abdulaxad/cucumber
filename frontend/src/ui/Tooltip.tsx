import type {ReactElement, ReactNode} from 'react';
import {Tooltip as AriaTooltip, TooltipTrigger} from 'react-aria-components';

/** DS-9 Tooltip: after 500 ms on hover, at once on focus; never the only place information lives. */
export function Tooltip({label, children, placement = 'right', isDisabled = false}: {
    label: ReactNode;
    children: ReactElement;
    placement?: 'right' | 'top' | 'bottom';
    isDisabled?: boolean
}) {
    return (
        <TooltipTrigger delay={500} closeDelay={0} isDisabled={isDisabled}>
            {children}
            <AriaTooltip
                placement={placement}
                offset={8}
                className="rounded-md bg-surface-inverse px-2 py-1 text-xs font-medium text-text-inverse shadow-md data-entering:animate-pop-in"
            >
                {label}
            </AriaTooltip>
        </TooltipTrigger>
    );
}
