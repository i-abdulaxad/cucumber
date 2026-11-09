import type {ReactNode} from 'react';
import {Dialog, Modal, ModalOverlay} from 'react-aria-components';

/** DS-7 Drawer: a modal panel from the left with a scrim; traps focus and closes on Escape. */
export function Drawer({isOpen, onOpenChange, label, children}: {
    isOpen: boolean;
    onOpenChange: (open: boolean) => void;
    label: string;
    children: ReactNode
}) {
    return (
        <ModalOverlay isOpen={isOpen} onOpenChange={onOpenChange} isDismissable
                      className="fixed inset-0 z-40 bg-scrim data-entering:animate-fade-in">
            <Modal
                className="fixed inset-y-0 left-0 w-72 max-w-full border-r border-border bg-surface-raised shadow-lg outline-none data-entering:animate-slide-in">
                <Dialog aria-label={label} className="flex h-full flex-col outline-none">
                    {children}
                </Dialog>
            </Modal>
        </ModalOverlay>
    );
}
