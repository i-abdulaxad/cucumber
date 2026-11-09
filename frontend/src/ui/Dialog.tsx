import type {ReactNode} from 'react';
import {Dialog as AriaDialog, Heading, Modal, ModalOverlay} from 'react-aria-components';
import {Button} from './Button';
import {cx} from './cx';

const sizes = {sm: 'max-w-sm', md: 'max-w-xl', lg: 'max-w-2xl'} as const;

export interface DialogProps {
    isOpen: boolean;
    onOpenChange: (open: boolean) => void;
    title: string;
    children?: ReactNode;
    /** Footer actions; the primary action goes last (on the right). */
    footer?: ReactNode;
    size?: keyof typeof sizes;
    /** alertdialog for confirmations and conflicts. */
    role?: 'dialog' | 'alertdialog';
    isDismissable?: boolean;
}

/** DS-9 Dialog: traps focus, returns it when closed, closes on Escape. */
export function Dialog({isOpen, onOpenChange, title, children, footer, size = 'sm', role = 'dialog', isDismissable = true}: DialogProps) {
    return (
        <ModalOverlay
            isOpen={isOpen}
            onOpenChange={onOpenChange}
            isDismissable={isDismissable}
            className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-scrim p-4 data-entering:animate-fade-in sm:items-center"
        >
            <Modal className={cx('w-full rounded-xl border border-border bg-surface-overlay shadow-lg outline-none data-entering:animate-pop-in', sizes[size])}>
                <AriaDialog role={role} className="flex flex-col gap-4 p-6 outline-none">
                    <Heading slot="title" className="text-xl font-semibold text-text">
                        {title}
                    </Heading>
                    {children ? <div className="text-base text-text-muted">{children}</div> : null}
                    {footer ? <div className="flex flex-wrap justify-end gap-2 pt-2">{footer}</div> : null}
                </AriaDialog>
            </Modal>
        </ModalOverlay>
    );
}

export interface ConfirmDialogProps {
    isOpen: boolean;
    onOpenChange: (open: boolean) => void;
    title: string;
    children?: ReactNode;
    /** Names the action, for example "Archive feature". */
    confirmLabel: string;
    cancelLabel: string;
    onConfirm: () => void;
    danger?: boolean;
    loading?: boolean;
}

/** DS-9 ConfirmDialog: the confirm button names the action; a danger variant for destructive actions. */
export function ConfirmDialog({isOpen, onOpenChange, title, children, confirmLabel, cancelLabel, onConfirm, danger = false, loading = false}: ConfirmDialogProps) {
    return (
        <Dialog
            isOpen={isOpen}
            onOpenChange={onOpenChange}
            title={title}
            role="alertdialog"
            footer={
                <>
                    <Button variant="secondary" onPress={() => onOpenChange(false)}>
                        {cancelLabel}
                    </Button>
                    <Button variant={danger ? 'danger' : 'primary'} onPress={onConfirm} loading={loading}>
                        {confirmLabel}
                    </Button>
                </>
            }
        >
            {children}
        </Dialog>
    );
}
