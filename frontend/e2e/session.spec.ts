import {expect, test, type Page, type Route} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {acme, anaCredentials, authProblem, fay, gatewayProblem, ids} from '../src/test/fixtures/auth';

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function base64url(text: string): string {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function token(sub: string, email: string, secondsLeft: number): string {
    const now = Math.floor(Date.now() / 1000);
    return `${base64url(JSON.stringify({alg: 'HS512', typ: 'JWT'}))}.${base64url(JSON.stringify({
        sub,
        email,
        iat: now,
        exp: now + secondsLeft
    }))}.c2ln`;
}

const problemJson = 'application/problem+json';

async function json(route: Route, status: number, body: unknown, contentType = 'application/json') {
    await route.fulfill({status, contentType, body: JSON.stringify(body)});
}

/** Every /api call must be answered by a test fixture; anything else fails the test. */
async function apiFixtures(page: Page, handlers: Record<string, (route: Route) => Promise<void>>) {
    const unexpected: string[] = [];
    await page.route('**/api/**', async (route) => {
        const request = route.request();
        const key = `${request.method()} ${new URL(request.url()).pathname}`;
        const handler = handlers[key];
        if (handler) await handler(route);
        else {
            unexpected.push(key);
            await route.abort();
        }
    });
    return unexpected;
}

async function expectNoAxeViolations(page: Page) {
    const results = await new AxeBuilder({page}).withTags(WCAG).analyze();
    expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

/** AC-9.6: no console message, URL, localStorage entry or cookie contains a secret. */
async function expectNoLeak(page: Page, consoleMessages: string[], secrets: string[]) {
    const local = await page.evaluate(() => JSON.stringify({...localStorage}));
    const cookies = JSON.stringify(await page.context().cookies());
    for (const secret of secrets) {
        expect(page.url()).not.toContain(secret);
        expect(local).not.toContain(secret);
        expect(cookies).not.toContain(secret);
        for (const message of consoleMessages) expect(message).not.toContain(secret);
    }
}

function captureConsole(page: Page): string[] {
    const messages: string[] = [];
    page.on('console', (message) => messages.push(message.text()));
    return messages;
}

test('@AC-1.1 @AC-9.6 log in and land where you were going; the token stays in sessionStorage', async ({page}) => {
    const consoleMessages = captureConsole(page);
    const jwt = token(ids.ana, anaCredentials.email, 3600);
    const unexpected = await apiFixtures(page, {
        'POST /api/auth/login': (route) => json(route, 200, {accessToken: jwt}),
        // Fixture only (F11, user 2026-11-09): the app shell loads the org, its projects and its members.
        'GET /api/orgs': (route) => json(route, 200, [acme]),
        [`GET /api/orgs/${ids.acme}/projects`]: (route) => json(route, 200, []),
        [`GET /api/orgs/${ids.acme}/members`]: (route) => json(route, 200, []),
    });
    await page.goto(`/orgs/${ids.acme}/projects`);
    await expect(page).toHaveURL(`/login?next=%2Forgs%2F${ids.acme}%2Fprojects`);
    await expect(page.getByRole('heading', {level: 1, name: 'Log in'})).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByLabel('Email').fill(anaCredentials.email);
    await page.getByLabel('Password').fill(anaCredentials.password);
    await page.getByRole('button', {name: 'Log in'}).click();

    await expect(page).toHaveURL(`/orgs/${ids.acme}/projects`);
    await expect(page.getByRole('heading', {level: 1, name: 'Projects'})).toBeFocused();
    expect(await page.evaluate(() => sessionStorage.getItem('cucumber.session'))).toBe(jwt);
    expect(await page.evaluate(() => localStorage.length)).toBe(0);
    await expectNoLeak(page, consoleMessages, [jwt, anaCredentials.password]);
    expect(unexpected).toEqual([]);
});

test('@AC-1.2 wrong credentials: message, empty password, focus on the summary', async ({page}) => {
    await apiFixtures(page, {
        'POST /api/auth/login': (route) => json(route, 401, authProblem(401, 'INVALID_CREDENTIALS', '/api/auth/login'), problemJson),
    });
    await page.goto('/login');
    await page.getByLabel('Email').fill(anaCredentials.email);
    await page.getByLabel('Password').fill('wrong');
    await page.getByLabel('Password').press('Enter');
    const summary = page.getByRole('alert');
    await expect(summary).toContainText('Email or password is incorrect');
    await expect(summary).toBeFocused();
    await expect(page.getByLabel('Password')).toHaveValue('');
    await expectNoAxeViolations(page);
});

test('@AC-1.3 register, log in automatically, see the empty organizations page', async ({page}) => {
    const jwt = token(ids.fay, fay.email, 3600);
    const unexpected = await apiFixtures(page, {
        'POST /api/auth/register': (route) => json(route, 201, fay),
        'POST /api/auth/login': (route) => json(route, 200, {accessToken: jwt}),
        'GET /api/orgs': (route) => json(route, 200, []),
    });
    await page.goto('/register');
    await expectNoAxeViolations(page);
    await page.getByLabel('Full name').fill('Fay Lin');
    await page.getByLabel('Email').fill(fay.email);
    await page.getByLabel('Password').fill('Secret123!');
    await page.getByRole('button', {name: 'Create account'}).click();
    await expect(page).toHaveURL('/orgs');
    await expect(page.getByText("You're not in an organization yet")).toBeVisible();
    await expect(page.getByRole('link', {name: 'Create organization'})).toBeVisible();
    await expectNoAxeViolations(page);
    expect(unexpected).toEqual([]);
});

test('@AC-1.3 a field error is shown at its field and the summary passes axe', async ({page}) => {
    await apiFixtures(page, {
        'POST /api/auth/register': (route) =>
            json(
                route,
                400,
                authProblem(400, 'VALIDATION_FAILED', '/api/auth/register', {
                    errors: [{
                        field: 'password',
                        message: 'Password is too common'
                    }]
                }),
                problemJson,
            ),
    });
    await page.goto('/register');
    await page.getByLabel('Full name').fill('Fay Lin');
    await page.getByLabel('Email').fill(fay.email);
    await page.getByLabel('Password').fill('Secret123!');
    await page.getByRole('button', {name: 'Create account'}).click();
    await expect(page.getByLabel('Password')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('Password')).toHaveAccessibleDescription(/Password is too common/);
    await expectNoAxeViolations(page);
});

test('@AC-1.4 a 401 ends the session and returns to the same page after logging in', async ({page}) => {
    const first = token(ids.ana, anaCredentials.email, 3600);
    const second = token(ids.ana, anaCredentials.email, 3600);
    let orgCalls = 0;
    await apiFixtures(page, {
        'GET /api/orgs': (route) => {
            orgCalls += 1;
            return orgCalls === 1 ? json(route, 401, gatewayProblem(401, '/api/orgs'), problemJson) : json(route, 200, [acme]);
        },
        'POST /api/auth/login': (route) => json(route, 200, {accessToken: second}),
    });
    await page.addInitScript((jwt) => {
        if (!sessionStorage.getItem('cucumber.seeded')) {
            sessionStorage.setItem('cucumber.session', jwt);
            sessionStorage.setItem('cucumber.seeded', '1');
        }
    }, first);
    await page.goto('/orgs');
    await expect(page).toHaveURL('/login?next=%2Forgs');
    expect(await page.evaluate(() => sessionStorage.getItem('cucumber.session'))).toBeNull();
    await expect(page.getByText('Your session ended. Log in again.')).toBeVisible();
    await page.getByLabel('Email').fill(anaCredentials.email);
    await page.getByLabel('Password').fill(anaCredentials.password);
    await page.getByRole('button', {name: 'Log in'}).click();
    await expect(page).toHaveURL('/orgs');
    await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
});

test('@AC-1.6 log out clears the session, and Back shows the login page', async ({page}) => {
    const jwt = token(ids.ana, anaCredentials.email, 3600);
    await apiFixtures(page, {
        'POST /api/auth/login': (route) => json(route, 200, {accessToken: jwt}),
        'GET /api/orgs': (route) => json(route, 200, [acme]),
    });
    await page.goto('/login?next=%2Forgs');
    await page.getByLabel('Email').fill(anaCredentials.email);
    await page.getByLabel('Password').fill(anaCredentials.password);
    await page.getByRole('button', {name: 'Log in'}).click();
    await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('button', {name: /Account/}).click();
    await page.getByRole('menuitem', {name: 'Log out'}).click();
    await expect(page).toHaveURL('/login');
    expect(await page.evaluate(() => sessionStorage.getItem('cucumber.session'))).toBeNull();
    await page.goBack();
    await expect(page.getByRole('heading', {level: 1, name: 'Log in'})).toBeVisible();
    await expect(page.getByText('Acme')).toHaveCount(0);
});

test('@AC-1.7 next cannot leave the app', async ({page}) => {
    const jwt = token(ids.ana, anaCredentials.email, 3600);
    await apiFixtures(page, {
        'POST /api/auth/login': (route) => json(route, 200, {accessToken: jwt}),
        'GET /api/orgs': (route) => json(route, 200, []),
    });
    await page.goto('/login?next=https%3A%2F%2Fevil.example');
    await page.getByLabel('Email').fill(anaCredentials.email);
    await page.getByLabel('Password').fill(anaCredentials.password);
    await page.getByRole('button', {name: 'Log in'}).click();
    await expect(page).toHaveURL('/');
    expect(new URL(page.url()).host).toBe('127.0.0.1:4173');
});

test('@AC-9.1 @AC-9.2 a 503 shows the unavailable state with Retry and the reference', async ({page}) => {
    const jwt = token(ids.ana, anaCredentials.email, 3600);
    let calls = 0;
    await apiFixtures(page, {
        'GET /api/orgs': (route) => {
            calls += 1;
            return calls <= 2 ? json(route, 503, gatewayProblem(503, '/api/orgs', 'rid-503'), problemJson) : json(route, 200, [acme]);
        },
    });
    await page.addInitScript((value) => sessionStorage.setItem('cucumber.session', value), jwt);
    await page.goto('/orgs');
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('The service is unavailable. Try again.');
    await expect(alert).toContainText('Reference: rid-503');
    await expectNoAxeViolations(page);
    await page.getByRole('button', {name: 'Retry'}).click();
    await expect(page.getByRole('link', {name: 'Acme'})).toBeVisible();
});

test('@AC-9.4 (shell) the skip link is the first stop and moves focus to main', async ({page}) => {
    await apiFixtures(page, {});
    await page.goto('/login');
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', {name: 'Skip to main content'});
    await expect(skip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('main')).toBeFocused();
});
