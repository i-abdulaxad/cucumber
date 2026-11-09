import {expect, test, type Page} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {ids} from '../src/test/fixtures/auth';
import {FakeBackend} from '../src/test/fixtures/fakeBackend';
import {users, type UserKey} from '../src/test/fixtures/workspace';
import {address, featureIds, legacy, manyFeatures, payment} from '../src/test/fixtures/scenario';

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function setup(page: Page, user: UserKey = 'gus', backend = new FakeBackend(user)) {
    const now = Math.floor(Date.now() / 1000);
    const jwt = `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify({sub: users[user].id, email: users[user].email, exp: now + 3600}))}.c2ln`;
    await page.addInitScript((value) => sessionStorage.setItem('cucumber.session', value), jwt);
    await page.route('**/api/**', async (route) => {
        const request = route.request();
        const raw = request.postData();
        const answer = backend.answer(request.method(), new URL(request.url()), raw ? JSON.parse(raw) : null);
        if (!answer) return route.abort();
        return route.fulfill({status: answer.status, contentType: answer.contentType, body: JSON.stringify(answer.body)});
    });
    return backend;
}

async function expectNoAxeViolations(page: Page) {
    // Check the settled UI: wait for overlays to finish their entry animation (opacity) first.
    await expect(page.locator('[data-entering]')).toHaveCount(0);
    const results = await new AxeBuilder({page}).withTags(WCAG).analyze();
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

const tree = (page: Page) => page.getByRole('treegrid', {name: 'Features'});
const rows = (page: Page) => tree(page).getByRole('row');
const featuresUrl = `/projects/${ids.checkout}/features`;

// @US-5 @AC-5.1
test('AC-5.1 the tree is built from paths', async ({page}) => {
    await setup(page);
    await page.goto(featuresUrl);
    await expect(rows(page)).toHaveCount(4);
    const texts = await rows(page).evaluateAll((els) => els.map((el) => el.textContent ?? ''));
    expect(texts[0]).toContain('features');
    expect(texts[1]).toContain('checkout');
    expect(texts[2]).toContain('address.feature');
    expect(texts[2]).toContain('@smoke');
    expect(texts[2]).toContain('1 test case');
    expect(texts[3]).toContain('payment.feature');
    expect(texts[3]).toContain('@checkout');
    await expect(tree(page)).not.toContainText('legacy.feature');
});

// @US-5 @AC-5.2
test('AC-5.2 filter by a tag, and clear it', async ({page}) => {
    const backend = await setup(page);
    await page.goto(featuresUrl);
    await expect(rows(page)).toHaveCount(4);
    const chip = page.getByRole('group', {name: 'Filter by tag'}).getByRole('button', {name: '@smoke'});
    await chip.click();
    await expect(page).toHaveURL(/tag=%40smoke/);
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    await expect(tree(page)).toContainText('address.feature');
    await expect(tree(page)).not.toContainText('payment.feature');
    expect(backend.requests.some((r) => r.method === 'GET' && r.path.endsWith('/features') && r.search === '?tag=%40smoke')).toBe(true);
    await chip.click();
    await expect(page).not.toHaveURL(/tag=/);
    await expect(chip).toHaveAttribute('aria-pressed', 'false');
    await expect(tree(page)).toContainText('payment.feature');
});

// @US-5 @AC-5.3
test('AC-5.3 archive after confirming, and a restore that clashes with an active path', async ({page}) => {
    const backend = await setup(page);
    await page.goto(`${featuresUrl}?feature=${payment.id}`);
    await page.getByRole('button', {name: 'Archive feature'}).click();
    const confirm = page.getByRole('alertdialog', {name: 'Archive this feature?'});
    await expect(confirm).toBeVisible();
    await expectNoAxeViolations(page);
    await confirm.getByRole('button', {name: 'Archive feature'}).click();
    await expect(page.getByText('Feature archived: read only')).toBeVisible();
    await expect(tree(page)).not.toContainText('payment.feature');

    await page.getByRole('tab', {name: 'Archived'}).click();
    await expect(page).toHaveURL(/view=archived/);
    await expect(tree(page)).toContainText('payment.feature');
    await expect(tree(page)).toContainText('legacy.feature');

    // Someone creates an active feature at the same path; restoring now clashes.
    const clash = backend.features.get(address.id);
    if (!clash) throw new Error('fixture');
    backend.features.set('3c5e7a9c-1d3f-4a5c-8e7a-9c1d3f5a7c9e', {...clash, id: '3c5e7a9c-1d3f-4a5c-8e7a-9c1d3f5a7c9e', path: payment.path});
    await page.getByRole('button', {name: 'Restore feature'}).click();
    await expect(page.getByRole('alert').filter({hasText: 'An active feature already uses this path'})).toBeVisible();
    await expect(page.getByText('Feature archived: read only')).toBeVisible();
});

// @US-5 @AC-5.4
test('AC-5.4 create a feature; path errors stay at the path field', async ({page}) => {
    const backend = await setup(page);
    await page.goto(featuresUrl);
    await page.getByRole('button', {name: 'New feature'}).click();
    const dialog = page.getByRole('dialog', {name: 'New feature'});
    await expectNoAxeViolations(page);

    await dialog.getByLabel('Path').fill('features/checkout/refund.txt');
    await dialog.getByRole('button', {name: 'Create feature'}).click();
    await expect(dialog.getByLabel('Path')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByLabel('Path')).toHaveAccessibleDescription(/must end with \.feature/);

    await dialog.getByLabel('Path').fill(address.path);
    await dialog.getByRole('button', {name: 'Create feature'}).click();
    await expect(dialog.getByLabel('Path')).toHaveAccessibleDescription(/An active feature already uses this path/);

    await dialog.getByLabel('Path').fill('features/checkout/refund.feature');
    await dialog.getByRole('button', {name: 'Create feature'}).click();
    await expect(page).toHaveURL(new RegExp(`feature=${featureIds.refund}`));
    const posts = backend.requestsMatching('POST', '/features').filter((r) => !r.path.includes('/archive'));
    expect(posts.at(-1)?.body).toEqual({path: 'features/checkout/refund.feature', source: 'Feature: Refund\n'});
    await expect(page.getByText('Version 1')).toBeVisible();
    await expect(tree(page).getByRole('row', {name: /refund\.feature/})).toHaveAttribute('aria-selected', 'true');
});

// @US-5 @AC-5.5
test('AC-5.5 a 2,000-feature tree stays small in the DOM and works by keyboard', async ({page}) => {
    const backend = new FakeBackend('gus');
    backend.extraSummaries = manyFeatures().filter((f) => f.id !== payment.id);
    await setup(page, 'gus', backend);
    await page.setViewportSize({width: 1440, height: 900});
    await page.goto(featuresUrl);
    await expect(rows(page).first()).toBeVisible();
    expect(await rows(page).count()).toBeLessThan(200);

    await rows(page).first().focus();
    await page.keyboard.press('ArrowDown');
    await expect(rows(page).nth(1)).toBeFocused();
    await page.keyboard.press('End');
    await expect(tree(page).locator('[role=row]:focus')).toContainText(/\.feature|features/);
    await page.keyboard.press('Home');
    await expect(rows(page).first()).toBeFocused();
    await expect(rows(page).first()).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('ArrowLeft');
    await expect(rows(page).first()).toHaveAttribute('aria-expanded', 'false');
    await page.keyboard.press('ArrowRight');
    await expect(rows(page).first()).toHaveAttribute('aria-expanded', 'true');

    await page.keyboard.type('pay');
    await expect(tree(page).locator('[role=row]:focus')).toContainText('payment.feature');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`feature=${payment.id}`));
    expect(await rows(page).count()).toBeLessThan(200);
});

// @US-9 @AC-9.2 and @AC-9.3 for the tree, in both themes.
for (const theme of ['light', 'dark'] as const) {
    test(`AC-9.3 tree views pass axe, ${theme}`, async ({page}) => {
        await page.addInitScript((value) => localStorage.setItem('cucumber.ui.theme', value), theme);
        const backend = await setup(page);
        await page.goto(featuresUrl);
        await expect(rows(page)).toHaveCount(4);
        await expectNoAxeViolations(page);
        await page.goto(`${featuresUrl}?view=archived`);
        await expect(tree(page)).toContainText(legacy.path.split('/').pop() ?? '');
        await expectNoAxeViolations(page);
        await page.goto(`${featuresUrl}?tag=%40smoke`);
        await expect(tree(page)).toContainText('address.feature');
        await expectNoAxeViolations(page);
        backend.features.clear();
        await page.goto(featuresUrl);
        await expect(page.getByText('No features yet')).toBeVisible();
        await expect(page.getByRole('button', {name: 'New feature'})).toBeVisible();
        await expectNoAxeViolations(page);
    });
}

test('AC-9.2 the tree empty state offers no action to a reader', async ({page}) => {
    const backend = await setup(page, 'dan');
    backend.features.clear();
    await page.goto(featuresUrl);
    await expect(page.getByText('No features yet')).toBeVisible();
    await expect(page.getByRole('button', {name: 'New feature'})).toHaveCount(0);
});
