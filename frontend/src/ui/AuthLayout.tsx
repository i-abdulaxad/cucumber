import type {ReactNode} from 'react';
import {Logo} from './Logo';
import {ThemeMenu} from './ThemeMenu';

/** DS-8 Auth template: a centred raised card with the product mark above the title. */
export function AuthLayout({children, footer}: { children: ReactNode; footer?: ReactNode }) {
    return (
        <div className="relative flex min-h-screen flex-col bg-surface">
            <div className="flex justify-end p-4">
                <ThemeMenu/>
            </div>
            <main id="main" tabIndex={-1} className="flex flex-1 items-start justify-center px-4 pb-16 sm:items-center">
                <div className="w-full max-w-sm">
                    <div className="rounded-xl border border-border bg-surface-raised p-6 shadow-lg sm:p-8">
                        <div className="mb-6">
                            <Logo size="lg"/>
                        </div>
                        {children}
                    </div>
                    {footer ? <div className="mt-6 text-center text-base text-text-muted">{footer}</div> : null}
                </div>
            </main>
        </div>
    );
}
