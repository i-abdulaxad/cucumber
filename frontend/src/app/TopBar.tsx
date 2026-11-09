import { Building, ChevronDown, LogOut, Menu as MenuIcon, Plus } from 'lucide-react';
import { Link } from 'react-aria-components';
import { useRouter } from '@tanstack/react-router';
import { endSession } from '../auth/session';
import { Avatar } from '../ui/Avatar';
import { Button } from '../ui/Button';
import { ComboBox } from '../ui/ComboBox';
import { Logo } from '../ui/Logo';
import { MenuButton, MenuHeading, MenuItem, MenuSeparator } from '../ui/Menu';
import { ThemeMenu } from '../ui/ThemeMenu';
import { appStrings } from './strings';
import type { useWorkspace } from './useWorkspace';

type Workspace = ReturnType<typeof useWorkspace>;

/** DS-7 top bar: product mark, org switcher, project switcher; theme menu and user menu on the right. */
export function TopBar({ workspace, onOpenNavigation }: { workspace: Workspace; onOpenNavigation: () => void }) {
    const router = useRouter();
    const { org, orgs, projects, project, session, me } = workspace;
    const orgName = org?.name ?? null;
    return (
        <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border bg-surface-raised px-3">
            <div className="lg:hidden">
                <Button variant="ghost" iconOnly aria-label={appStrings.openNavigation} onPress={onOpenNavigation}>
                    <MenuIcon aria-hidden="true" size={16} />
                </Button>
            </div>
            <Link href="/" className="flex items-center rounded-md px-1 outline-none">
                <Logo />
            </Link>
            <span aria-hidden="true" className="hidden text-text-muted sm:inline">
                /
            </span>
            <MenuButton
                label={appStrings.organizationsMenu}
                trigger={
                    <Button variant="ghost">
                        <Building aria-hidden="true" size={16} />
                        <span className="sr-only">{appStrings.organization}: </span>
                        <span className="max-w-32 truncate sm:max-w-48">{orgName ?? appStrings.selectOrganization}</span>
                        <ChevronDown aria-hidden="true" size={16} />
                    </Button>
                }
            >
                {orgs.map((o) => (
                    <MenuItem key={o.id} id={o.id} href={`/orgs/${o.id}/projects`} textValue={o.name} icon={<Building aria-hidden="true" size={16} />}>
                        {o.name}
                    </MenuItem>
                ))}
                {orgs.length > 0 ? <MenuSeparator /> : null}
                <MenuItem id="create-org" href="/orgs/new" icon={<Plus aria-hidden="true" size={16} />}>
                    {appStrings.createOrg}
                </MenuItem>
            </MenuButton>
            {org ? (
                <>
                    <span aria-hidden="true" className="hidden text-text-muted sm:inline">
                        /
                    </span>
                    <div className="hidden sm:block">
                        <ComboBox
                            label={appStrings.project}
                            placeholder={appStrings.selectProject}
                            isLoading={workspace.projectsLoading}
                            selectedKey={project?.id ?? null}
                            items={projects.map((p) => ({ id: p.id, label: p.name, description: p.archivedAt ? appStrings.archived : undefined }))}
                            onSelect={(id) => {
                                if (id !== project?.id) router.history.push(`/projects/${id}/features`);
                            }}
                        />
                    </div>
                </>
            ) : null}
            <div className="ml-auto flex items-center gap-1">
                <ThemeMenu />
                {session ? (
                    <MenuButton
                        label={appStrings.accountMenu}
                        placement="bottom end"
                        trigger={
                            <Button variant="ghost" iconOnly>
                                <Avatar userId={session.userId} fullName={me?.fullName} email={session.email} />
                                <span className="sr-only">{appStrings.account}</span>
                                <span className="sr-only">{session.email}</span>
                            </Button>
                        }
                    >
                        <MenuHeading label={appStrings.account}>
                            <span className="block font-medium text-text">{me?.fullName ?? session.email}</span>
                            {me?.fullName ? <span className="block text-xs">{session.email}</span> : null}
                        </MenuHeading>
                        <MenuSeparator />
                        <MenuItem id="logout" icon={<LogOut aria-hidden="true" size={16} />} onAction={() => endSession('logout')}>
                            {appStrings.logOut}
                        </MenuItem>
                    </MenuButton>
                ) : null}
            </div>
        </header>
    );
}
