import { Building, CirclePlay, FileText, FolderKanban, KeyRound, PanelLeftClose, PanelLeftOpen, Settings, Users, type LucideIcon } from 'lucide-react';
import { Link } from 'react-aria-components';
import { useRouterState } from '@tanstack/react-router';
import { hasProjectRole } from '../auth/roles';
import { Button } from '../ui/Button';
import { Tooltip } from '../ui/Tooltip';
import { cx } from '../ui/cx';
import { appStrings } from './strings';
import type { useWorkspace } from './useWorkspace';

type Workspace = ReturnType<typeof useWorkspace>;

interface NavItem {
    label: string;
    href: string;
    icon: LucideIcon;
}

interface NavSection {
    title: string | null;
    items: NavItem[];
}

/** DS-7: org section, then the project section; items the role can't use are absent (D5). */
export function navSections(ws: Workspace): NavSection[] {
    const sections: NavSection[] = [];
    if (!ws.orgId) {
        sections.push({ title: null, items: [{ label: appStrings.nav.organizations, href: '/orgs', icon: Building }] });
        return sections;
    }
    sections.push({
        title: ws.org?.name ?? null,
        items: [
            { label: appStrings.nav.projects, href: `/orgs/${ws.orgId}/projects`, icon: FolderKanban },
            { label: appStrings.nav.members, href: `/orgs/${ws.orgId}/members`, icon: Users },
        ],
    });
    if (ws.project) {
        const base = `/projects/${ws.project.id}`;
        const items: NavItem[] = [
            { label: appStrings.nav.features, href: `${base}/features`, icon: FileText },
            { label: appStrings.nav.runs, href: `${base}/runs`, icon: CirclePlay },
        ];
        if (hasProjectRole(ws.project.myRole, 'ADMIN')) items.push({ label: appStrings.nav.uploadTokens, href: `${base}/upload-tokens`, icon: KeyRound });
        items.push({ label: appStrings.nav.members, href: `${base}/members`, icon: Users }, { label: appStrings.nav.settings, href: `${base}/settings`, icon: Settings });
        sections.push({ title: ws.project.name, items });
    }
    return sections;
}

export interface SidebarProps {
    workspace: Workspace;
    collapsed: boolean;
    /** Desktop only: toggles the collapsed state. */
    onToggle?: () => void;
    /** Called when an item is chosen (the drawer closes). */
    onNavigate?: () => void;
}

export function Sidebar({ workspace, collapsed, onToggle, onNavigate }: SidebarProps) {
    const pathname = useRouterState({ select: (s) => s.location.pathname });
    const sections = navSections(workspace);
    return (
        <nav
            aria-label={appStrings.sidebar}
            className={cx('flex h-full shrink-0 flex-col border-r border-border bg-surface-raised', collapsed ? 'w-14' : 'w-60', !onToggle && 'w-full border-r-0')}
        >
            <div className="flex flex-1 flex-col gap-6 overflow-y-auto px-2 py-4">
                {sections.map((section, index) => (
                    <div key={section.title ?? `section-${index}`} className="flex flex-col gap-1">
                        {section.title && !collapsed ? <p className="truncate px-2 pb-1 text-xs font-medium text-text-muted">{section.title}</p> : null}
                        <ul className="flex flex-col gap-0.5">
                            {section.items.map((item) => {
                                const active = pathname === item.href;
                                const Icon = item.icon;
                                const link = (
                                    <Link
                                        href={item.href}
                                        aria-current={active ? 'page' : undefined}
                                        aria-label={collapsed ? item.label : undefined}
                                        onPress={onNavigate}
                                        className={cx(
                                            'relative flex h-8 items-center gap-3 rounded-md px-2 text-sm font-medium outline-none transition duration-120 ease-standard',
                                            collapsed && 'justify-center',
                                            active ? 'bg-surface-hover text-text' : 'text-text-muted data-hovered:bg-surface-hover data-hovered:text-text',
                                        )}
                                    >
                                        {active ? <span aria-hidden="true" className="absolute inset-y-1 left-0 w-0.5 rounded-full bg-primary" /> : null}
                                        <Icon aria-hidden="true" size={20} className={active ? 'text-primary' : undefined} />
                                        {collapsed ? null : <span className="truncate">{item.label}</span>}
                                    </Link>
                                );
                                return (
                                    <li key={item.href}>
                                        <Tooltip label={item.label} isDisabled={!collapsed}>
                                            {link}
                                        </Tooltip>
                                    </li>
                                );
                            })}
                        </ul>
                    </div>
                ))}
            </div>
            {onToggle ? (
                <div className={cx('flex border-t border-border p-2', collapsed ? 'justify-center' : 'justify-end')}>
                    <Button variant="ghost" iconOnly size="md" aria-label={collapsed ? appStrings.expandSidebar : appStrings.collapseSidebar} onPress={onToggle}>
                        {collapsed ? <PanelLeftOpen aria-hidden="true" size={16} /> : <PanelLeftClose aria-hidden="true" size={16} />}
                    </Button>
                </div>
            ) : null}
        </nav>
    );
}
