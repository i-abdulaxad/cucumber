// AC-10.5: review screenshots of every batch A screen and state, light and dark, at 1440×900 and 390×844.
// Run with "npm run screenshots"; the files go to review-screenshots/ (gitignored), named
// <screen>-<state>-<theme>-<width>.png.
import {fileURLToPath} from 'node:url';
import {expect, test, type Page, type Route} from '@playwright/test';
import type {components} from '../src/api/generated/auth-service';
import {acme, anaCredentials, authProblem, gatewayProblem, ids} from '../src/test/fixtures/auth';
import {FakeBackend} from '../src/test/fixtures/fakeBackend';
import {detail, payment, paymentSourceV3, scenarioProblem} from '../src/test/fixtures/scenario';

type S = components['schemas'];

const outDir = fileURLToPath(new URL('../review-screenshots/', import.meta.url));
const problemJson = 'application/problem+json';
const gusId = '9c1e3a5b-7d2f-4b6a-8e0c-2a4c6e8a0b57';

const orgs = [
    {...acme, myRole: 'MEMBER'},
    {...acme, id: '7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d', name: 'Globex', myRole: 'ADMIN', seatLimit: null},
] satisfies S['Organization'][];
const projects = [
    {id: ids.checkout, orgId: ids.acme, name: 'Checkout', createdAt: '2026-10-02T09:00:00Z', archivedAt: null, myRole: 'MEMBER'},
    {id: '2b3c4d5e-6f70-4a81-9b2c-3d4e5f6a7b8c', orgId: ids.acme, name: 'Search', createdAt: '2026-10-04T09:00:00Z', archivedAt: null, myRole: 'READER'},
    {id: '5d7e9f1a-2b3c-4d5e-8f60-718293a4b5c6', orgId: ids.acme, name: 'Billing', createdAt: '2026-09-02T09:00:00Z', archivedAt: '2026-10-20T10:00:00Z', myRole: 'MEMBER'},
] satisfies S['Project'][];
const members = [
    {userId: ids.ana, email: anaCredentials.email, fullName: 'Ana Ruiz', role: 'OWNER', joinedAt: '2026-10-01T08:30:00Z'},
    {userId: gusId, email: 'gus@example.com', fullName: 'Gus Underwood', role: 'MEMBER', joinedAt: '2026-10-03T08:30:00Z'},
] satisfies S['Member'][];

function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function token(): string {
    const now = Math.floor(Date.now() / 1000);
    return `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify({sub: gusId, email: 'gus@example.com', iat: now, exp: now + 3600}))}.c2ln`;
}

async function json(route: Route, status: number, body: unknown, contentType = 'application/json') {
    await route.fulfill({status, contentType, body: JSON.stringify(body)});
}

type OrgsMode = 'ready' | 'empty' | 'hang' | 'error' | 'forbidden';

async function world(page: Page, orgsMode: OrgsMode = 'ready') {
    await page.route('**/api/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        const method = route.request().method();
        if (method === 'POST' && path === '/api/auth/login') {
            return json(route, 401, authProblem(401, 'INVALID_CREDENTIALS', path), problemJson);
        }
        if (method === 'POST' && path === '/api/auth/register') {
            return json(route, 400, authProblem(400, 'VALIDATION_FAILED', path, {errors: [{field: 'password', message: 'Password is too common'}]}), problemJson);
        }
        if (path === '/api/orgs') {
            if (orgsMode === 'hang') return undefined;
            if (orgsMode === 'error') return json(route, 503, gatewayProblem(503, path, 'rid-7f3a9c'), problemJson);
            if (orgsMode === 'forbidden') {
                return json(route, 403, {type: 'about:blank', title: 'Forbidden', status: 403, detail: 'No access.', instance: path, code: 'FORBIDDEN'}, problemJson);
            }
            return json(route, 200, orgsMode === 'empty' ? [] : orgs);
        }
        if (path === `/api/orgs/${ids.acme}/projects`) return json(route, 200, projects);
        if (path === `/api/orgs/${ids.acme}/members`) return json(route, 200, members);
        return json(route, 404, gatewayProblem(404, path), problemJson);
    });
}

async function signIn(page: Page) {
    await page.addInitScript((jwt) => sessionStorage.setItem('cucumber.session', jwt), token());
}

async function settle(page: Page) {
    await page.waitForLoadState('networkidle', {timeout: 3000}).catch(() => undefined);
    await page.evaluate(() => document.fonts.ready);
}

for (const theme of ['light', 'dark'] as const) {
    for (const [width, height] of [[1440, 900], [390, 844]] as const) {
        test(`screenshots, ${theme}, ${width}`, async ({page}) => {
            test.setTimeout(120_000);
            await page.setViewportSize({width, height});
            await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
            const shot = async (screen: string, state: string) => {
                await settle(page);
                await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
                await page.screenshot({path: `${outDir}${screen}-${state}-${theme}-${width}.png`, fullPage: true, animations: 'disabled', caret: 'hide'});
            };

            // Auth screens.
            await world(page);
            await page.goto('/login');
            await shot('login', 'default');
            await page.getByLabel('Email').fill(anaCredentials.email);
            await page.getByLabel('Password').fill('wrong');
            await page.getByRole('button', {name: 'Log in'}).click();
            await expect(page.getByRole('alert')).toBeVisible();
            await shot('login', 'error');
            await page.getByRole('button', {name: 'Theme'}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await shot('login', 'theme-menu-open');
            await page.keyboard.press('Escape');

            await page.goto('/register');
            await shot('register', 'default');
            await page.getByLabel('Full name').fill('Fay Lin');
            await page.getByLabel('Email').fill('fay@example.com');
            await page.getByLabel('Password').fill('password');
            await page.getByRole('button', {name: 'Create account'}).click();
            await expect(page.getByRole('alert')).toBeVisible();
            await shot('register', 'validation-error');

            await page.goto('/nowhere');
            await shot('not-found', 'default');

            // Signed in: the app shell.
            await signIn(page);
            await page.goto('/orgs');
            await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
            await shot('orgs', 'ready');

            await page.goto(`/orgs/${ids.acme}/projects`);
            await expect(page.getByRole('heading', {level: 1, name: 'Projects'})).toBeVisible();
            await shot('org-projects', 'ready');

            await page.goto(`/projects/${ids.checkout}/features?tag=%40smoke`);
            await expect(page.getByRole('heading', {level: 1, name: 'Features'})).toBeVisible();
            await expect(page.getByRole('button', {name: /Account/})).toContainText('GU');
            await shot('project-features', 'ready');

            await page.getByRole('button', {name: /Account/}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await shot('shell', 'user-menu-open');
            await page.keyboard.press('Escape');

            await page.getByRole('button', {name: 'Theme'}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await shot('shell', 'theme-menu-open');
            await page.keyboard.press('Escape');

            await page.getByRole('button', {name: /Organization/}).click();
            await expect(page.getByRole('menu')).toBeVisible();
            await shot('shell', 'org-menu-open');
            await page.keyboard.press('Escape');

            if (width === 390) {
                await page.getByRole('button', {name: 'Open navigation'}).click();
                await expect(page.getByRole('dialog', {name: 'Navigation'})).toBeVisible();
                await shot('shell', 'drawer-open');
                await page.keyboard.press('Escape');
            } else {
                await page.getByRole('combobox', {name: 'Project'}).click();
                await expect(page.getByRole('listbox')).toBeVisible();
                await shot('shell', 'project-switcher-open');
                await page.keyboard.press('Escape');
                await page.getByRole('button', {name: 'Collapse sidebar'}).click();
                await shot('shell', 'sidebar-collapsed');
                await page.getByRole('button', {name: 'Expand sidebar'}).click();
            }

            // Org list view states.
            for (const mode of ['empty', 'hang', 'error', 'forbidden'] as const) {
                await page.unrouteAll({behavior: 'ignoreErrors'});
                await world(page, mode);
                await page.goto('/orgs');
                if (mode === 'hang') await expect(page.locator('[data-skeleton]').first()).toBeVisible();
                else if (mode === 'empty') await expect(page.getByText("You're not in an organization yet")).toBeVisible();
                else await expect(page.getByRole('alert')).toBeVisible({timeout: 10_000});
                await shot('orgs', mode === 'hang' ? 'loading' : mode);
            }
            await page.unrouteAll({behavior: 'ignoreErrors'});
        });
    }
}

// Batch B (additive): the feature tree and the editor.
for (const theme of ['light', 'dark'] as const) {
    for (const [width, height] of [[1440, 900], [390, 844]] as const) {
        test(`screenshots, batch B, ${theme}, ${width}`, async ({page}) => {
            test.setTimeout(120_000);
            await page.setViewportSize({width, height});
            await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
            await signIn(page);
            const backend = new FakeBackend('gus');
            await page.route('**/api/**', async (route) => {
                const request = route.request();
                const raw = request.postData();
                const answer = backend.answer(request.method(), new URL(request.url()), raw ? JSON.parse(raw) : null);
                if (!answer) return route.abort();
                return route.fulfill({status: answer.status, contentType: answer.contentType, body: JSON.stringify(answer.body)});
            });
            const shot = async (screen: string, state: string) => {
                await settle(page);
                await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
                await page.screenshot({path: `${outDir}${screen}-${state}-${theme}-${width}.png`, fullPage: true, animations: 'disabled', caret: 'hide'});
            };
            const base = `/projects/${ids.checkout}/features`;
            const tree = page.getByRole('treegrid', {name: 'Features'});

            await page.goto(base);
            await expect(tree.getByRole('row')).toHaveCount(4);
            await shot('tree', 'expanded');
            await page.goto(`${base}?tag=%40smoke`);
            await expect(tree).toContainText('address.feature');
            await shot('tree', 'tag-filter');
            await page.goto(`${base}?view=archived`);
            await expect(tree).toContainText('legacy.feature');
            await shot('tree', 'archived');
            await page.goto(base);
            await page.getByRole('button', {name: 'New feature'}).click();
            await expect(page.getByRole('dialog', {name: 'New feature'})).toBeVisible();
            await shot('tree', 'create-dialog');
            await page.keyboard.press('Escape');

            await page.goto(`${base}?feature=${payment.id}`);
            await expect(page.getByText('Version 2')).toBeVisible();
            await shot('editor', 'valid');

            const content = page.getByRole('textbox', {name: 'Gherkin source'});
            await content.click();
            await page.keyboard.press('ControlOrMeta+a');
            await page.keyboard.insertText('@checkout\nFeature: Payment\n\n  Scenario: Pay by card\n    Given a cart with 2 items\n    Whenn I pay by card\n    Then the order is confirmed\n');
            await page.keyboard.press('ControlOrMeta+s');
            await expect(page.locator('.cm-lintRange-error')).toHaveCount(1);
            await shot('editor', 'parse-errors');

            backend.saveOverride = (body, current) => {
                backend.features.set(current.id, detail(payment, paymentSourceV3, {currentVersion: 3}));
                return {status: 409, contentType: 'application/problem+json', body: scenarioProblem(409, 'VERSION_CONFLICT', '/x', {currentVersion: 3})};
            };
            await page.keyboard.press('Escape');
            await content.click();
            await page.keyboard.press('ControlOrMeta+s');
            await expect(page.getByRole('alertdialog', {name: 'This feature changed'})).toBeVisible();
            await shot('editor', 'conflict-dialog');
            backend.saveOverride = null;
            await page.getByRole('button', {name: 'Reload latest version'}).click();
            await expect(page.getByRole('region', {name: 'Your unsaved text'})).toBeVisible();
            await shot('editor', 'reloaded-with-unsaved-text');
        });
    }
}

