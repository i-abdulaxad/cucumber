import { useEffect, useState } from 'react';
import { Outlet, useRouterState } from '@tanstack/react-router';
import { X } from 'lucide-react';
import { Button } from '../ui/Button';
import { Drawer } from '../ui/Drawer';
import { Logo } from '../ui/Logo';
import { readSidebarCollapsed, writeSidebarCollapsed } from '../ui/prefs';
import { skipNextHeadingFocus } from './focus';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { appStrings } from './strings';
import { useWorkspace } from './useWorkspace';

/** DS-7 app layout: top bar, collapsible sidebar (≥ lg) or drawer (< lg), and the main content. */
export function AppShell() {
    const workspace = useWorkspace();
    const [collapsed, setCollapsed] = useState(readSidebarCollapsed);
    const [drawerOpen, setDrawerOpen] = useState(false);
    const pathname = useRouterState({ select: (s) => s.location.pathname });

    useEffect(() => setDrawerOpen(false), [pathname]);

    function toggle() {
        setCollapsed((value) => {
            writeSidebarCollapsed(!value);
            return !value;
        });
    }

    return (
        <div className="flex h-screen flex-col bg-surface text-text">
            <TopBar workspace={workspace} onOpenNavigation={() => setDrawerOpen(true)} />
            <div className="flex min-h-0 flex-1">
                <div className="hidden lg:flex">
                    <Sidebar workspace={workspace} collapsed={collapsed} onToggle={toggle} />
                </div>
                <main id="main" tabIndex={-1} className="min-w-0 flex-1 overflow-y-auto">
                    <div className="mx-auto w-full max-w-6xl p-4 lg:p-6">
                        <Outlet />
                    </div>
                </main>
            </div>
            <Drawer isOpen={drawerOpen} onOpenChange={setDrawerOpen} label={appStrings.navigation}>
                <div className="flex h-12 items-center justify-between border-b border-border px-3">
                    <Logo />
                    <Button variant="ghost" iconOnly aria-label={appStrings.closeNavigation} onPress={() => setDrawerOpen(false)}>
                        <X aria-hidden="true" size={16} />
                    </Button>
                </div>
                <div className="min-h-0 flex-1">
                    <Sidebar
                        workspace={workspace}
                        collapsed={false}
                        onNavigate={() => {
                            skipNextHeadingFocus();
                            setDrawerOpen(false);
                        }}
                    />
                </div>
            </Drawer>
        </div>
    );
}
