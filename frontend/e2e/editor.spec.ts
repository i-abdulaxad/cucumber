import {expect, test, type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {ids} from '../src/test/fixtures/auth';
import {FakeBackend} from '../src/test/fixtures/fakeBackend';
import {billingId, users, type UserKey} from '../src/test/fixtures/workspace';
import {detail, payment, paymentSourceV3, scenarioProblem} from '../src/test/fixtures/scenario';

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function tokenFor(user: UserKey): string {
    const now = Math.floor(Date.now() / 1000);
    return `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify({sub: users[user].id, email: users[user].email, exp: now + 3600}))}.c2ln`;
}

async function wire(page: Page, backend: FakeBackend, user: UserKey) {
    await page.route('**/api/**', async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        if (url.pathname === '/api/auth/login') {
            return route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({accessToken: tokenFor(user)})});
        }
        const raw = request.postData();
        const answer = backend.answer(request.method(), url, raw ? JSON.parse(raw) : null);
        if (!answer) return route.abort();
        return route.fulfill({status: answer.status, contentType: answer.contentType, body: JSON.stringify(answer.body)});
    });
}

async function openEditor(page: Page, user: UserKey = 'gus', backend = new FakeBackend(user), projectId: string = ids.checkout) {
    await page.addInitScript((jwt) => sessionStorage.setItem('cucumber.session', jwt), tokenFor(user));
    await wire(page, backend, user);
    await page.goto(`/projects/${projectId}/features?feature=${payment.id}`);
    await expect(page.getByText(/^Version \d+$/)).toBeVisible();
    return backend;
}

async function expectNoAxeViolations(page: Page) {
    // Check the settled UI: wait for overlays to finish their entry animation (opacity) first.
    await expect(page.locator('[data-entering]')).toHaveCount(0);
    const results = await new AxeBuilder({page}).withTags(WCAG).analyze();
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

const editor = (page: Page) => page.getByRole('textbox', {name: 'Gherkin source'});
const line = (page: Page, n: number) => page.locator('.cm-line').nth(n - 1);

/** Replaces the whole document as typed text (no auto-indent). */
async function replaceDoc(page: Page, text: string) {
    await editor(page).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText(text);
}

// @US-6 @AC-6.1
test('AC-6.1 edit, enter a message and save with Ctrl+S', async ({page}) => {
    const backend = await openEditor(page);
    await line(page, 6).click();
    await page.keyboard.press('End');
    await page.keyboard.type(' today');
    await expect(page.getByText('Unsaved changes')).toBeVisible();
    await page.getByLabel('Change message (optional)').fill('Clarify card step');
    await editor(page).click();
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByText('Version 3')).toBeVisible();
    await expect(page.getByRole('status').filter({hasText: 'Saved'})).toBeVisible();
    await expect(page.getByText('Unsaved changes')).toHaveCount(0);
    const put = backend.requestsMatching('PUT', `/features/${payment.id}`).at(-1);
    expect(put?.body).toEqual({
        path: payment.path,
        source: expect.stringContaining('When I pay by card today'),
        baseVersion: 2,
        message: 'Clarify card step',
    });
});

// @US-6 @AC-6.2
test('AC-6.2 Gherkin errors show where they are; F8 moves between them', async ({page}) => {
    await openEditor(page);
    await replaceDoc(page, 'Feature: Pay\n\n  Scenario: Card\n  Whenn I pay\n');
    await page.keyboard.press('ControlOrMeta+s');
    const marked = page.locator('.cm-lintRange-error');
    await expect(marked).toHaveCount(1);
    await expect(marked).toHaveText('W');
    await expect(line(page, 4)).toContainText('Whenn I pay');
    await expect(line(page, 4).locator('.cm-lintRange-error')).toHaveCount(1);
    await expect(page.locator('.cm-panel-lint')).toContainText("(4:3): expected: #EOF, #TableRow, ... got 'Whenn I pay'");
    await expect(page.getByRole('status').filter({hasText: '1 Gherkin error; first on line 4'})).toBeAttached();
    await expect(line(page, 4)).toHaveText('  Whenn I pay');
    await expect(page.getByText('Unsaved changes')).toBeVisible();
    await expectNoAxeViolations(page);

    await replaceDoc(page, 'Feature: Pay\n  Whenn a\n  Scenario: Card\n  Whenn b\n  Then c\n  Whenn d\n');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByRole('status').filter({hasText: '3 Gherkin errors; first on line 2'})).toBeAttached();
    await expect(page.locator('.cm-lintRange-error')).toHaveCount(3);
    await editor(page).click();
    await page.keyboard.press('ControlOrMeta+Home');
    await page.keyboard.press('F8');
    await expect(page.locator('.cm-activeLine')).toContainText('Whenn a');
    await page.keyboard.press('F8');
    await expect(page.locator('.cm-activeLine')).toContainText('Whenn b');
});

// @US-6 @AC-6.4
test('AC-6.4 someone else saved first: reload, keep the text aside, never resend', async ({page}) => {
    const backend = await openEditor(page);
    backend.saveOverride = (body, current) => {
        backend.features.set(current.id, detail(payment, paymentSourceV3, {currentVersion: 3}));
        return {status: 409, contentType: 'application/problem+json', body: scenarioProblem(409, 'VERSION_CONFLICT', '/x', {currentVersion: 3})};
    };
    await line(page, 6).click();
    await page.keyboard.press('End');
    await page.keyboard.type(' now');
    await page.keyboard.press('ControlOrMeta+s');
    const dialog = page.getByRole('alertdialog', {name: 'This feature changed'});
    await expect(dialog).toContainText('This feature changed (now version 3) since you opened it');
    await expect.poll(() => dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
    await expectNoAxeViolations(page);
    backend.saveOverride = null;
    await dialog.getByRole('button', {name: 'Reload latest version'}).click();
    await expect(page.getByText('Version 3')).toBeVisible();
    await expect(line(page, 7)).toContainText('confirmed by email');
    const pane = page.getByRole('region', {name: 'Your unsaved text'});
    await expect(pane).toContainText('When I pay by card now');
    await page.waitForTimeout(300);
    const bases = backend.requestsMatching('PUT', `/features/${payment.id}`).map((r) => (typeof r.body === 'object' && r.body !== null && 'baseVersion' in r.body ? r.body.baseVersion : undefined));
    expect(bases).toEqual([2]);
    await pane.getByRole('button', {name: 'Dismiss'}).click();
    await expect(pane).toHaveCount(0);
});

// @US-6 @AC-6.5
test('AC-6.5 leaving with unsaved changes asks first; the tab close prompts too', async ({page}) => {
    await openEditor(page);
    await line(page, 6).click();
    await page.keyboard.press('End');
    await page.keyboard.type(' later');
    await page.getByRole('treegrid', {name: 'Features'}).getByRole('row', {name: /address\.feature/}).click();
    const dialog = page.getByRole('alertdialog', {name: 'Discard unsaved changes?'});
    await expect(dialog).toBeVisible();
    await expectNoAxeViolations(page);
    await dialog.getByRole('button', {name: 'Keep editing'}).click();
    await expect(dialog).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`feature=${payment.id}`));
    await expect(line(page, 6)).toContainText('When I pay by card later');

    let prompted = false;
    page.on('dialog', (d) => {
        if (d.type() === 'beforeunload') prompted = true;
        void d.dismiss();
    });
    await page.close({runBeforeUnload: true});
    await expect.poll(() => prompted).toBe(true);
});

// @US-6 @AC-6.6
test.describe('AC-6.6 read-only editor', () => {
    for (const [user, banner] of [
        ['hal', 'Read only: your role is TESTER'],
        ['dan', 'Read only: your role is READER'],
    ] as const) {
        test(`${user}: ${banner}`, async ({page}) => {
            await openEditor(page, user);
            await expect(page.getByText(banner)).toBeVisible();
            await expect(page.getByRole('button', {name: 'Save'})).toHaveCount(0);
            await expect(editor(page)).toHaveAttribute('aria-readonly', 'true');
        });
    }

    test('Ana in the archived project Billing', async ({page}) => {
        await openEditor(page, 'ana', new FakeBackend('ana'), billingId);
        await expect(page.getByText('Project archived: read only')).toBeVisible();
        await expect(page.getByRole('button', {name: 'Save'})).toHaveCount(0);
    });
});

// @US-9 @AC-9.4
test('AC-9.4 keyboard-only journey: log in, open Checkout, edit, see a 422 marker, fix and save', async ({page}) => {
    const backend = new FakeBackend('gus');
    await wire(page, backend, 'gus');
    await page.goto('/login');

    async function tabTo(check: () => Promise<boolean>, label: string) {
        for (let i = 0; i < 40; i += 1) {
            await page.keyboard.press('Tab');
            if (await check()) return;
        }
        throw new Error(`Tab never reached ${label}`);
    }
    const focusedName = () => page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? document.activeElement?.textContent ?? '');
    async function expectFocusVisibleNotBody() {
        const state = await page.evaluate(() => ({
            body: document.activeElement === document.body || document.activeElement === null,
            visible: document.activeElement?.matches(':focus-visible') ?? false,
        }));
        expect(state.body).toBe(false);
        expect(state.visible).toBe(true);
    }

    await tabTo(async () => (await page.evaluate(() => document.activeElement?.id)) === 'login-email', 'email');
    await page.keyboard.type(users.gus.email);
    await page.keyboard.press('Tab');
    await page.keyboard.type('Secret123!');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
    await expectFocusVisibleNotBody();

    await tabTo(async () => (await focusedName()).trim() === 'Acme', 'Acme');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(`/orgs/${ids.acme}/projects`);
    await tabTo(async () => (await page.evaluate(() => document.activeElement?.getAttribute('role'))) === 'combobox', 'project switcher');
    await page.keyboard.type('Check');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(`/projects/${ids.checkout}/features`);

    await tabTo(async () => (await page.evaluate(() => document.activeElement?.getAttribute('role'))) === 'row', 'tree');
    await expectFocusVisibleNotBody();
    await page.keyboard.type('pay');
    await page.keyboard.press('Enter');
    await expect(page.getByText('Version 2')).toBeVisible();

    await tabTo(async () => (await page.evaluate(() => document.activeElement?.classList.contains('cm-content'))) === true, 'editor');
    await page.keyboard.press('ControlOrMeta+Home');
    for (let i = 0; i < 5; i += 1) await page.keyboard.press('ArrowDown');
    await page.keyboard.press('End');
    await page.keyboard.type(' today');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByText('Version 3')).toBeVisible();

    await page.keyboard.press('Home');
    for (let i = 0; i < 4; i += 1) await page.keyboard.press('ArrowRight');
    await page.keyboard.type('n');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.locator('.cm-lintRange-error')).toHaveCount(1);
    await expect(page.getByRole('status').filter({hasText: '1 Gherkin error; first on line 6'})).toBeAttached();

    // The lint panel took focus with the errors; Escape returns to the editor at the same place.
    await expect(page.locator('.cm-panel-lint [role=listbox]')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(editor(page)).toBeFocused();
    await page.keyboard.press('Backspace');
    await page.keyboard.press('End');
    await page.keyboard.type(' again');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByText('Version 4')).toBeVisible();
    await expect(line(page, 6)).toHaveText('    When I pay by card today again');
    await expect(page.locator('.cm-lintRange-error')).toHaveCount(0);
});

// @US-9 @AC-9.3 in both themes.
for (const theme of ['light', 'dark'] as const) {
    test(`AC-9.3 the editor passes axe, ${theme}`, async ({page}) => {
        await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
        await openEditor(page);
        await expectNoAxeViolations(page);
        await page.getByRole('tab', {name: 'History'}).click();
        await expectNoAxeViolations(page);
    });

    test(`AC-9.3 the read-only editor passes axe, ${theme}`, async ({page}) => {
        await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
        await openEditor(page, 'dan');
        await expect(page.getByText('Read only: your role is READER')).toBeVisible();
        await expectNoAxeViolations(page);
    });
}
