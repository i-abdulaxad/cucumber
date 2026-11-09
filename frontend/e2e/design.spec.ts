import {expect, test, type Page, type Route} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import type {components} from '../src/api/generated/auth-service';
import {acme, anaCredentials, gatewayProblem, ids} from '../src/test/fixtures/auth';

type S = components['schemas'];

declare global {
    interface Window {
        /** The data-theme seen by the first animation frame, before first paint (AC-10.1). */
        firstFrameTheme?: string;
        /** Proves a theme switch happened without a reload. */
        marker?: number;
    }
}
const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const problemJson = 'application/problem+json';

const gusId = '9c1e3a5b-7d2f-4b6a-8e0c-2a4c6e8a0b57';
const billingId = '5d7e9f1a-2b3c-4d5e-8f60-718293a4b5c6';

const acmeForGus = {...acme, myRole: 'MEMBER'} satisfies S['Organization'];
const checkout = {
    id: ids.checkout,
    orgId: ids.acme,
    name: 'Checkout',
    createdAt: '2026-10-02T09:00:00Z',
    archivedAt: null,
    myRole: 'MEMBER',
} satisfies S['Project'];
const billing = {
    ...checkout,
    id: billingId,
    name: 'Billing',
    archivedAt: '2026-10-20T10:00:00Z'
} satisfies S['Project'];
const members = [
    {
        userId: ids.ana,
        email: anaCredentials.email,
        fullName: 'Ana Ruiz',
        role: 'OWNER',
        joinedAt: '2026-10-01T08:30:00Z'
    },
    {
        userId: gusId,
        email: 'gus@example.com',
        fullName: 'Gus Underwood',
        role: 'MEMBER',
        joinedAt: '2026-10-03T08:30:00Z'
    },
] satisfies S['Member'][];

function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function token(sub: string, email: string): string {
    const now = Math.floor(Date.now() / 1000);
    return `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify({
        sub,
        email,
        iat: now,
        exp: now + 3600
    }))}.c2ln`;
}

async function json(route: Route, status: number, body: unknown, contentType = 'application/json') {
    await route.fulfill({status, contentType, body: JSON.stringify(body)});
}

async function api(page: Page, handlers: Record<string, (route: Route) => Promise<void>>) {
    const unexpected: string[] = [];
    await page.route('**/api/**', async (route) => {
        const key = `${route.request().method()} ${new URL(route.request().url()).pathname}`;
        const handler = handlers[key];
        if (handler) await handler(route);
        else {
            unexpected.push(key);
            await route.abort();
        }
    });
    return unexpected;
}

async function signIn(page: Page, sub: string, email: string) {
    await page.addInitScript((jwt) => sessionStorage.setItem('cucumber.session', jwt), token(sub, email));
}

const gusWorld = {
    'GET /api/orgs': (route: Route) => json(route, 200, [acmeForGus]),
    [`GET /api/orgs/${ids.acme}/projects`]: (route: Route) => json(route, 200, [checkout, billing]),
    [`GET /api/orgs/${ids.acme}/members`]: (route: Route) => json(route, 200, members),
};

async function expectNoAxeViolations(page: Page) {
    const results = await new AxeBuilder({page}).withTags(WCAG).analyze();
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

// @US-10 @AC-10.1
test.describe('AC-10.1 the theme follows the OS until chosen', () => {
    const rows: Array<[string | null, 'light' | 'dark', 'light' | 'dark']> = [
        [null, 'dark', 'dark'],
        [null, 'light', 'light'],
        ['system', 'dark', 'dark'],
        ['light', 'dark', 'light'],
        ['dark', 'light', 'dark'],
    ];
    for (const [stored, os, applied] of rows) {
        test(`stored ${stored ?? '(none)'}, OS ${os}: ${applied} before first paint`, async ({browser}) => {
            const context = await browser.newContext({colorScheme: os});
            const page = await context.newPage();
            await api(page, {});
            await page.addInitScript((value) => {
                if (value !== null) localStorage.setItem('cucumber.ui.theme', value);
                const record = () => {
                    window.firstFrameTheme ??= document.documentElement.getAttribute('data-theme') ?? 'none';
                };
                requestAnimationFrame(record);
            }, stored);
            await page.goto('/login');
            await expect(page.locator('html')).toHaveAttribute('data-theme', applied);
            expect(await page.evaluate(() => window.firstFrameTheme)).toBe(applied);
            await context.close();
        });
    }

    test('picking Dark switches without reload, shows it checked, and survives a reload; System follows the OS live', async ({page}) => {
        await page.emulateMedia({colorScheme: 'light'});
        await api(page, {});
        await page.goto('/login');
        await page.evaluate(() => {
            window.marker = 42;
        });
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');

        await page.getByRole('button', {name: 'Theme'}).click();
        await page.getByRole('menuitemradio', {name: 'Dark'}).click();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
        expect(await page.evaluate(() => window.marker)).toBe(42);
        await page.getByRole('button', {name: 'Theme'}).click();
        await expect(page.getByRole('menuitemradio', {name: 'Dark'})).toHaveAttribute('aria-checked', 'true');
        await page.keyboard.press('Escape');

        await page.reload();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

        await page.getByRole('button', {name: 'Theme'}).click();
        await page.getByRole('menuitemradio', {name: 'System'}).click();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
        await page.emulateMedia({colorScheme: 'dark'});
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    });
});

// @US-10 @AC-10.2
test.describe('AC-10.2 the app shell', () => {
    test('desktop 1440×900: top bar, sidebar, collapse that survives a reload', async ({page}) => {
        await page.setViewportSize({width: 1440, height: 900});
        await signIn(page, gusId, 'gus@example.com');
        const unexpected = await api(page, gusWorld);
        await page.goto(`/projects/${ids.checkout}/features`);

        const banner = page.getByRole('banner');
        await expect(banner.getByText('Cucumber', {exact: true})).toBeVisible();
        await expect(banner.getByRole('button', {name: /Organization: Acme/})).toBeVisible();
        await expect(banner.getByRole('combobox', {name: 'Project'})).toHaveValue('Checkout');
        await expect(banner.getByRole('button', {name: 'Theme'})).toBeVisible();
        await expect(banner.getByRole('button', {name: /Account/})).toContainText('GU');

        const nav = page.getByRole('navigation', {name: 'Sidebar'});
        await expect(nav.getByRole('link')).toHaveText(['Projects', 'Members', 'Features', 'Runs', 'Members', 'Settings']);
        await expect(nav.getByRole('link', {name: 'Upload tokens'})).toHaveCount(0);
        await expect(nav.getByRole('link', {name: 'Features'})).toHaveAttribute('aria-current', 'page');
        await expectNoAxeViolations(page);

        await page.getByRole('button', {name: 'Collapse sidebar'}).click();
        await expect.poll(async () => (await nav.boundingBox())?.width).toBe(56);
        await expect(nav.getByRole('link', {name: 'Runs'})).toBeVisible();
        await nav.getByRole('link', {name: 'Runs'}).hover();
        await expect(page.getByRole('tooltip')).toHaveText('Runs');

        await page.reload();
        await expect.poll(async () => (await page.getByRole('navigation', {name: 'Sidebar'}).boundingBox())?.width).toBe(56);
        await page.getByRole('button', {name: 'Expand sidebar'}).click();
        await expect.poll(async () => (await nav.boundingBox())?.width).toBe(240);
        expect(unexpected).toEqual([]);
    });

    test('mobile 390×844: the drawer traps focus; Escape or an item closes it and returns focus', async ({page}) => {
        await page.setViewportSize({width: 390, height: 844});
        await signIn(page, gusId, 'gus@example.com');
        await api(page, gusWorld);
        await page.goto(`/projects/${ids.checkout}/features`);
        await expect(page.getByRole('heading', {level: 1, name: 'Features'})).toBeVisible();
        await expect(page.getByRole('navigation', {name: 'Sidebar'})).toHaveCount(0);

        const menuButton = page.getByRole('button', {name: 'Open navigation'});
        await menuButton.click();
        const drawer = page.getByRole('dialog', {name: 'Navigation'});
        await expect(drawer).toBeVisible();
        await expectNoAxeViolations(page);
        for (let i = 0; i < 12; i += 1) {
            await page.keyboard.press('Tab');
            expect(await drawer.evaluate((el) => el.contains(document.activeElement))).toBe(true);
        }
        await page.keyboard.press('Escape');
        await expect(drawer).toHaveCount(0);
        await expect(menuButton).toBeFocused();

        await menuButton.click();
        await page.getByRole('dialog', {name: 'Navigation'}).getByRole('link', {name: 'Projects'}).click();
        await expect(page).toHaveURL(`/orgs/${ids.acme}/projects`);
        await expect(page.getByRole('dialog', {name: 'Navigation'})).toHaveCount(0);
        await expect(menuButton).toBeFocused();
    });
});

// @US-9 @AC-9.3 in both themes, for the batch A routes and states.
for (const theme of ['light', 'dark'] as const) {
    test.describe(`AC-9.3 axe, ${theme} theme`, () => {
        test.beforeEach(async ({page}) => {
            await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
        });

        test('login, register and not-found', async ({page}) => {
            await api(page, {});
            for (const path of ['/login', '/register']) {
                await page.goto(path);
                await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
                await expect(page.getByRole('heading', {level: 1})).toBeVisible();
                await expectNoAxeViolations(page);
            }
            await page.getByRole('button', {name: 'Theme'}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await expectNoAxeViolations(page);
        });

        test('org list ready and empty, the not-found page, and the menus open', async ({page}) => {
            await signIn(page, gusId, 'gus@example.com');
            let empty = false;
            await api(page, {...gusWorld, 'GET /api/orgs': (route) => json(route, 200, empty ? [] : [acmeForGus])});
            await page.goto('/orgs');
            await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
            await expectNoAxeViolations(page);
            empty = true;
            await page.reload();
            await expect(page.getByText("You're not in an organization yet")).toBeVisible();
            await expectNoAxeViolations(page);
            await page.goto('/nowhere');
            await expect(page.getByRole('heading', {level: 1, name: 'Page not found'})).toBeVisible();
            await expectNoAxeViolations(page);
            await page.goto(`/projects/${ids.checkout}/features`);
            await page.getByRole('button', {name: /Account/}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await expectNoAxeViolations(page);
        });

        test('loading, error and forbidden states', async ({page}) => {
            await signIn(page, gusId, 'gus@example.com');
            let mode: 'hang' | 'error' | 'forbidden' = 'hang';
            await api(page, {
                'GET /api/orgs': async (route) => {
                    if (mode === 'hang') return; // never answered: the loading state
                    if (mode === 'error') return json(route, 503, gatewayProblem(503, '/api/orgs', 'rid-503'), problemJson);
                    return json(
                        route,
                        403,
                        {
                            type: 'about:blank',
                            title: 'Forbidden',
                            status: 403,
                            detail: 'No access.',
                            instance: '/api/orgs',
                            code: 'FORBIDDEN'
                        },
                        problemJson,
                    );
                },
            });
            await page.goto('/orgs');
            await expect(page.locator('[data-skeleton]').first()).toBeVisible();
            await expectNoAxeViolations(page);
            mode = 'error';
            await page.reload();
            await expect(page.getByRole('alert')).toContainText('The service is unavailable. Try again.', {timeout: 10_000});
            await expectNoAxeViolations(page);
            mode = 'forbidden';
            await page.reload();
            await expect(page.getByRole('alert')).toContainText("You don't have access to this");
            await expectNoAxeViolations(page);
        });
    });
}

// @US-10 @AC-10.6
test('AC-10.6 reduced motion: no transitions on menus and the drawer, and Skeletons do not shimmer', async ({browser}) => {
    const context = await browser.newContext({reducedMotion: 'reduce', viewport: {width: 390, height: 844}});
    const page = await context.newPage();
    await signIn(page, gusId, 'gus@example.com');
    await api(page, {...gusWorld, 'GET /api/orgs': async () => undefined});
    await page.goto('/orgs');
    const skeleton = page.locator('[data-skeleton]').first();
    await expect(skeleton).toBeVisible();
    expect(await skeleton.evaluate((el) => getComputedStyle(el).animationName)).toBe('none');

    await page.getByRole('button', {name: 'Theme'}).click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    const popover = menu.locator('xpath=ancestor-or-self::*[@data-rac or @role="dialog"][1]');
    for (const el of [menu, popover]) {
        const motion = await el.evaluate((node) => {
            const style = getComputedStyle(node);
            return {transition: style.transitionDuration, animation: style.animationName};
        });
        expect(motion.transition.split(',').every((d) => d.trim() === '0s')).toBe(true);
        expect(motion.animation).toBe('none');
    }
    await page.keyboard.press('Escape');

    await page.getByRole('button', {name: 'Open navigation'}).click();
    const drawer = page.getByRole('dialog', {name: 'Navigation'});
    await expect(drawer).toBeVisible();
    const drawerMotion = await drawer.evaluate((node) => getComputedStyle(node).transitionDuration);
    expect(drawerMotion.split(',').every((d) => d.trim() === '0s')).toBe(true);
    await context.close();
});
