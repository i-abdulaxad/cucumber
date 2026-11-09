import type {ReactNode} from 'react';
import {Tab as AriaTab, TabList as AriaTabList, TabPanel as AriaTabPanel, Tabs as AriaTabs, type Key} from 'react-aria-components';

/** DS-9 Tabs: underline style, arrow keys move between tabs, the selected tab in `primary`. */
export function Tabs({selectedKey, onSelectionChange, children}: { selectedKey?: Key; onSelectionChange?: (key: Key) => void; children: ReactNode }) {
    return (
        <AriaTabs selectedKey={selectedKey} onSelectionChange={onSelectionChange} className="flex min-h-0 flex-1 flex-col">
            {children}
        </AriaTabs>
    );
}

export function TabList({label, children}: { label: string; children: ReactNode }) {
    return (
        <AriaTabList aria-label={label} className="flex shrink-0 gap-4 border-b border-border px-4">
            {children}
        </AriaTabList>
    );
}

export function Tab({id, children}: { id: string; children: ReactNode }) {
    return (
        <AriaTab
            id={id}
            className="-mb-px flex cursor-default items-center gap-1.5 border-b-2 border-transparent py-2 text-sm font-medium text-text-muted outline-none transition duration-120 ease-standard data-hovered:text-text data-selected:border-primary data-selected:text-primary data-focus-visible:rounded-sm data-focus-visible:outline-2 data-focus-visible:outline-focus-ring"
        >
            {children}
        </AriaTab>
    );
}

export function TabPanel({id, children}: { id: string; children: ReactNode }) {
    return (
        <AriaTabPanel id={id} className="flex min-h-0 flex-1 flex-col outline-none">
            {children}
        </AriaTabPanel>
    );
}
