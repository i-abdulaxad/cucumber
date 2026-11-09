import type {ReactNode} from 'react';
import {ToggleButton} from 'react-aria-components';

/** A toggle Badge (tag filter chips): aria-pressed, never colour alone (a check mark when selected). */
export function ToggleChip({isSelected, onChange, children}: { isSelected: boolean; onChange: (selected: boolean) => void; children: ReactNode }) {
    return (
        <ToggleButton
            isSelected={isSelected}
            onChange={onChange}
            className="inline-flex h-6 cursor-default items-center gap-1 rounded-sm border border-border bg-surface-raised px-2 font-mono text-xs text-text-muted transition duration-120 ease-standard data-hovered:bg-surface-hover data-hovered:text-text data-selected:border-primary data-selected:bg-primary-soft data-selected:text-primary"
        >
            {({isSelected: selected}) => (
                <>
                    {selected ? <span aria-hidden="true">✓</span> : null}
                    {children}
                </>
            )}
        </ToggleButton>
    );
}
