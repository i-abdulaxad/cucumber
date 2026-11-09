import {ChevronsUpDown} from 'lucide-react';
import {
    Button as AriaButton,
    ComboBox as AriaComboBox,
    Input,
    Label,
    ListBox,
    ListBoxItem,
    Popover,
    type Key
} from 'react-aria-components';
import {popoverClasses} from './Menu';
import {uiStrings} from './strings';

export interface ComboBoxItem {
    id: string;
    label: string;
    description?: string;
}

/** DS-9 ComboBox: a filterable list with a visually hidden label (used by the project switcher). */
export function ComboBox({label, items, selectedKey, onSelect, placeholder, isLoading = false}: {
    label: string;
    items: readonly ComboBoxItem[];
    selectedKey: string | null;
    onSelect: (key: string) => void;
    placeholder: string;
    isLoading?: boolean
}) {
    function onSelectionChange(key: Key | null) {
        if (key !== null) onSelect(String(key));
    }

    return (
        <AriaComboBox
            selectedKey={selectedKey}
            onSelectionChange={onSelectionChange}
            defaultItems={items}
            menuTrigger="focus"
            className="group relative"
        >
            <Label className="sr-only">{label}</Label>
            <div
                className="flex h-8 items-center rounded-md transition duration-120 ease-standard data-hovered:bg-surface-hover">
                <Input
                    placeholder={placeholder}
                    className="h-8 w-32 truncate rounded-md bg-transparent px-2 text-base font-medium text-text outline-none placeholder:text-text-muted sm:w-44 data-focused:bg-surface-hover"
                />
                <AriaButton aria-label={uiStrings.showOptions}
                            className="inline-flex size-6 items-center justify-center rounded-md text-text-muted data-hovered:text-text">
                    <ChevronsUpDown aria-hidden="true" size={16}/>
                </AriaButton>
            </div>
            <Popover placement="bottom start" offset={6} className={`${popoverClasses} w-64`}>
                <ListBox<ComboBoxItem>
                    className="outline-none"
                    renderEmptyState={() => <p
                        className="px-2 py-1.5 text-sm text-text-muted">{isLoading ? uiStrings.loading : uiStrings.noMatches}</p>}
                >
                    {(item) => (
                        <ListBoxItem
                            id={item.id}
                            textValue={item.label}
                            className="flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-base outline-none data-focused:bg-surface-hover data-selected:font-semibold"
                        >
                            <span className="flex-1 truncate">{item.label}</span>
                            {item.description ?
                                <span className="text-xs text-text-muted">{item.description}</span> : null}
                        </ListBoxItem>
                    )}
                </ListBox>
            </Popover>
        </AriaComboBox>
    );
}
