import type {ReactNode} from 'react';
import {
    Header,
    Menu as AriaMenu,
    MenuItem as AriaMenuItem,
    MenuSection,
    MenuTrigger,
    Popover,
    Separator,
    type Key,
    type Selection,
} from 'react-aria-components';
import {Check} from 'lucide-react';
import {cx} from './cx';

export const popoverClasses =
    'min-w-48 overflow-auto rounded-lg border border-border bg-surface-overlay p-1 shadow-md outline-none data-entering:animate-pop-in';

const itemClasses =
    'flex cursor-default items-center gap-2 rounded-md px-2 py-1.5 text-base text-text outline-none data-focused:bg-surface-hover data-hovered:bg-surface-hover data-disabled:opacity-50';

export function MenuItem({id, children, icon, href, danger = false, onAction, textValue}: {
    id?: Key;
    children: ReactNode;
    icon?: ReactNode;
    href?: string;
    danger?: boolean;
    onAction?: () => void;
    textValue?: string
}) {
    return (
        <AriaMenuItem id={id} href={href} onAction={onAction} textValue={textValue}
                      className={cx(itemClasses, danger && 'text-danger')}>
            {({isSelected, selectionMode}) => (
                <>
                    {selectionMode !== 'none' ? (
                        <span className="inline-flex size-4 items-center justify-center text-primary">{isSelected ?
                            <Check aria-hidden="true" size={16}/> : null}</span>
                    ) : null}
                    {icon ? <span className="inline-flex text-text-muted">{icon}</span> : null}
                    <span className="flex-1 truncate">{children}</span>
                </>
            )}
        </AriaMenuItem>
    );
}

export function MenuSeparator() {
    return <Separator className="my-1 h-px bg-border"/>;
}

export function MenuHeading({children, label}: { children: ReactNode; label: string }) {
    return (
        <MenuSection aria-label={label}>
            <Header className="px-2 py-1.5 text-sm text-text-muted">{children}</Header>
        </MenuSection>
    );
}

export interface MenuButtonProps {
    /** The trigger: a react-aria Button (from ./Button). */
    trigger: ReactNode;
    label: string;
    children: ReactNode;
    placement?: 'bottom start' | 'bottom end';
    selectionMode?: 'single';
    selectedKey?: Key;
    onSelect?: (key: Key) => void;
}

/** DS-9 Menu: a trigger and a popover menu, with optional single selection (the theme menu). */
export function MenuButton({
                               trigger,
                               label,
                               children,
                               placement = 'bottom start',
                               selectionMode,
                               selectedKey,
                               onSelect
                           }: MenuButtonProps) {
    function onSelectionChange(selection: Selection) {
        if (selection === 'all') return;
        const [first] = [...selection];
        if (first !== undefined) onSelect?.(first);
    }

    return (
        <MenuTrigger>
            {trigger}
            <Popover placement={placement} offset={6} className={popoverClasses}>
                <AriaMenu
                    aria-label={label}
                    className="outline-none"
                    selectionMode={selectionMode}
                    selectedKeys={selectedKey !== undefined ? [selectedKey] : undefined}
                    disallowEmptySelection={selectionMode === 'single'}
                    onSelectionChange={selectionMode ? onSelectionChange : undefined}
                >
                    {children}
                </AriaMenu>
            </Popover>
        </MenuTrigger>
    );
}
