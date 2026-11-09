import {beforeAll, describe, expect, it} from 'vitest';
import {fireEvent, screen, waitFor, within} from '@testing-library/react';
import {http, HttpResponse} from 'msw';
import {server, problemHeaders} from '../../test/msw';
import {renderApp} from '../../test/render';
import {tokenFor} from '../../test/jwt';
import {ids} from '../../test/fixtures/auth';
import {users, type UserKey} from '../../test/fixtures/workspace';
import {featureWorld, scenarioReadHandlers, workspaceHandlers} from '../../test/fixtures/handlers';
import {
    address,
    addressDetail,
    detail,
    legacy,
    legacyDetail,
    payment,
    paymentDetail,
    paymentSourceV2,
    paymentSourceV3,
    scenarioProblem,
} from '../../test/fixtures/scenario';
import {startSession} from '../../auth/session';

const featurePath = `/api/projects/${ids.checkout}/features/${payment.id}`;

// jsdom has no layout: CodeMirror measures text ranges, which jsdom's Range doesn't implement.
beforeAll(() => {
    const empty = () => ({length: 0, item: () => null, [Symbol.iterator]: [][Symbol.iterator]});
    Range.prototype.getClientRects = function getClientRects() {
        return Object.assign(Object.create(DOMRectList.prototype ?? Object.prototype), empty());
    };
    Range.prototype.getBoundingClientRect = function getBoundingClientRect() {
        return new DOMRect(0, 0, 0, 0);
    };
});

function open(user: UserKey, featureId: string = payment.id, saveAnswer?: (body: unknown) => Response) {
    startSession(tokenFor(users[user].id, users[user].email, 3600));
    const world = featureWorld([payment, address, legacy], [paymentDetail, addressDetail, legacyDetail]);
    server.use(...workspaceHandlers(user), ...scenarioReadHandlers(world));
    if (saveAnswer) {
        server.use(
            http.put('/api/projects/:projectId/features/:featureId', async ({request}) => {
                const body = await request.json();
                world.requests.push({key: `PUT ${new URL(request.url).pathname}`, body});
                return saveAnswer(body);
            }),
        );
    }
    const app = renderApp(`/projects/${ids.checkout}/features?feature=${featureId}`);
    return {app, world};
}

async function editorReady(version = 2) {
    await screen.findByText(`Version ${version}`);
}

function puts(world: ReturnType<typeof open>['world']) {
    return world.requests.filter((r) => r.key.startsWith('PUT '));
}

// @US-6 @AC-6.1
describe('AC-6.1 edit and save', () => {
    it('sends baseVersion, source, path and message; shows version 3 and "Saved"', async () => {
        const {world} = open('gus', payment.id, () => HttpResponse.json(detail(payment, paymentSourceV3, {currentVersion: 3})));
        await editorReady();
        fireEvent.change(await screen.findByLabelText('Change message (optional)'), {target: {value: 'Clarify card step'}});
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        await screen.findByText('Version 3');
        expect(await screen.findByText('Saved')).toBeTruthy();
        expect(puts(world)[0]?.body).toEqual({
            path: payment.path,
            source: paymentSourceV2,
            baseVersion: 2,
            message: 'Clarify card step',
        });
    });
});

// @US-6 @AC-6.2
describe('AC-6.2 Gherkin errors show where they are', () => {
    it('marks line 4 column 3, opens the lint panel and announces the summary', async () => {
        const message = "(4:3): expected: #EOF, #TableRow, ... got 'Whenn I pay'";
        open('gus', payment.id, () =>
            HttpResponse.json(scenarioProblem(422, 'GHERKIN_INVALID', featurePath, {parseErrors: [{line: 4, column: 3, message}]}), {
                status: 422,
                headers: problemHeaders,
            }),
        );
        await editorReady();
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        expect(await screen.findByText('1 Gherkin error; first on line 4')).toBeTruthy();
        await waitFor(() => expect(document.querySelector('.cm-panel-lint')?.textContent).toContain(message));
        const marked = document.querySelector('.cm-lintRange-error');
        expect(marked?.textContent).toBe('S');
        expect(marked?.closest('.cm-line')?.textContent).toBe('  Scenario: Pay by card');
        expect(screen.getByText('Version 2')).toBeTruthy();
    });

    it('summarises three errors', async () => {
        open('gus', payment.id, () =>
            HttpResponse.json(
                scenarioProblem(422, 'GHERKIN_INVALID', featurePath, {
                    parseErrors: [
                        {line: 2, column: 1, message: 'a'},
                        {line: 4, column: 3, message: 'b'},
                        {line: 6, column: 5, message: 'c'},
                    ],
                }),
                {status: 422, headers: problemHeaders},
            ),
        );
        await editorReady();
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        expect(await screen.findByText('3 Gherkin errors; first on line 2')).toBeTruthy();
    });
});

// @US-6 @AC-6.4
describe('AC-6.4 someone else saved first', () => {
    it('offers a reload, keeps the user text in a read-only pane, and never resends automatically', async () => {
        const {world} = open('gus', payment.id, () =>
            HttpResponse.json(scenarioProblem(409, 'VERSION_CONFLICT', featurePath, {currentVersion: 3}), {status: 409, headers: problemHeaders}),
        );
        await editorReady();
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        const dialog = await screen.findByRole('alertdialog');
        expect(dialog.textContent).toContain('This feature changed (now version 3) since you opened it');
        expect(within(dialog).getByRole('button', {name: 'Keep editing'})).toBeTruthy();
        await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

        world.details.set(payment.id, detail(payment, paymentSourceV3, {currentVersion: 3}));
        fireEvent.click(within(dialog).getByRole('button', {name: 'Reload latest version'}));
        await screen.findByText('Version 3');
        const pane = screen.getByRole('region', {name: 'Your unsaved text'});
        expect(pane.textContent).toContain('Then the order is confirmed');
        await new Promise((resolve) => setTimeout(resolve, 50));
        expect(puts(world).map((p) => p.body)).toEqual([expect.objectContaining({baseVersion: 2})]);
        fireEvent.click(within(pane).getByRole('button', {name: 'Dismiss'}));
        expect(screen.queryByRole('region', {name: 'Your unsaved text'})).toBeNull();
    });
});

// @US-6 @AC-6.5
describe('AC-6.5 path conflicts', () => {
    it('shows 409 FEATURE_PATH_TAKEN at the path field', async () => {
        open('gus', payment.id, () =>
            HttpResponse.json(scenarioProblem(409, 'FEATURE_PATH_TAKEN', featurePath), {status: 409, headers: problemHeaders}),
        );
        await editorReady();
        fireEvent.change(await screen.findByLabelText('Path'), {target: {value: address.path}});
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        const path = screen.getByLabelText('Path');
        await waitFor(() => expect(path.getAttribute('aria-invalid')).toBe('true'));
        const describedBy = (path.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent ?? '');
        expect(describedBy.join(' ')).toContain('An active feature already uses this path');
    });
});

// @US-6 @AC-6.6
describe('AC-6.6 read-only editor', () => {
    it.each([
        ['hal', payment.id, 'Read only: your role is TESTER'],
        ['dan', payment.id, 'Read only: your role is READER'],
        ['gus', legacy.id, 'Feature archived: read only'],
    ] as const)('%s on %s: %s', async (user, featureId, banner) => {
        open(user, featureId);
        expect(await screen.findByText(banner)).toBeTruthy();
        expect(screen.queryByRole('button', {name: 'Save'})).toBeNull();
        expect(document.querySelector('.cm-content')?.getAttribute('aria-readonly')).toBe('true');
    });

    it('becomes read-only after a save answers 409 FEATURE_ARCHIVED', async () => {
        const {world} = open('gus', payment.id, () =>
            HttpResponse.json(scenarioProblem(409, 'FEATURE_ARCHIVED', featurePath), {status: 409, headers: problemHeaders}),
        );
        await editorReady();
        world.details.set(payment.id, detail(payment, paymentSourceV2, {archivedAt: '2026-11-09T10:00:00Z'}));
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        expect(await screen.findByText('Feature archived: read only')).toBeTruthy();
        await waitFor(() => expect(screen.queryByRole('button', {name: 'Save'})).toBeNull());
    });
});

// @US-6 @AC-6.7
describe('AC-6.7 saving without changes', () => {
    it('says "No changes to save" and stays at version 2', async () => {
        open('gus', payment.id, () => HttpResponse.json(paymentDetail));
        await editorReady();
        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));
        expect(await screen.findByText('No changes to save')).toBeTruthy();
        expect(screen.getByText('Version 2')).toBeTruthy();
    });
});
