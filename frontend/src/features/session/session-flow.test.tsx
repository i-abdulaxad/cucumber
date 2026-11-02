import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server, problemHeaders } from '../../test/msw';
import { renderApp } from '../../test/render';
import { tokenFor } from '../../test/jwt';
import { acme, anaCredentials, authProblem, fay, gatewayProblem, ids } from '../../test/fixtures/auth';
import { SESSION_STORAGE_KEY, getToken, startSession } from '../../auth/session';
import { authApi } from '../../api/client';

const anaToken = () => tokenFor(ids.ana, anaCredentials.email, 3600);

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

async function logIn(email = anaCredentials.email, password = anaCredentials.password) {
  fill('Email', email);
  fill('Password', password);
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }));
}

function loginAnswers(token: string) {
  let calls = 0;
  server.use(
    http.post('/api/auth/login', async ({ request }) => {
      calls += 1;
      const body = await request.json();
      if (typeof body === 'object' && body && 'password' in body && body.password === anaCredentials.password) {
        return HttpResponse.json({ accessToken: token });
      }
      return HttpResponse.json(authProblem(401, 'INVALID_CREDENTIALS', '/api/auth/login'), { status: 401, headers: problemHeaders });
    }),
  );
  return () => calls;
}

function orgsAnswer(orgs: Array<typeof acme>) {
  server.use(http.get('/api/orgs', () => HttpResponse.json(orgs)));
}

const consoleSpies: Array<ReturnType<typeof vi.spyOn>> = [];
beforeEach(() => {
  for (const method of ['log', 'info', 'warn', 'error', 'debug'] as const) {
    consoleSpies.push(vi.spyOn(console, method));
  }
});
afterEach(() => {
  consoleSpies.length = 0;
});

/** AC-9.6: no console message, URL, localStorage entry or cookie contains a secret. */
function expectNoLeak(secrets: string[], href: string) {
  for (const secret of secrets) {
    expect(href).not.toContain(secret);
    expect(document.cookie).not.toContain(secret);
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i) ?? '';
      expect(`${key}=${localStorage.getItem(key) ?? ''}`).not.toContain(secret);
    }
    for (const spy of consoleSpies) {
      for (const call of spy.mock.calls) expect(JSON.stringify(call)).not.toContain(secret);
    }
  }
}

// @US-1 @AC-1.1
describe('AC-1.1 log in and land where you were going', () => {
  it('redirects to login with next, then lands on the target; the token is only in sessionStorage', async () => {
    const token = anaToken();
    loginAnswers(token);
    const app = renderApp(`/orgs/${ids.acme}/projects`);
    await waitFor(() => expect(app.href()).toBe(`/login?next=%2Forgs%2F${ids.acme}%2Fprojects`));
    await logIn();
    await waitFor(() => expect(app.href()).toBe(`/orgs/${ids.acme}/projects`));
    expect(await screen.findByRole('heading', { level: 1, name: 'Projects' })).toBeTruthy();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(token);
    expect(localStorage.length).toBe(0);
    expectNoLeak([token, anaCredentials.password], app.href());
  });
});

// @US-1 @AC-1.2
describe('AC-1.2 wrong credentials', () => {
  it('shows the message, empties the password and focuses the error summary', async () => {
    loginAnswers(anaToken());
    renderApp('/login');
    await screen.findByRole('heading', { level: 1, name: 'Log in' });
    await logIn(anaCredentials.email, 'wrong');
    const summary = await screen.findByRole('alert');
    expect(summary.textContent).toContain('Email or password is incorrect');
    await waitFor(() => expect(document.activeElement).toBe(summary));
    expect(screen.getByLabelText<HTMLInputElement>('Password').value).toBe('');
    expect(screen.getByLabelText<HTMLInputElement>('Email').value).toBe(anaCredentials.email);
    expect(getToken()).toBeNull();
    expectNoLeak(['wrong'], window.location.href);
  });

  it('requires both fields before calling the API (D8)', async () => {
    const calls = loginAnswers(anaToken());
    renderApp('/login');
    await screen.findByRole('heading', { level: 1, name: 'Log in' });
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));
    const summary = await screen.findByRole('alert');
    expect(summary.textContent).toContain('Enter your email');
    expect(summary.textContent).toContain('Enter your password');
    expect(calls()).toBe(0);
  });
});

// @US-1 @AC-1.3
describe('AC-1.3 register, then log in automatically', () => {
  function registerAnswers(answer: () => Response) {
    server.use(http.post('/api/auth/register', answer));
  }

  async function register() {
    await screen.findByRole('heading', { level: 1, name: 'Create an account' });
    fill('Full name', 'Fay Lin');
    fill('Email', 'fay@example.com');
    fill('Password', 'Secret123!');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
  }

  it('registers, logs in with the same credentials, and shows the empty organizations page', async () => {
    const token = tokenFor(ids.fay, 'fay@example.com', 3600);
    let loginBody: unknown = null;
    registerAnswers(() => HttpResponse.json(fay, { status: 201 }));
    server.use(
      http.post('/api/auth/login', async ({ request }) => {
        loginBody = await request.json();
        return HttpResponse.json({ accessToken: token });
      }),
    );
    orgsAnswer([]);
    const app = renderApp('/register');
    await register();
    await waitFor(() => expect(app.href()).toBe('/orgs'));
    expect(loginBody).toEqual({ email: 'fay@example.com', password: 'Secret123!' });
    expect(await screen.findByText("You're not in an organization yet")).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Create organization' })).toBeTruthy();
    expectNoLeak([token, 'Secret123!'], app.href());
  });

  it('400 VALIDATION_FAILED: the message is under the password field, linked by aria-describedby', async () => {
    registerAnswers(() =>
      HttpResponse.json(
        authProblem(400, 'VALIDATION_FAILED', '/api/auth/register', { errors: [{ field: 'password', message: 'Password is too common' }] }),
        { status: 400, headers: problemHeaders },
      ),
    );
    renderApp('/register');
    await register();
    const password = screen.getByLabelText('Password');
    await waitFor(() => expect(password.getAttribute('aria-invalid')).toBe('true'));
    const describedBy = (password.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean);
    const texts = describedBy.map((id) => document.getElementById(id)?.textContent ?? '');
    expect(texts.join(' ')).toContain('Password is too common');
    expect(screen.getByLabelText<HTMLInputElement>('Full name').value).toBe('Fay Lin');
  });

  it('409 EMAIL_ALREADY_REGISTERED: says so and offers "Log in"', async () => {
    registerAnswers(() =>
      HttpResponse.json(authProblem(409, 'EMAIL_ALREADY_REGISTERED', '/api/auth/register'), { status: 409, headers: problemHeaders }),
    );
    renderApp('/register');
    await register();
    const summary = await screen.findByRole('alert');
    expect(summary.textContent).toContain('An account with this email exists');
    expect(screen.getAllByRole('link', { name: 'Log in' }).length).toBeGreaterThan(0);
  });
});

// @US-1 @AC-1.4
describe('AC-1.4 a 401 anywhere ends the session and keeps the location', () => {
  it('clears the session, goes to login with next, and returns there after logging in', async () => {
    const first = anaToken();
    startSession(first);
    const app = renderApp(`/projects/${ids.checkout}/features?tag=%40smoke`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Features' })).toBeTruthy();

    server.use(http.get('/api/orgs', () => HttpResponse.json(gatewayProblem(401, '/api/orgs'), { status: 401, headers: problemHeaders })));
    await act(async () => {
      await authApi.GET('/api/orgs');
    });

    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    await waitFor(() =>
      expect(app.href()).toBe(`/login?next=%2Fprojects%2F${ids.checkout}%2Ffeatures%3Ftag%3D%2540smoke`),
    );

    loginAnswers(anaToken());
    await logIn();
    await waitFor(() => expect(app.href()).toBe(`/projects/${ids.checkout}/features?tag=%40smoke`));
    expect(await screen.findByText('Tag filter: @smoke')).toBeTruthy();
  });
});

// @US-1 @AC-1.5 (app level): a valid mirrored token logs in without calling login.
describe('AC-1.5 session on start (app level)', () => {
  it('a restored session opens a protected page without a login request', async () => {
    const calls = loginAnswers(anaToken());
    orgsAnswer([acme]);
    startSession(anaToken());
    const app = renderApp('/orgs');
    expect(await screen.findByRole('link', { name: 'Acme' })).toBeTruthy();
    expect(app.href()).toBe('/orgs');
    expect(calls()).toBe(0);
  });

  it('without a session the login page shows', async () => {
    const app = renderApp('/orgs');
    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeTruthy();
    expect(app.href()).toBe('/login?next=%2Forgs');
  });
});

// @US-1 @AC-1.6
describe('AC-1.6 expiry and logout', () => {
  it('when the token expires, the login page says so', async () => {
    orgsAnswer([acme]);
    startSession(tokenFor(ids.ana, anaCredentials.email, 2));
    const app = renderApp('/orgs');
    expect(await screen.findByRole('link', { name: 'Acme' })).toBeTruthy();
    expect(await screen.findByText('Your session expired. Log in again.', {}, { timeout: 4000 })).toBeTruthy();
    expect(app.href().startsWith('/login')).toBe(true);
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  }, 8000);

  it('log out empties memory, the mirror and the query cache; Back shows the login page', async () => {
    orgsAnswer([acme]);
    startSession(anaToken());
    const app = renderApp('/orgs');
    expect(await screen.findByRole('link', { name: 'Acme' })).toBeTruthy();
    expect(app.queryClient.getQueryCache().getAll().length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
    await waitFor(() => expect(app.href()).toBe('/login'));
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(app.queryClient.getQueryCache().getAll().length).toBe(0);

    act(() => app.history.back());
    await waitFor(() => expect(app.router.state.location.pathname).toBe('/login'));
    expect(screen.queryByText('Acme')).toBeNull();
    expect(screen.getByRole('heading', { level: 1, name: 'Log in' })).toBeTruthy();
  });
});

// @US-1 @AC-1.7
describe('AC-1.7 the next parameter cannot leave the app', () => {
  it.each([
    ['%2Forgs', '/orgs'],
    ['%2F%2Fevil.example', '/'],
    ['https%3A%2F%2Fevil.example', '/'],
    ['javascript%3Aalert(1)', '/'],
    ['%2F%5Cevil.example', '/'],
  ])('next=%s lands on %s', async (next, landing) => {
    loginAnswers(anaToken());
    orgsAnswer([]);
    const app = renderApp(`/login?next=${next}`);
    await screen.findByRole('heading', { level: 1, name: 'Log in' });
    await logIn();
    await waitFor(() => expect(app.router.state.location.pathname).toBe(landing));
    expect(app.href()).toBe(landing);
  });
});

// Shell parts of AC-9.2 and D17.
describe('shell', () => {
  it('has a skip link to the main content', async () => {
    renderApp('/login');
    await screen.findByRole('heading', { level: 1, name: 'Log in' });
    const skip = screen.getByRole('link', { name: 'Skip to main content' });
    expect(skip.getAttribute('href')).toBe('#main');
    expect(document.getElementById('main')?.tagName).toBe('MAIN');
  });

  it('moves focus to the h1 after navigation', async () => {
    renderApp('/login');
    await screen.findByRole('heading', { level: 1, name: 'Log in' });
    fireEvent.click(screen.getByRole('link', { name: 'Create an account' }));
    const h1 = await screen.findByRole('heading', { level: 1, name: 'Create an account' });
    await waitFor(() => expect(document.activeElement).toBe(h1));
  });

  it('shows the signed-in email in the header', async () => {
    orgsAnswer([acme]);
    startSession(anaToken());
    renderApp('/orgs');
    expect(await screen.findByText(anaCredentials.email)).toBeTruthy();
  });

  it('a signed-in user who opens /login goes to next', async () => {
    orgsAnswer([acme]);
    startSession(anaToken());
    const app = renderApp('/login?next=%2Forgs');
    await waitFor(() => expect(app.href()).toBe('/orgs'));
  });
});
