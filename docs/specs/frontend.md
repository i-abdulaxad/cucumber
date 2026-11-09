# frontend phase 1: web UI

Status: **Ready** (the user approved the spec, stack option A, and Q1–Q4 as recommended, including SE1, on 2026-11-02).
**Batch B is paused** (user, 2026-11-03) until F11, the design foundation in batch DS, passes review point DS. QD1–QD3
were answered on 2026-11-05: **F11 is ready to start.**
Last updated: 2026-11-03 (visual design DS-1 to DS-12, US-10, batch DS with F11, FE1; batches B–E use the design
system).

## Questions for batch DS (answered by the user, recorded 2026-11-05)

- **User answers 2026-11-05:**
    - **QD1:** a local `cx()` helper, with no `clsx` or `tailwind-merge`; components offer fixed variants only.
    - **QD2:** system fonts; no font package.
    - **QD3:** `lucide-react` is approved, pinned to the version frontend-swe verifies.
    - **`@types/node`:** the earlier rejection was only a pause; it's installed as F11's separate first step, as
      written.
- **QD1. Class helpers.** Recommended: neither `clsx` nor `tailwind-merge`. Use a local 5-line `cx()` that joins truthy
  class strings. Components expose variants, not free `className` overrides, so no class merging is needed. Why: two
  fewer packages, and no route for ad-hoc styling. If the user prefers overrides, approve `tailwind-merge` 3.x and
  `clsx` 2.x instead.
- **QD2. Font.** Recommended: no font package. Use the system stacks in DS-3. Why: zero download, native rendering, no
  licence or hosting question. The alternative is `@fontsource-variable/inter`, about 50 KB.
- **QD3. Icons** (needed for the sidebar, status chips and empty states). Recommended: approve `lucide-react` (ISC
  licence, tree-shaken, one consistent 24 px stroke set). Why: hand-drawn SVGs are slow to make and look inconsistent.
  The alternative is about 30 hand-authored SVG components in `src/ui/icons/`.
- **Already decided by the user (2026-11-03):** `tailwindcss` 4 and `@tailwindcss/vite` 4, with exact versions verified
  by frontend-swe.

## Questions (answered by the user 2026-11-02: all as recommended)

- **User answers 2026-11-02:**
    - **Q1:** Node 24 LTS, npm, and a committed `package-lock.json`, pinned in `.nvmrc` and `engines`. This machine has
      only Node 22.23.2 (nvm); the user will install Node 24.
    - **Q2:** the six peer packages are approved. **User 2026-11-02 (F1 stop):** `@testing-library/dom` 10.4.2 is
      approved as a seventh peer (required by @testing-library/react 16.3). **User 2026-11-02 (review point A):**
      `@types/node`, pinned to the Node 24 types, is approved as a directly declared devDependency.
    - **Q3:** SE1 is approved.
    - **Q4:** the report screens go in batch D of phase 1.
- **Q1. Runtime and package manager.** Recommended: Node 24 LTS, pinned in `frontend/.nvmrc` and `engines`, with npm and
  a committed `package-lock.json`. Why: no extra tool to install, and the lockfile makes builds reproducible.
- **Q2. Direct peer packages.** Approve these as part of the stack, at the versions npm resolves (recorded in F1's
  handoff): `react-dom`, `@types/react`, `@types/react-dom`, `@codemirror/state`, `@codemirror/language`,
  `@codemirror/commands`, and (added 2026-11-02) `@testing-library/dom` 10.4.2. Why: the approved libraries can't be
  used without them. Any other package is a stop condition.
- **Q3. SE1 (scenario-service, docs and one test).** Recommended: approve. backend-swe pins down the unit of
  `parseErrors[].column` (expected: Unicode code points) with one new scenario-service test, and documents it in
  contract 1.0.7. Why: without it, error markers can land in the wrong place on lines with non-ASCII text (G2).
- **Q4. Report screens.** Recommended: batch D of this phase (D1). Why: report-service is accepted (contract 1.0.2) and
  gateway 0.3.4 routes it, so nothing blocks them.

## Problem and goal

Every capability exists only as a REST API today. Testers, leads and admins need a browser UI to:

- sign in;
- manage organizations, members and projects;
- browse and edit Gherkin features, with server-side validation shown in the editor;
- see test runs and record manual results.

**Success:**

- Ana registers, creates the org Acme and the project Checkout, and invites Gus, who accepts.
- Gus creates `features/checkout/payment.feature`, saves an invalid edit and sees the marker on the right line and
  column, fixes it, and saves version 2.
- Hal records manual results for a run Gus planned, and Dan reads the run summary.
- Every screen works with the keyboard alone and passes the axe WCAG 2.1 AA checks.

## Scope

**In (phase 1):**

- Screens 1–5: session; organizations; projects; the feature tree; the feature editor with history and test cases.
- Screen 6 in batch D: runs, the dashboard, results, manual runs and upload tokens.
- The typed client, Problem handling, accessibility, unit, component and end-to-end tests, and a README.

**Out:**

- Production hosting, a CSP and a CDN (FU-F5). In development the Vite proxy is used.
- Refresh tokens and server-side logout (G6).
- Push updates (G4).
- A run-list filter and trend API (G5).
- A current-user endpoint (G3).
- Diffs between versions; attachments; MFA.
- Internationalisation: English only, but all text lives in one module per feature.
- Tailwind: approved by the user on 2026-11-03 (DS-1 to DS-12); no longer out of scope.

## Glossary (new terms only)

| Term           | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Session        | The JWT `accessToken` held in memory and mirrored to `sessionStorage` under the key `cucumber.session`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| Problem union  | The typed union of every RFC 9457 Problem the contracts declare, discriminated by `status` and `code`. A gateway Problem has no `code`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| Effective role | `myRole` from auth-service: the organization role from `getOrganization`/`listMyOrganizations`, and the project role from `getProject`/`listProjects`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| View state     | One of `loading`, `empty`, `error`, `forbidden`, `not-found` or `ready`, shown by every data view.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| UI fixture     | MSW handlers plus data typed with `satisfies` against the generated schema types (the Acme fixture below).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Acme fixture   | **Org** Acme, `seatLimit` 5. Members: Ana `OWNER`, Ben `ADMIN`, Gus `MEMBER`, Hal `MEMBER`, Dan `READER`. Ivy has an account but no organization.<br>**Project** Checkout: Ana effective `ADMIN` (implicit); explicit members Gus `MEMBER`, Hal `TESTER`, Dan `READER`.<br>**Project** Billing: archived.<br>**Features in Checkout:** `features/checkout/payment.feature` (Payment, v2, tags `@checkout`); `features/checkout/address.feature` (Address, v1, `@smoke`); `features/legacy.feature` (archived).<br>**Run** "Nightly" (AUTOMATED, COMPLETED: 2 passed, 1 failed, 1 pending) and **run** "Sprint 12" (MANUAL, IN_PROGRESS, 4 results). |

## Decisions (recommended defaults)

**D1. Phases and order.** Phase 1 runs in batches A, DS (design foundation, inserted by the user 2026-11-03), then B to
E. Screen 6 is batch D, because nothing blocks it any more (Q4). **Why:** risky foundations first, then the editor, then
the CRUD screens.

**D2. Stack (user decision 2026-11-02, option A).** Exactly these packages; Q2's peers; nothing else without approval.

- **User decisions 2026-11-02 (F1 stop: the planned versions didn't resolve together):**
    - TypeScript 5.9.3 replaces 6.0.3, because openapi-typescript 7.13's peer is `typescript ^5.x`;
    - ESLint 9.39.5 replaces 10.14, because eslint-plugin-jsx-a11y 6.10.2 supports at most ESLint 9;
    - no `overrides` for either;
    - `npx playwright install chromium` is allowed only if the cached revision doesn't match @playwright/test 1.64.1.
- **Core:** React 19.3; Vite 8.3 with @vitejs/plugin-react 6.1; TypeScript pinned at 5.9.3.
- **UI:** react-aria-components 1.22.
- **Editor:** @codemirror/view 6.43, @codemirror/legacy-modes 6.5 (Gherkin), @codemirror/lint 6.9.
- **Data and routing:** @tanstack/react-query 5.105, @tanstack/react-router 1.170.
- **Client:** openapi-typescript 7.13, openapi-fetch 0.17.
- **Charts:** echarts 6.1.
- **Unit and component tests:** vitest 5, @testing-library/react 16.3, jsdom 30, msw 3.
- **End to end:** @playwright/test 1.64, @axe-core/playwright 4.14.
- **Lint and types:** eslint 9.39.5, typescript-eslint 8.72, eslint-plugin-jsx-a11y 6.10, `tsc --noEmit` strict;
  `@types/node` pinned to the Node 24 types (user 2026-11-02).
- **Styling (user 2026-11-03, replacing CSS modules):**
    - Tailwind CSS 4 (`tailwindcss`, `@tailwindcss/vite`) with the DS design tokens, styling react-aria-components
      through their `data-*` state attributes (Tailwind 4 variants such as `data-pressed:`, with no plugin);
    - the helpers and icons per QD1 and QD3;
    - no font package unless QD2 says otherwise.
- **No jwt-decode:** `sub`, `email` and `exp` are decoded by hand.

**D3. Location and dev proxy (user decision).** The app lives in `frontend/` at the repo root. The Vite dev server
proxies `/api` to `http://localhost:8080` (the gateway), so there is no gateway CORS change. The app calls only relative
`/api/...` URLs. **Why:** same-origin in development.

**D4. Session (user decision, with details).**

- **Storage:**
    - the token lives in memory and is mirrored to `sessionStorage` (per tab);
    - never in `localStorage`, a cookie, a URL or the console.
- **On start:**
    - read the mirror and decode `exp`;
    - if `exp` is less than or equal to now, discard it.
- **Expiry:** when `exp` passes, the app logs out with the message "Your session expired. Log in again."
- **401 handling:**
    - any 401 from a call other than login clears the session and goes to `/login?next=<path+search>`;
    - `next` is accepted only if it starts with `/` and not with `//`; otherwise `/`.
- **Logout:** clears memory, the mirror and the whole query cache.
- **Why:** the user's decision, plus closing the open-redirect and stale-data holes.

**D5. Permissions come from `myRole`, never from the token's `orgs` claim (user decision).**

- One module, `src/auth/roles.ts`, holds the rank functions:
    - organization: `OWNER` 4 > `ADMIN` 3 > `MEMBER` 2 > `READER` 1;
    - project: `ADMIN` 4 > `MEMBER` 3 > `TESTER` 2 > `READER` 1.
- Actions the role doesn't allow are not rendered. Read-only views say why: "Read only: your role is READER" or "Project
  archived".
- The server stays the authority. A 403 shows the forbidden state and refetches the role.
- **Why:** the contracts compute roles from the database on every request.

**D6. Typed client.**

- `npm run gen:api` turns `docs/api/{auth-service,scenario-service,report-service,api-gateway}.openapi.yaml` into
  `src/api/generated/*.ts`. The output is committed.
- `npm run gen:api:check` fails when regenerating gives a diff, and it is part of `npm test`.
- There is one `openapi-fetch` client per service, with one shared middleware that:
    - adds `Authorization: Bearer`;
    - parses `application/problem+json` into the Problem union;
    - turns a 401 into D4's 401 handling.
- **Why:** the contracts stay the single source of truth, and drift fails the build.

**D7. Problem handling (one mapper, `src/api/problem.ts`).** The map from response to UI is US-9's AC-9.1. Rules:

- `errors[]` entries map to form fields by `field` or `pointer`; an unmatched entry is shown as a form-level message.
- A Problem without `code` (from the gateway) is handled by its status.
- A body that isn't a Problem, or a network failure, is the generic error.
- `requestId` is shown, when present, as "Reference: <id>".
- `detail` is shown only for codes the UI doesn't have its own text for.
- **Why:** one place for every error; contracts change in one spot.

**D8. Forms: the server validates.**

- The client checks only that required fields are filled and that a length isn't over the contract's limit.
- Every other rule (password policy, names, path syntax) comes back as 400 `VALIDATION_FAILED` and is shown at its
  field.
- **Why:** duplicated rules drift.

**D9. Data freshness.**

- react-query defaults: `staleTime` 30 s and refetch on window focus.
- Queries retry once, and only for a network failure, 502, 503 or 504. Mutations never retry.
- After a mutation, the affected queries are invalidated.
- Polling (G4): the run list, and the detail of a run that is PLANNED or IN_PROGRESS, refetch every 30 s while the tab
  is visible.
- **Why:** no push channel exists, and polling stays cheap and bounded.

**D10. Feature tree.**

- **Building it:**
    - built on the client from `listFeatures` `path` values, split on `/`;
    - folders first, then files, each ordered by lower-cased code points, like the API;
    - rendered with react-aria-components `Tree` and `Virtualizer`.
- **Views:** "Active" (default) and "Archived" (`archived=true`).
- **Tag filter:**
    - chips come from `listTags`;
    - one tag at a time, because the API accepts one;
    - selecting a chip refetches with `tag=`.
- **State in the URL:** the selected feature, the view and the tag live in the URL search, so a view can be bookmarked.
- **Why:** the contract offers flat lists only.

**D11. Editor.**

- **Setup:**
    - CodeMirror 6 with the legacy Gherkin mode;
    - `Ctrl/Cmd+S` saves;
    - a dirty indicator;
    - a leave guard: a router blocker plus `beforeunload`.
- **Save:** `saveFeature` with `source`, `path`, `baseVersion` = the loaded `currentVersion`, and an optional `message`.
- **Outcomes:**
    - **422 `GHERKIN_INVALID`:**
        - each `parseErrors` entry becomes a lint diagnostic at (`line`, `column`);
        - the lint panel opens;
        - an `aria-live="polite"` region says "N Gherkin errors; first on line L";
        - diagnostics stay until the next save attempt.
    - **409 `VERSION_CONFLICT`:**
        - a dialog says the feature changed (`currentVersion`) and offers "Reload latest version" or "Keep editing";
        - Reload shows the user's own text in a read-only "Your unsaved text" pane until it's dismissed;
        - there is no overwrite.
    - **409 `FEATURE_PATH_TAKEN`:** an error at the path field.
    - **409 `FEATURE_ARCHIVED` or `PROJECT_ARCHIVED`:** the editor becomes read-only, with a banner, and the data is
      refetched.
    - **An identical save (same version):** "No changes to save".
- **Why:** no edit is ever lost silently, and no edit overwrites someone else's.

**D12. Parse-error columns (G2).**

- One function maps (`line`, `column`) to a CodeMirror offset. It treats `column` as Unicode code points (SE1), converts
  them to UTF-16, and clamps to the line's end.
- Until SE1 is answered, AC-6.3 stays P1 and F3 stops on it.
- **Why:** an astral character (for example an emoji) is 2 UTF-16 units.

**D13. People's names (G3).**

- A user id is shown as the `fullName` from the org's `listMembers`, or "Former member" if they're not in it.
- The header shows the JWT `email`, plus the `fullName` once known from the current org's members.
- **Why:** there is no current-user endpoint; this needs no backend change.

**D14. Invitation links.**

- After a create, the inviter sees the token once, with "Copy link", which copies `<origin>/invite#<token>`.
- The accept page reads the fragment, removes it from the address bar (`history.replaceState`), and keeps it in memory.
- During a login detour, the token is kept in `sessionStorage` under `cucumber.pendingInvite` and deleted after use.
- **Why:** a fragment is never sent to a server or written to its logs.

**D15. Run screens (batch D).**

- **Runs list:** `listRuns`, 20 per page. Each row shows name, source, status, total, `passRate`, environment, build and
  created time.
- **Dashboard:**
    - an echarts stacked bar of `counts` for the runs on the current page;
    - a `passRate` line for the same runs;
    - each chart has a visible data table alternative.
- **Run detail:** the run summary (`counts`, `executed`, `passRate`, `durationMs`, `byFeature`).
- **Results:** 100 per page, with a status filter. A result's detail shows steps and hooks, with `errorMessage`
  collapsed.
- **Manual planning:** features picked from the tree, plus an optional tag, name, environment and build.
- **Recording:**
    - PASSED, FAILED, SKIPPED or UNKNOWN, plus a comment;
    - Complete and Cancel buttons.
- **Upload tokens:**
    - create shows the token once, with "Copy" and a curl snippet;
    - list and revoke.
- **Why:** the report-service D7 and D12 contract, with no new API.

**D16. Tests.**

- **vitest:** units (roles, JWT decode, the Problem mapper, the tree builder, the column mapping) and components (
  Testing Library plus MSW and the UI fixture).
- **Playwright:** journeys against `vite preview` with `page.route` fixtures, and axe on every route.
- **Manual run:** one against the real stack per standards §6 (F10).
- Fixtures must type-check against the generated schemas. Runtime schema validation would need a new package: stop and
  ask.
- **Why:** contract-true tests without a new dependency.

**D17. Accessibility (WCAG 2.1 AA).**

- axe finds no violations for the `wcag2a`, `wcag2aa`, `wcag21a` or `wcag21aa` tags on any route, in any view state.
- Visible focus; a skip link; one `h1` per page; focus moves to the `h1` after navigation.
- Dialogs trap focus and return it when they close.
- Form errors are linked with `aria-describedby`, and a summary takes focus after a failed submit.
- Colour is never the only signal: statuses carry text.
- Contrast ≥ 4.5:1; `prefers-reduced-motion` is respected.
- Charts have tables.
- **Why:** user decision; standards §1 AC style.

## Visual design (user decision 2026-11-03)

**Goal:** a polished SaaS look in the style of Linear or SmartBear Cucumber Studio. A collapsible left sidebar, a top
bar with switchers, cards and tables with subtle borders and elevation, and light and dark themes. Each DS item is
binding for every screen. The colour hex values are Tailwind 4 palette values, and every contrast ratio below is checked
by a test (AC-10.3).

**DS-1. Semantic colour tokens.** CSS custom properties in `src/ui/theme.css`, mapped into Tailwind's `@theme`. Feature
code uses only these names, never palette colours.

| Token             | Light                  | Dark                   | Use                                                      |
|-------------------|------------------------|------------------------|----------------------------------------------------------|
| `surface`         | `#FAFAFA` (zinc-50)    | `#09090B` (zinc-950)   | page background                                          |
| `surface-raised`  | `#FFFFFF`              | `#18181B` (zinc-900)   | cards, tables, sidebar, top bar, inputs                  |
| `surface-overlay` | `#FFFFFF`              | `#27272A` (zinc-800)   | menus, popovers, dialogs, toasts                         |
| `surface-hover`   | `#F4F4F5` (zinc-100)   | `#27272A`              | row and item hover, selected tree row                    |
| `border`          | `#E4E4E7` (zinc-200)   | `#27272A`              | decorative dividers and card edges                       |
| `border-control`  | `#71717A` (zinc-500)   | `#71717A`              | input, checkbox and switch outlines (≥ 3:1, WCAG 1.4.11) |
| `text`            | `#18181B` (zinc-900)   | `#FAFAFA`              | body text                                                |
| `text-muted`      | `#52525B` (zinc-600)   | `#A1A1AA` (zinc-400)   | secondary text, help, placeholders                       |
| `primary`         | `#4F46E5` (indigo-600) | `#818CF8` (indigo-400) | primary buttons, links, active nav                       |
| `primary-hover`   | `#4338CA` (indigo-700) | `#A5B4FC` (indigo-300) | hover and pressed                                        |
| `on-primary`      | `#FFFFFF`              | `#09090B`              | text on `primary`                                        |
| `danger`          | `#DC2626` (red-600)    | `#F87171` (red-400)    | destructive actions, errors                              |
| `warning`         | `#B45309` (amber-700)  | `#FBBF24` (amber-400)  | warnings                                                 |
| `success`         | `#15803D` (green-700)  | `#4ADE80` (green-400)  | success                                                  |
| `info`            | `#0369A1` (sky-700)    | `#38BDF8` (sky-400)    | info                                                     |
| `focus-ring`      | `#6366F1` (indigo-500) | `#818CF8`              | 2 px focus ring with a 2 px offset                       |

**Contrast minimums:**

- `text` and `text-muted` on `surface`, `surface-raised` and `surface-overlay`: ≥ 4.5:1. Measured on raised: light 17.7
  and 7.7; dark 16.7 and 6.8.
- `primary`, `danger`, `warning`, `success` and `info` as text on `surface-raised`: ≥ 4.5:1. Measured, light: 6.3, 4.8,
  5.0, 5.0, 5.9. Dark: 5.8 and up.
- `on-primary` on `primary`: ≥ 4.5:1. Light 6.3, dark 6.6.
- `focus-ring` and `border-control` against adjacent surfaces: ≥ 3:1.
- `danger` buttons use `on-primary`-style text: white on red-600 in light (4.8), and zinc-950 on red-400 in dark.

**DS-2. Execution status colours.** A StatusChip always has an icon and the status word. Colour is never the only
signal.

| Status    | Light text on background | Dark text on background      | Icon          |
|-----------|--------------------------|------------------------------|---------------|
| PASSED    | green-700 on green-50    | green-300 on green-950/60%   | check-circle  |
| FAILED    | red-700 on red-50        | red-300 on red-950/60%       | x-circle      |
| SKIPPED   | sky-700 on sky-50        | sky-300 on sky-950/60%       | skip-forward  |
| PENDING   | amber-700 on amber-50    | amber-300 on amber-950/60%   | clock         |
| UNDEFINED | purple-700 on purple-50  | purple-300 on purple-950/60% | help-circle   |
| AMBIGUOUS | orange-700 on orange-50  | orange-300 on orange-950/60% | git-fork      |
| UNKNOWN   | slate-600 on slate-100   | slate-300 on slate-800       | circle-dashed |

- Every chip's text is ≥ 4.5:1 on its own background (light: 4.9 to 7.0).
- The same seven colours, at full strength, are the series colours of the echarts charts (F8), in both themes.
- Run statuses (PLANNED, IN_PROGRESS, COMPLETED, CANCELLED) use the neutral Badge (DS-9) with an icon.

**DS-3. Type.**

- **Sans:** `ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`.
- **Mono** (editor, paths, ids, tokens): `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`.
- **Scale** (size/line height, px):

| Name   | Size/line | Use                     |
|--------|-----------|-------------------------|
| `xs`   | 12/16     | captions, badges        |
| `sm`   | 13/20     | tables, sidebar         |
| `base` | 14/20     | body and controls       |
| `md`   | 16/24     | auth forms, card titles |
| `lg`   | 18/28     | section titles          |
| `xl`   | 20/28     | dialog titles           |
| `2xl`  | 24/32     | page `h1`               |

- **Weights:** 400 for body, 500 for labels and nav, 600 for titles.
- **Tabular figures** for numbers in tables and charts.

**DS-4. Spacing, radius, elevation.**

- **Spacing:** Tailwind's 4 px scale, using only these steps: 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12, 16 (4–64 px).
- **Rhythm:** control padding 2/3 (8/12 px); card padding 4 or 6; page padding 6 (≥ `lg`) or 4 (< `lg`); 6 between
  sections.
- **Radius:** `sm` 4 px (chips, badges); `md` 6 px (buttons, inputs, menu items); `lg` 8 px (cards, tables); `xl` 12
  px (dialogs, the auth card); `full` (avatars, switches).
- **Elevation, light theme:**
    - `xs` `0 1px 2px rgb(0 0 0 / .05)`: controls;
    - `sm` `0 1px 3px rgb(0 0 0 / .08), 0 1px 2px rgb(0 0 0 / .04)`: cards;
    - `md` `0 4px 12px rgb(0 0 0 / .10)`: menus, popovers;
    - `lg` `0 12px 32px rgb(0 0 0 / .16)`: dialogs, the drawer.
- **Elevation, dark theme:** the same shadows at double opacity, plus a 1 px `border` on every raised element.

**DS-5. Motion.**

- **Durations:** 120 ms for hover and press; 180 ms for menus, popovers and tooltips; 240 ms for dialogs and the drawer.
- **Easing:** `cubic-bezier(.2, 0, 0, 1)`.
- **Properties:** opacity and transform only, never layout.
- **Reduced motion:** with `prefers-reduced-motion: reduce`, every transition and animation is off, including the
  Skeleton shimmer, which becomes a static tint.

**DS-6. Themes.**

- **Settings:** `system` (the default, following `prefers-color-scheme` live), `light` and `dark`.
- **Where it's set:** in the top-bar theme menu. It's stored in `localStorage` as `cucumber.ui.theme`, and only
  `src/ui/prefs.ts` reads or writes it.
- **Applied by:** the `data-theme` attribute on `<html>`, set by a tiny inline script in `index.html` before first
  paint, so there is no flash of the wrong theme.
- **Also themed:** CodeMirror (F3) and echarts (F8) take their colours from the tokens.

**DS-7. Layout and breakpoints.** Tailwind's defaults (`sm` 640, `md` 768, `lg` 1024, `xl` 1280).

- **Top bar:** 48 px, `surface-raised`, with a bottom `border`.
    - **Left:**
        - the product mark (a 24 px `primary` rounded square with a white "C", plus the wordmark "Cucumber");
        - the org switcher (a Menu of `listMyOrganizations` plus "Create organization");
        - "/";
        - the project switcher (a ComboBox of the org's accessible projects).
    - **Right:**
        - the theme menu (System, Light, Dark; the checked item marked);
        - the user menu: an Avatar with initials; the name (D13) and email; "Log out".
- **Sidebar, ≥ `lg`:**
    - 240 px, collapsible to 56 px (icons only, each with a Tooltip carrying its label);
    - the state is stored as `cucumber.ui.sidebar` via `src/ui/prefs.ts`.
- **Sidebar, < `lg`:** a modal drawer opened from a menu button in the top bar. It traps focus, closes on Escape and on
  navigation, and has a scrim.
- **Sidebar contents:**
    - org section: Projects, Members;
    - project section, when a project is open: Features, Runs, Upload tokens (`ADMIN` only), Members, Settings;
    - items the role can't use are absent (D5);
    - the active item has `aria-current="page"`, a `surface-hover` background and a 2 px `primary` left bar.
- **Content:**
    - padding per DS-4; tables and the tree are fluid;
    - forms and settings are at most 640 px wide;
    - the editor fills the remaining height.

**DS-8. Page templates.**

- **Auth** (login, register, accept invitation):
    - a centred `surface-raised` card on `surface`, 400 px wide (full width with 16 px margins < `sm`), `xl` radius,
      `lg` shadow;
    - the product mark above the title (`2xl`); the form; secondary links below.
- **App page:**
    - a PageHeader: Breadcrumbs (org › project › area), the `h1`, an optional one-line description in `text-muted`, and
      the primary action on the right (secondary actions in an overflow Menu);
    - then content in Cards or a Table.
- **Detail page:** a PageHeader plus Tabs.
    - Feature: Editor, History, Test cases.
    - Run: Summary, Results.
- **View states (AC-9.2) inside templates:**
    - EmptyState and the error, forbidden and not-found states sit centred in the content area: an icon in a 40 px muted
      circle, a title, one sentence, and the permitted action;
    - loading uses Skeletons shaped like the final layout (table rows, cards, tree rows).

**DS-9. Component inventory (`src/ui/*`).** Each is built on react-aria-components where one exists. Every interactive
component has these states: default, hover, pressed, focus-visible (the DS-1 ring) and disabled (50% opacity,
`cursor-not-allowed`, still focusable only where the ARIA pattern requires it).

| Component                                 | Variants and specific states                                                                                                                                                                                                                |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Button                                    | primary, secondary (raised and bordered), ghost, danger; sizes `sm` 28 px, `md` 32 px, `lg` 40 px (auth); icon-only (needs `aria-label`); loading (spinner, label kept for width, `aria-busy`, not pressable)                               |
| TextField, TextArea                       | label, description, error (`danger` border and message via `aria-describedby`), read-only, required marker; TextArea auto-grows to 8 lines                                                                                                  |
| Select, ComboBox                          | placeholder, open listbox (`md` elevation), selected item check, empty "No matches", loading                                                                                                                                                |
| Checkbox, Switch                          | checked, indeterminate (Checkbox), invalid                                                                                                                                                                                                  |
| Dialog, ConfirmDialog                     | `sm` 400, `md` 560 and `lg` 720 px; title, body, footer actions (primary on the right); ConfirmDialog has a danger variant whose confirm button names the action ("Archive feature")                                                        |
| Menu                                      | items with an optional icon and shortcut hint, separators, sections, a danger item, checkable items (theme menu)                                                                                                                            |
| Tabs                                      | underline style, keyboard arrows, the selected tab in `primary`                                                                                                                                                                             |
| Table                                     | sticky header, sortable columns (`aria-sort`, header button with an arrow icon), row hover, selectable rows (Checkbox), right-aligned numerics, Pagination below ("1–20 of 57", previous and next, a page-size Select where the API allows) |
| Tree                                      | 28 px rows, chevron expand, folder and file icons, selected and focused rows, virtualized (D10)                                                                                                                                             |
| Badge, StatusChip                         | Badge: neutral, primary, success, warning, danger; StatusChip per DS-2                                                                                                                                                                      |
| Toast                                     | success, error, info; bottom-right (top on mobile), auto-dismiss after 5 s (errors stay until dismissed), pause on hover and focus, announced politely (errors assertively)                                                                 |
| EmptyState                                | icon (from the QD3 set, no illustrations), title, text, action                                                                                                                                                                              |
| Skeleton                                  | line, block and row shapes; shimmer per DS-5                                                                                                                                                                                                |
| Breadcrumbs                               | links with a `/` separator; the last item is current and not a link                                                                                                                                                                         |
| Avatar                                    | initials from `fullName` (or the email's first letter) on a colour hashed from the user id among 6 token-safe hues; 24 and 32 px                                                                                                            |
| Tooltip                                   | after 500 ms on hover or immediately on focus; `xs` text on an inverted surface; never the only place information lives                                                                                                                     |
| PageHeader, Card, Sidebar, TopBar, Drawer | as DS-7 and DS-8                                                                                                                                                                                                                            |

**DS-10. Iconography.** Icons come from the QD3 set: 16 px in controls, 20 px in the sidebar, 24 px in empty states.
Each is decorative (`aria-hidden`) unless it's the only content of a button.

**DS-11. Writing.** Sentence case everywhere. Buttons are verbs ("Create project"). Errors say what happened and what to
do (AC-9.1's texts).

**DS-12. No ad-hoc styling.**

- Feature code composes `src/ui` components and token utility classes for layout only (flex, grid, gap and spacing
  steps).
- No inline `style` (except virtualizer positioning), no arbitrary values (`[#…]`, `[13px]`) and no new CSS files.
  `src/ui/theme.css` is the only stylesheet.
- A component that's missing is added to `src/ui` per DS-9 and noted in the handoff.

## Contract gaps (triage)

| Gap | Decision (default)                            | Owner                                                           | Priority                        |
|-----|-----------------------------------------------|-----------------------------------------------------------------|---------------------------------|
| G1  | Gateway routes for report-service             | **Resolved**: gateway 0.3.4 routes GW1–GW5                      | —                               | — |
| G2  | Unit of `parseErrors.column` undefined        | SE1: verify and document (Q3); D12 meanwhile                    | backend-swe (SE1), frontend-swe | P1 |
| G3  | No current-user endpoint (`fullName`)         | D13 in phase 1; FU-F1 `GET /api/auth/me` later                  | pm (auth-service spec)          | P2 |
| G4  | No push channel                               | D9 polling; FU-F2 server-sent events later                      | pm                              | P3 |
| G5  | `listRuns` has no filters or trend aggregates | D15 uses the current page only; FU-F3 in report-service phase 2 | pm (report-service phase 2)     | P2 |
| G6  | No refresh or server logout                   | D4: client logout, re-login after 24 h; FU-F4                   | pm (auth-service spec)          | P2 |
| G7  | Production hosting and CORS                   | Out of scope; FU-F5                                             | pm                              | P2 |

## User stories and acceptance criteria

Every view also meets AC-9.2 (view states) and AC-9.3 (axe). The data is the Acme fixture.

### US-1: Session (P0)

```gherkin
@US-1 @AC-1.1 @P0
Scenario: Log in and land where you were going
  Given Ana opens "/orgs/{acme}/projects" without a session
  Then she is sent to "/login?next=%2Forgs%2F{acme}%2Fprojects"
  When she logs in with "ana@example.com" and "Secret123!"
  Then she sees the Acme projects page, and sessionStorage "cucumber.session" holds the token
  And localStorage, document.cookie and the URL do not contain the token

@US-1 @AC-1.2 @P0
Scenario: Wrong credentials
  When Ana logs in with password "wrong"
  Then the login answers 401 INVALID_CREDENTIALS, the form shows "Email or password is incorrect", the password field is empty, and focus is on the error summary

@US-1 @AC-1.3 @P0
Scenario: Register, then log in automatically
  When Fay registers with full name "Fay Lin", email "fay@example.com" and password "Secret123!"
  Then the app logs her in with the same credentials and shows "/orgs" with the empty state "You're not in an organization yet" and a "Create organization" action
  And when registration answers 400 VALIDATION_FAILED with errors for "password", the message appears under the password field, linked by aria-describedby
  And when it answers 409 EMAIL_ALREADY_REGISTERED, the form says "An account with this email exists" and offers "Log in"

@US-1 @AC-1.4 @P0
Scenario: A 401 anywhere ends the session and keeps the location
  Given Gus is on "/projects/{checkout}/features?tag=%40smoke"
  When any API call answers 401
  Then the session is cleared and he is on "/login?next=%2Fprojects%2F{checkout}%2Ffeatures%3Ftag%3D%2540smoke"
  And after logging in again he is back on that page with the tag filter applied

@US-1 @AC-1.5 @P0
Scenario Outline: Session on start
  Given sessionStorage holds <token>
  When the app starts
  Then <outcome>
  Examples:
    | token                        | outcome                                                    |
    | a token with exp in 1 hour   | Ana is logged in without a request to /api/auth/login      |
    | a token with exp 1 s ago     | the mirror is cleared and the login page shows             |
    | "not-a-jwt"                  | the mirror is cleared and the login page shows             |
    | nothing                      | the login page shows                                       |

@US-1 @AC-1.6 @P0
Scenario: Expiry and logout
  Given Ana's token expires in 2 s
  When 2 s pass
  Then she is on the login page with "Your session expired. Log in again."
  And after "Log out", memory, sessionStorage "cucumber.session" and the query cache are empty, and the browser's Back button shows the login page, not cached data

@US-1 @AC-1.7 @P1
Scenario Outline: The next parameter can't leave the app
  When Ana logs in from "/login?next=<next>"
  Then she lands on "<landing>"
  Examples:
    | next                     | landing |
    | %2Forgs                  | /orgs   |
    | %2F%2Fevil.example       | /       |
    | https%3A%2F%2Fevil.example | /     |
    | javascript%3Aalert(1)    | /       |
```

### US-2: Accept an invitation (P1)

```gherkin
@US-2 @AC-2.1 @P1
Scenario: A new person accepts
  Given Fay, without a session, opens "/invite#tok-123"
  Then the address bar shows "/invite" without the fragment
  When she enters full name "Fay Lin" and password "Secret123!" and accepts
  Then the API gets "POST /api/auth/invitations/tok-123/accept" without Authorization, answers 201, and Fay sees "You joined Acme as MEMBER" and the login form with "fay@example.com" filled in

@US-2 @AC-2.2 @P1
Scenario: A logged-in person accepts, including a login detour
  Given Gus opens "/invite#tok-456" without a session and chooses "I have an account"
  When he logs in
  Then he returns to the accept page, "cucumber.pendingInvite" is removed after use, the accept is sent with his Bearer token and no body, and he lands on Acme's page

@US-2 @AC-2.3 @P1
Scenario Outline: The invitation can't be used
  When the accept answers <status> <code>
  Then the page shows "<message>" and no form
  Examples:
    | status | code                         | message                                                    |
    | 404    | INVITATION_NOT_FOUND         | This invitation link isn't valid.                          |
    | 410    | INVITATION_EXPIRED           | This invitation has expired. Ask for a new one.            |
    | 410    | INVITATION_REVOKED           | This invitation was withdrawn.                             |
    | 410    | INVITATION_ALREADY_ACCEPTED  | This invitation was already used.                          |
    | 403    | INVITATION_EMAIL_MISMATCH    | This invitation is for a different email. Log out and try again. |
    | 409    | ALREADY_MEMBER               | You're already a member of this organization.              |
    | 409    | SEAT_LIMIT_REACHED           | The organization has no free seat. Ask an admin.           |
```

### US-3: Organizations (P1)

```gherkin
@US-3 @AC-3.1 @P1
Scenario: List and create
  When Ana opens "/orgs"
  Then she sees Acme with role "OWNER"
  When she creates "Globex"
  Then she is on Globex's page with role OWNER, and the list contains both

@US-3 @AC-3.2 @P1
Scenario: Change a role within the ceiling
  When Ben (ADMIN) opens Acme's members
  Then Ana's row (OWNER) has no role menu and no remove action, and Ben's own row has no role menu
  And Gus's role menu offers exactly ADMIN, MEMBER, READER
  When Ben makes Gus READER
  Then the list shows Gus as READER after the 200
  And a 422 LAST_OWNER, ROLE_CEILING or SELF_ROLE_CHANGE is shown next to the row, and the row keeps its previous role

@US-3 @AC-3.3 @P1
Scenario: Invite and share the link once
  When Ben invites "fay@example.com" as MEMBER
  Then a dialog shows the token once with "Copy link" copying "<origin>/invite#<token>", and after it closes the token appears nowhere in the DOM or storage
  And a 409 SEAT_LIMIT_REACHED shows "No free seat (5 of 5 used)" at the form

@US-3 @AC-3.4 @P1
Scenario: Pending invitations and seats
  Given Acme has 5 members and 0 pending invitations
  When Ben opens Acme
  Then he sees "Seats: 5 of 5 used", the pending list is empty with "No pending invitations", and "Invite" is disabled with the reason "No free seat"
  When Ben revokes a pending invitation
  Then it disappears and the seat count drops by 1

@US-3 @AC-3.5 @P1
Scenario Outline: Organization actions by role
  When <user> (<role>) opens Acme
  Then <action> is <shown>
  Examples:
    | user | role   | action                  | shown   |
    | Ana  | OWNER  | Invite, roles, remove   | shown   |
    | Ben  | ADMIN  | Invite, roles, remove   | shown   |
    | Gus  | MEMBER | Invite, roles, remove   | absent  |
    | Dan  | READER | Invite, roles, remove   | absent  |
    | Gus  | MEMBER | Members list            | shown   |
    | Ana  | OWNER  | Create project          | shown   |
    | Gus  | MEMBER | Create project          | absent  |
```

### US-4: Projects (P1)

```gherkin
@US-4 @AC-4.1 @P1
Scenario: List and create
  When Gus opens Acme's projects
  Then he sees Checkout only (listProjects returns only accessible projects), with "MEMBER"
  When Ana creates "Payments"
  Then she is on Payments with "ADMIN"
  And creating "checkout" answers 409 PROJECT_NAME_TAKEN, shown at the name field

@US-4 @AC-4.2 @P1
Scenario: Rename, archive and restore
  When Ana renames Checkout to "Checkout Web"
  Then the title changes after the 200
  When she archives it
  Then every write action in Checkout's pages is absent, and a banner says "Project archived: read only", with "Restore" for Ana
  And a 409 PROJECT_ARCHIVED from any write refetches the project and shows the same banner

@US-4 @AC-4.3 @P1
Scenario: Project members
  When Ana adds Ben as TESTER to Checkout, changes Hal to MEMBER, and removes Dan
  Then the member list shows Ben TESTER, Gus MEMBER, Hal MEMBER
  And adding Dan (org READER) as MEMBER answers 422 PROJECT_ROLE_NOT_ALLOWED, shown at the role field
  And the add dialog offers only current Acme members who aren't explicit members yet
  And Gus sees "Leave project" on his own row only

@US-4 @AC-4.4 @P1
Scenario Outline: Project actions by role
  When <user> (project <role>) opens Checkout's settings
  Then rename, archive, restore and member changes are <shown>
  Examples:
    | user | role   | shown  |
    | Ana  | ADMIN  | shown  |
    | Gus  | MEMBER | absent |
    | Hal  | TESTER | absent |
    | Dan  | READER | absent |
```

### US-5: Feature tree (P0)

```gherkin
@US-5 @AC-5.1 @P0
Scenario: The tree is built from paths
  When Gus opens Checkout's features
  Then the tree shows folder "features" > folder "checkout" > files "address.feature" and "payment.feature" in that order, each with its name, tags and testCaseCount
  And "features/legacy.feature" isn't shown

@US-5 @AC-5.2 @P0
Scenario: Filter by a tag
  When Gus selects the chip "@smoke"
  Then the API gets "listFeatures?tag=%40smoke", only address.feature shows, the URL has "tag=%40smoke", and the chip has aria-pressed "true"
  When he selects "@smoke" again
  Then the filter clears

@US-5 @AC-5.3 @P0
Scenario: Archive and restore
  When Gus archives payment.feature after confirming
  Then it leaves the active tree and appears in the "Archived" view
  When he restores it while an active feature has the same path
  Then the 409 FEATURE_PATH_TAKEN says "An active feature already uses this path" and it stays archived

@US-5 @AC-5.4 @P0
Scenario: Create a feature
  When Gus creates path "features/checkout/refund.feature"
  Then the API gets createFeature with that path and the source "Feature: Refund\n", the tree selects the new file, and the editor opens at version 1
  And a 400 VALIDATION_FAILED for "path" and a 409 FEATURE_PATH_TAKEN are shown at the path field

@US-5 @AC-5.5 @P1
Scenario: Large trees stay usable by keyboard
  Given Checkout has 2,000 features in 40 folders
  When Gus opens the tree
  Then fewer than 200 tree rows are in the DOM, ArrowDown, ArrowRight, Home and End move through rows, Enter opens the feature, and type-ahead "pay" focuses payment.feature
```

### US-6: Feature editor (P0)

```gherkin
@US-6 @AC-6.1 @P0
Scenario: Edit and save
  Given Gus opened payment.feature at currentVersion 2
  When he changes a step, enters the message "Clarify card step" and presses Ctrl+S
  Then the API gets saveFeature with "baseVersion" 2, the source, the path and the message; after the 200 the editor shows version 3 and "Saved", and the dirty marker is gone

@US-6 @AC-6.2 @P0
Scenario: Gherkin errors show where they are
  When Gus saves text where line 4 is "  Whenn I pay"
  And saveFeature answers 422 GHERKIN_INVALID with parseErrors [{"line":4,"column":3,"message":"(4:3): expected: #EOF, #TableRow, ... got 'Whenn I pay'"}]
  Then line 4 column 3 has an error diagnostic, the lint panel is open with that message, the aria-live region says "1 Gherkin error; first on line 4", the text is unchanged and still dirty
  And with 3 errors the region says "3 Gherkin errors; first on line 2", and F8 moves to the next diagnostic

@US-6 @AC-6.3 @P1
Scenario Outline: Columns on non-ASCII lines (D12, SE1)
  Given line 2 is <line>
  When parseErrors says line 2, column <column>
  Then the diagnostic starts before the character <char>
  Examples:
    | line                    | column | char |
    | "  Scénario: Pay"       | 13     | "P"  |
    | "  Scenario: 💳 Pay"    | 16     | "P"  |
    | "  Scenario: Pay"       | 99     | end of line |

@US-6 @AC-6.4 @P0
Scenario: Someone else saved first
  Given Gus edits payment.feature from version 2
  When saveFeature answers 409 VERSION_CONFLICT with "currentVersion" 3
  Then a dialog says "This feature changed (now version 3) since you opened it" with "Reload latest version" and "Keep editing", and focus is in the dialog
  When he chooses "Reload latest version"
  Then the editor shows version 3, and a read-only "Your unsaved text" pane shows his text until he dismisses it
  And no request was sent with "baseVersion" 3 automatically

@US-6 @AC-6.5 @P0
Scenario: Path conflicts and unsaved changes
  When Gus changes the path to "features/checkout/address.feature" and saves
  Then 409 FEATURE_PATH_TAKEN is shown at the path field
  When he navigates to address.feature with unsaved changes
  Then a dialog asks "Discard unsaved changes?" and staying keeps his text; closing the tab triggers the browser's leave prompt

@US-6 @AC-6.6 @P0
Scenario Outline: Read-only editor
  When <user> opens payment.feature <context>
  Then the editor is read-only, Save is absent, and the banner says "<banner>"
  Examples:
    | user | context                         | banner                          |
    | Hal  | (project TESTER)                | Read only: your role is TESTER  |
    | Dan  | (project READER)                | Read only: your role is READER  |
    | Gus  | of an archived feature          | Feature archived: read only     |
    | Ana  | in archived project Billing     | Project archived: read only     |
    | Gus  | after a save answers 409 FEATURE_ARCHIVED | Feature archived: read only |

@US-6 @AC-6.7 @P1
Scenario: Saving without changes
  When Gus saves without changing anything and saveFeature answers 200 with currentVersion 2
  Then the editor says "No changes to save" and stays at version 2
```

### US-7: History and test cases (P1)

```gherkin
@US-7 @AC-7.1 @P1
Scenario: Version history
  When Dan opens payment.feature's history
  Then he sees versions 2 and 1, newest first, each with author name (D13), time and message
  When he opens version 1
  Then its text shows read-only with "Version 1 of 2", and a version that answers 404 shows the not-found state

@US-7 @AC-7.2 @P1
Scenario: Test cases
  When Dan opens payment.feature's test cases
  Then a table lists each test case with name, kind, line and tags, in the API's order, and an example row shows its scenario's name
```

### US-8: Runs, results, manual runs and upload tokens (P1, batch D)

```gherkin
@US-8 @AC-8.1 @P1
Scenario: Runs list and dashboard
  When Dan opens Checkout's runs
  Then he sees "Sprint 12" and "Nightly" newest first, with status, total and pass rate ("Nightly": 50.0%)
  And the chart of counts and the pass-rate line each have a "Show as table" alternative with the same numbers
  And "Next page" requests page 1 with size 20

@US-8 @AC-8.2 @P1
Scenario: Run detail and results
  When Dan opens "Nightly"
  Then the summary shows 2 PASSED, 1 FAILED, 1 PENDING, "executed" 4, pass rate 50.0%, and the per-feature table
  When he filters results by FAILED
  Then the API gets listResults with status FAILED, and opening the result shows its steps in order with the failed step's error message collapsed behind "Show error"

@US-8 @AC-8.3 @P1
Scenario: Plan a manual run
  When Gus plans "Sprint 13" with Payment and Address, tag "@smoke", environment "staging"
  Then the API gets planManualRun with those featureIds in that order, and he lands on the new PLANNED run
  And 422 FEATURE_NOT_PLANNABLE marks each id in "featureIds" in the picker, 422 RUN_EMPTY says "No test case matches", 422 RUN_TOO_LARGE says "More than 2,000 test cases", and 503 SCENARIO_SERVICE_UNAVAILABLE shows the error state with Retry

@US-8 @AC-8.4 @P1
Scenario: Record, complete and cancel
  When Hal marks a result FAILED with comment "Card declined"
  Then the row shows FAILED and Hal's name after the 200, and the run is IN_PROGRESS
  When Hal completes the run
  Then it shows COMPLETED, recording controls disappear, and a 409 RUN_CLOSED on any later write refetches the run
  And Gus sees "Cancel run"; Hal doesn't

@US-8 @AC-8.5 @P1
Scenario: Upload tokens
  When Ana creates a token named "GitHub Actions"
  Then a dialog shows the token once, with "Copy" and a curl example for "POST /api/ci/runs"; after closing, the token appears nowhere in the DOM or storage, and the list shows only its prefix
  When she revokes it after confirming
  Then it shows "Revoked"; a 409 UPLOAD_TOKEN_LIMIT on create says "At most 10 active tokens"

@US-8 @AC-8.6 @P1
Scenario Outline: Report actions by project role
  When <user> (<role>) opens Checkout's runs
  Then <action> is <shown>
  Examples:
    | user | role   | action                    | shown  |
    | Dan  | READER | record, complete          | absent |
    | Hal  | TESTER | record, complete          | shown  |
    | Hal  | TESTER | plan, cancel              | absent |
    | Gus  | MEMBER | plan, cancel              | shown  |
    | Gus  | MEMBER | upload tokens             | absent |
    | Ana  | ADMIN  | upload tokens             | shown  |

@US-8 @AC-8.7 @P2
Scenario: Open runs refresh
  Given "Sprint 12" is IN_PROGRESS and the tab is visible
  Then its detail refetches every 30 s, and stops while the tab is hidden or once it is COMPLETED
```

### US-9: Errors, states, accessibility and safety (P0)

```gherkin
@US-9 @AC-9.1 @P0
Scenario Outline: One Problem mapper
  When a call answers <response>
  Then the UI shows <ui>
  Examples:
    | response                                              | ui                                                                 |
    | 401 (any call but login)                              | AC-1.4                                                             |
    | 403 FORBIDDEN                                         | the forbidden state "You don't have access to this", and the role is refetched |
    | 403 without code (gateway CORS)                       | the error state "The request was refused"                          |
    | 404 any NOT_FOUND code                                | the not-found state with a link back                               |
    | 409 PROJECT_ARCHIVED                                  | the archived banner and a refetch                                  |
    | 409 CONFLICT (lock timeout)                           | "Someone else is changing this. Try again." with Retry             |
    | 400 VALIDATION_FAILED with errors [{"field":"name"}]  | the message at the name field                                      |
    | 400 MALFORMED_REQUEST                                 | "The request could not be read." and the reference                 |
    | 502, 503, 504 (with or without code)                  | the error state "The service is unavailable. Try again." with Retry and "Reference: <requestId>" when present |
    | 500                                                   | the error state "Something went wrong" with the reference          |
    | a network failure or a non-Problem body               | the error state "Can't reach the server" with Retry               |

@US-9 @AC-9.2 @P0
Scenario Outline: Every data view has its states
  When <view> is <state>
  Then it shows <ui>, and assistive technology hears it (aria-busy or a live region)
  Examples:
    | view            | state     | ui                                                   |
    | projects list   | loading   | a busy indicator after 300 ms, no layout jump         |
    | projects list   | empty     | "No projects yet" and "Create project" for OWNER/ADMIN |
    | feature tree    | empty     | "No features yet" and "New feature" for MEMBER+       |
    | feature tree    | error     | the AC-9.1 error state with Retry                    |
    | runs list       | empty     | "No runs yet", and the CI upload hint for ADMIN      |
    | editor          | forbidden | the forbidden state                                  |
    | editor          | not-found | "This feature doesn't exist" and a link to the tree   |

@US-9 @AC-9.3 @P0
Scenario: axe passes on every route
  When Playwright visits every route in ready, empty and error states, and with each dialog open, in the light and the dark theme (user 2026-11-03)
  Then axe reports 0 violations for wcag2a, wcag2aa, wcag21a and wcag21aa

@US-9 @AC-9.4 @P0
Scenario: Keyboard-only journey
  Using only the keyboard, Gus logs in, opens Checkout, finds payment.feature in the tree, edits and saves it, sees a 422 marker, fixes it and saves
  Then focus is always visible, never lost to <body> after a dialog or navigation, and the skip link reaches the main content

@US-9 @AC-9.5 @P0
Scenario: The client matches the contracts
  When "npm test" runs
  Then "gen:api:check" passes only if src/api/generated equals a fresh generation from docs/api, "tsc --noEmit" passes in strict mode, and eslint (with jsx-a11y) reports 0 errors

@US-9 @AC-9.6 @P0
Scenario: Secrets stay secret
  During AC-1.1 to AC-8.5
  Then no console message, URL, localStorage entry or cookie contains a JWT, an invitation token, an upload token or a password
```

### US-10: Visual design and themes (P0, batch DS; user 2026-11-03)

```gherkin
@US-10 @AC-10.1 @P0
Scenario Outline: The theme follows the OS until chosen
  Given localStorage "cucumber.ui.theme" is <stored> and the OS prefers <os>
  When Ana loads "/login"
  Then <html> has data-theme "<applied>" before first paint (no frame in the other theme in a Playwright trace)
  Examples:
    | stored  | os    | applied |
    | (none)  | dark  | dark    |
    | (none)  | light | light   |
    | system  | dark  | dark    |
    | light   | dark  | light   |
    | dark    | light | dark    |
  And when Ana picks "Dark" in the theme menu, the page switches without reload, the menu shows "Dark" checked, and the choice survives a reload; with "System", an OS change while open switches the theme live

@US-10 @AC-10.2 @P0
Scenario: The app shell
  Given Gus is in Acme's project Checkout at 1440×900
  Then the top bar shows the product mark, the org switcher "Acme", the project switcher "Checkout", the theme menu and his initials "GU"
  And the sidebar lists Projects and Members, then Features, Runs, Members and Settings, with no Upload tokens (MEMBER), and "Features" has aria-current "page"
  When he collapses the sidebar
  Then it is 56 px wide with icon-only items whose tooltips show their labels, and the state survives a reload
  When the viewport is 390×844
  Then the sidebar is hidden, the top-bar menu button opens it as a drawer that traps focus, and Escape or choosing an item closes it and returns focus to the menu button

@US-10 @AC-10.3 @P0
Scenario: Token contrast is checked
  When the contrast test reads src/ui/theme.css for both themes
  Then every DS-1 and DS-2 pair meets its stated minimum (4.5:1 for text, 3:1 for focus-ring and border-control)

@US-10 @AC-10.4 @P0
Scenario: Batch A screens use the design system
  Then login, register, the org list (ready and empty), and the loading, error, forbidden and not-found states use DS-8's templates and DS-9's components, with no inline style, arbitrary value or CSS file outside src/ui/theme.css (an eslint or grep check in npm test)

@US-10 @AC-10.5 @P1
Scenario: Review screenshots
  When "npm run screenshots" runs
  Then frontend/review-screenshots/ holds one PNG per batch A screen and state × light and dark × 1440×900 and 390×844, named "<screen>-<state>-<theme>-<width>.png", including the drawer open, the user menu open and the theme menu open; the folder is gitignored

@US-10 @AC-10.6 @P1
Scenario: Reduced motion
  Given the OS asks for reduced motion
  Then opening a dialog, a menu and the drawer has no transition, and Skeletons don't shimmer
```

## E-list: changes outside `frontend/`

| ID  | Change                                                                                                                                                                                                                                                 | Files                                                                                                                | Why     | Risk | Depends | Task                        |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|---------|------|---------|-----------------------------|
| SE1 | Verify the unit of `parseErrors[].column` with one new test (a line with `é` and one with `💳` before the error), and document it in the `ParseError.column` description; contract 1.0.6 → 1.0.7, docs only. **Approved by the user 2026-11-02 (Q3).** | `scenario-service/src/test/java/org/scenarioservice/` (one new test class), `docs/api/scenario-service.openapi.yaml` | G2, D12 | Low  | —       | backend-swe, before F3 ends |

| FE1 | Restyle batch A's committed frontend files onto the design system (DS-1 to DS-12); behaviour unchanged. **User
decision 2026-11-03.** | the batch A files named in F11's Allowed files | The user rejected the bare styling | Medium (
touches session screens) | QD1–QD3 | F11 |

**Will not be touched:** every service's code, tests and config; every contract except SE1's note; the gateway (no CORS
change, D3); the ERD (no stored data changes); repository-root files (`.gitignore`, `.env*`, `README.md`,
`settings.gradle`). `docs/api/*` is read-only input to `gen:api`.

## Frontend guardrails (beyond standards §1, §2, §5 and §6)

- **Layout:** in `frontend/`:
    - `src/app` (providers, router, shell);
    - `src/api` (client, middleware, `problem.ts`, `generated/`);
    - `src/auth` (session, JWT decode, `roles.ts`);
    - `src/ui` (shared states, dialogs, form parts);
    - `src/features/<area>`;
    - `src/test` (MSW, fixtures, render helpers);
    - `e2e/`;
    - plus `scripts/gen-api.mjs`.
- **One owner per concern:**
    - only `src/api` calls `fetch`;
    - only `problem.ts` interprets error bodies;
    - only `roles.ts` compares roles;
    - only `src/auth/session.ts` touches `sessionStorage` (`cucumber.session`, `cucumber.pendingInvite`);
    - only `src/ui/prefs.ts` touches `localStorage` (`cucumber.ui.theme`, `cucumber.ui.sidebar`; never a secret);
    - only `src/ui/theme.css` defines styles (DS-12).
- **Code rules:**
    - no `any`, and no `as` casts on API data;
    - no `dangerouslySetInnerHTML`;
    - no `console.*` in `src` (lint rule);
    - all text in each area's `strings.ts`.
- **Test first:** each row in "Test first" is written and fails before the code.
- **Commands:** `npm ci`, `npm run lint`, `npm run typecheck`, `npm test` (vitest plus `gen:api:check`), `npm run e2e`.
  Report counts, as `passed/failed/skipped`.
- **Never** commit (the user commits), read `.env`, or start a backend except in F10's manual run (standards §6).

## Tasks for frontend-swe

**Every task:**

- Allowed files are only under `frontend/**`, as listed.
- **Must not:** change any backend, contract or DTO; add a package beyond D2, Q2 and QD1–QD3; store a token outside D4.
- **UI (from F11 on, user 2026-11-03):**
    - every screen uses the DS-8 templates and the DS-9 components from `src/ui`, with token classes only (DS-12), and
      no ad-hoc CSS;
    - `src/ui/**` may be extended additively with a missing DS-9-style component; changing an existing `src/ui`
      component's look or API needs pm;
    - new screens and states are added to `npm run screenshots` (both themes, both widths);
    - axe runs in both themes (AC-9.3).
- **Done when:** its ACs are green, plus lint, typecheck, test and e2e (from F2) with counts, plus a §8 handoff.
- **Common stop conditions:**
    - a file outside Allowed files;
    - a contract that doesn't support an AC;
    - a package version that doesn't resolve or conflicts with the pinned TypeScript 5.9.3;
    - an existing test that would need weakening.

### Batch A: foundation (review point A)

**F1. Scaffold, tooling, typed client.**

- **Allowed files:**
    - `frontend/package.json`, `package-lock.json`, `.nvmrc`, `.gitignore`;
    - `tsconfig*.json`, `vite.config.ts` (with the `/api` proxy), `vitest.config.ts`, `playwright.config.ts`,
      `eslint.config.js`, `index.html`;
    - `scripts/gen-api.mjs`, `src/api/generated/**`, `src/main.tsx`, `src/test/setup.ts`.
- **ACs:** AC-9.5.
- **Test first:**
    - `gen:api:check` fails after a deliberate one-line edit to a generated file;
    - a sample test renders "Hello" through Testing Library.
- **Stop if:** `node -v` isn't 24.x (stop and report; don't work around it, user 2026-11-02); openapi-typescript 7.13
  can't read one of the four contracts (OpenAPI 3.1); or a D2 version doesn't exist.

**F2. Problem handling, session, routing shell, login and register.**

- **Allowed files:** `frontend/src/api/{client,middleware,problem}.ts`, `src/auth/**`, `src/app/**`, `src/ui/**`,
  `src/features/session/**`, `src/test/**`, `e2e/session.spec.ts`, and their tests.
- **ACs:** AC-1.1 to AC-1.7, AC-9.1, AC-9.2 (shell parts), AC-9.6 (session parts).
- **Test first:**
    - the AC-9.1 mapper table as a unit test;
    - the AC-1.5 outline;
    - the AC-1.7 outline;
    - a JWT decode test with a base64url payload containing `-` and `_`.
- **Stop if:** the gateway's 401 for an expired token can't be told apart from login's 401 `INVALID_CREDENTIALS` by path
  and code.

**Review point A:** the session security rows (AC-1.4 to AC-1.7, AC-9.6), the mapper, and drift checking.

- **Reached 2026-11-02:** F1 and F2 are done. Verified by the main session on Node 24.22.2:
    - lint 0, typecheck 0;
    - vitest 141/0/0, with `gen:api:check` passing;
    - build OK (113.84 KB gzip initial JS);
    - e2e 9/0/0;
    - no overrides, only approved packages.
- **Deviations, all accepted by pm:**
    - **RV-A1:** placeholder pages for `/orgs/new`, `/orgs/$orgId/projects` and `/projects/$projectId/features`,
      replaced in batches B and C. Why: routing and the 401 `next` flow need real targets now.
    - **RV-A2:** a 502, 503 or 504 with a non-Problem body maps to "unavailable", and an unknown `code` is handled by
      its status alone. Why: the status says more than "can't reach the server"; D7 already handles a Problem without
      `code` by status; and new server codes then degrade safely. AC-9.1's "non-Problem body" row applies to other
      statuses.
    - **RV-A3:** native labelled inputs in batch A, with react-aria-components in later batches. Why: native inputs meet
      D17. Later forms may switch without a change to the ACs.
- **User decisions (2026-11-02):**
    - ESLint 9.39.5's end-of-support warning is accepted (FU-F6);
    - `@types/node` is approved (D2, Q2);
    - batch B may start.

### Batch DS: design foundation (review point DS; before batch B, user 2026-11-03)

**F11. Design system, theme, app shell, and the batch A restyle (FE1).**

- **Install steps.** Each is a separate command, with exact versions verified against npm and reported in the handoff:
    1. `npm install -D --save-exact @types/node@24.x` (approved 2026-11-02; on its own, as the user asked);
    2. `npm install -D --save-exact tailwindcss@4.x @tailwindcss/vite@4.x`;
    3. only if QD1 approves them: `tailwind-merge` and `clsx`;
    4. per QD3: `lucide-react` (or no package, with hand-authored icons);
    5. per QD2: a font package only if approved.
- **Allowed files:**
    - **Config:** `frontend/package.json`, `package-lock.json`, `vite.config.ts` (the Tailwind plugin only),
      `index.html` (the DS-6 pre-paint script), `.gitignore` (add `review-screenshots/`), `playwright.config.ts` (
      additive projects or options for themes and widths), `eslint.config.js` (additive DS-12 rules).
    - **New:** `src/ui/**` (`theme.css`, `prefs.ts`, the DS-9 components, icons if hand-authored), `src/app/**` (Shell,
      TopBar, Sidebar, Drawer, routes; placeholders restyled), `e2e/design.spec.ts`, `e2e/screenshots.spec.ts`, and
      their tests.
    - **Restyled (FE1):** `src/features/session/**`, `src/app/pages/**`,
      `src/ui/{ErrorState,QueryView,EmptyState,PageHeading,TextField,ErrorSummary,AppLink}.tsx`, `src/main.tsx` (the
      stylesheet import).
    - **Deleted:** `src/ui/ui.module.css`, `src/app/shell.module.css`, `src/app/app.css`,
      `src/features/session/session.module.css`.
    - **Selector-only updates** in existing tests (`src/**/*.test.tsx`, `e2e/session.spec.ts`), each listed in the
      handoff with its reason.
- **Must not:**
    - change behaviour, routes, the API layer, `src/auth/**`, the Problem mapper or any user-visible text other than
      DS-11 casing;
    - remove or weaken an assertion;
    - add a package outside D2, Q2 and QD1–QD3;
    - build batch B–E screens.
- **ACs:** AC-10.1 to AC-10.6; AC-9.2 and AC-9.3 for batch A routes in both themes; all batch A ACs stay green.
- **Test first:**
    - AC-10.3, the contrast test, red before `theme.css` exists;
    - AC-10.1, the outline, including no flash;
    - AC-10.2 at desktop and mobile widths;
    - the DS-12 lint or grep check, red while the `.module.css` files exist.
- **Done when:**
    - vitest stays at 141 or more and e2e at 9 or more, all passing, plus the new tests;
    - axe shows 0 violations in both themes;
    - the screenshots exist (AC-10.5);
    - the build's initial JS stays within 250 KB gzip;
    - the handoff lists the screenshot folder for the user.
- **Stop if:**
    - a package outside the approved list is needed;
    - Tailwind 4 can't style a react-aria-components state without a plugin;
    - an existing test needs more than a selector change;
    - a DS-1 or DS-2 pair fails its contrast minimum (report it; pm changes the token).

**Review point DS:** the user reviews `frontend/review-screenshots/` (light and dark, desktop and mobile) and the shell
interactively. Batch B starts only after the user approves.
- **Reached 2026-11-09:** F11 is done, and the user approved the design from the screenshots. Batch B is approved and has started.
- **Verified by the main session:** lint, typecheck and build pass. Vitest was 276/1; the one failure is resolved by the user-approved fixture change for AC-1.4 below, which frontend-swe is applying. Per frontend-swe: e2e 24/0/0, axe 0 violations in both themes, 70 screenshots.
- **Installed:** `@types/node` 24.19.2; `tailwindcss` and `@tailwindcss/vite` 4.3.5; `lucide-react` 1.70.0.
- **Test changes approved by the user:**
  - AC-1.1: fixture handlers for the shell's requests;
  - AC-1.4: `{once: true}` on the 401 handler (fixture only);
  - AC-1.6: a selector change, because logout is now in the Account menu.
- **Deviations, all accepted by pm:**
  - **RV-DS1:** the node-environment tests are `.js`, because tsconfig wasn't in Allowed files. They may move to TypeScript when a task allows it.
  - **RV-DS2:** extra tokens, all covered by the contrast test.
  - **RV-DS3:** the dark-theme logo "C" uses `on-primary`.
  - **RV-DS4:** sidebar links to future pages show "not found" until batches C and D.
  - **RV-DS5:** the Button loading state doesn't use react-aria's `isPending`, because of an axe issue.
  - **RV-DS6:** the theme menu also appears on the auth pages.
  - **RV-DS7:** the role badge text has no parentheses.
  - **RV-DS8:** the screenshot sets differ between desktop and mobile.
  - **RV-DS9:** the remaining DS-9 components are built when first used, under each task's additive `src/ui/**` rule.
- **Code style (user, 2026-11-09):** the frontend uses 4-space indentation, kept from the user's IDE reformat. `gen:api` output is unchanged.

### Batch B: editor and tree (review point B)

**F3. Feature editor.**

- **Allowed files:** `frontend/src/features/editor/**`; `src/app/routes.tsx` (additive); `src/test/fixtures/**` (
  additive); `e2e/editor.spec.ts`; `src/ui/**` (additive); `e2e/screenshots.spec.ts` (additive).
- **UI:** the Detail page template with Tabs; a CodeMirror theme from the DS-1 tokens in both themes (Gherkin keywords
  `primary`, tags `info`, comments `text-muted`, diagnostics `danger`); the conflict dialog as Dialog; "Saved" as a
  Toast.
- **ACs:** AC-6.1 to AC-6.7, AC-9.4.
- **Test first:**
    - the AC-6.2 diagnostic at (4, 3);
    - the AC-6.3 column outline (unit);
    - AC-6.4: no automatic resend after a conflict.
- **Stop if:** SE1's answer isn't code points (then D12 changes; ask pm); or `@codemirror/lint` can't announce through a
  live region without a new package.

**F4. Feature tree, create, archive, restore.**

- **Allowed files:** `frontend/src/features/tree/**`; `src/app/routes.tsx` (additive); `src/test/fixtures/**` (
  additive); `e2e/tree.spec.ts`; `src/ui/**` (additive); `e2e/screenshots.spec.ts` (additive).
- **UI:** the DS-9 Tree; tag chips as toggle Badges; the Active/Archived switch as Tabs; archiving with a danger
  ConfirmDialog; empty and loading states per DS-8.
- **ACs:** AC-5.1 to AC-5.5.
- **Test first:**
    - the tree builder (ordering, nested folders, a path containing a `.` folder);
    - AC-5.5 rendering 2,000 features.
- **Stop if:** react-aria-components 1.22's `Tree` with `Virtualizer` can't do type-ahead or keyboard expansion as
  AC-5.5 needs.

**Review point B:** the editor's conflict and error handling, and the keyboard journey.

### Batch C: organizations, projects, history (review point C)

**F5. Organizations and invitations.**

- **Allowed files:** `frontend/src/features/{orgs,invitations}/**`, routes (additive), fixtures (additive),
  `e2e/orgs.spec.ts`, `src/ui/**` (additive), `e2e/screenshots.spec.ts` (additive).
- **UI:** members and invitations as Tables with Avatars; role changes via Select; the invite token in a Dialog with a
  Copy button and Toast; the accept page on the Auth template.
- **ACs:** AC-2.1 to AC-2.3, AC-3.1 to AC-3.5.
- **Test first:**
    - AC-3.2: the role menu limited by the ceiling;
    - AC-2.1: the fragment removed from the address bar;
    - AC-3.3: the token is gone from the DOM after the dialog closes.

**F6. Projects and project members.**

- **Allowed files:** `frontend/src/features/projects/**`, routes (additive), fixtures (additive),
  `e2e/projects.spec.ts`, `src/ui/**` (additive), `e2e/screenshots.spec.ts` (additive).
- **UI:** the project list as Cards or a Table; Settings as a 640 px form page; the archived banner as a warning Card;
  member changes via Table, Select and ConfirmDialog.
- **ACs:** AC-4.1 to AC-4.4.
- **Test first:** the AC-4.4 outline; the AC-4.2 archived banner after a 409.

**F7. History and test cases.**

- **Allowed files:** `frontend/src/features/history/**`, routes (additive), fixtures (additive), `src/ui/**` (additive),
  `e2e/screenshots.spec.ts` (additive).
- **UI:** the History and Test cases tabs of the Detail template; Tables; a read-only editor in F3's theme.
- **ACs:** AC-7.1, AC-7.2.
- **Test first:** AC-7.1's 404 version.

**Review point C.**

### Batch D: runs and results (review point D)

**F8. Runs, dashboard, results.**

- **Allowed files:** `frontend/src/features/runs/**`, routes (additive), fixtures (additive), `e2e/runs.spec.ts`,
  `src/ui/**` (additive), `e2e/screenshots.spec.ts` (additive).
- **UI:** a Table with sorting and Pagination; StatusChips (DS-2); summary numbers in Cards; echarts with DS-2 series
  colours and token axes and grids in both themes; the chart's table alternative as a Table.
- **ACs:** AC-8.1, AC-8.2, AC-8.7.
- **Test first:** AC-8.1's chart table equals the data; AC-8.7 polling stops when the tab is hidden.
- **Stop if:** echarts 6.1 can't render in jsdom for tests without a new package (then test the table and the options
  object only, and report).

**F9. Manual runs, recording, upload tokens.**

- **Allowed files:** `frontend/src/features/{runs,tokens}/**`, routes (additive), fixtures (additive),
  `e2e/manual-run.spec.ts`, `src/ui/**` (additive), `e2e/screenshots.spec.ts` (additive).
- **UI:** the planning form in a `lg` Dialog with a feature picker (Tree with Checkboxes); recording via segmented
  status Buttons plus a TextArea; the token shown once in a Dialog with Copy and a mono code block.
- **ACs:** AC-8.3 to AC-8.6.
- **Test first:** AC-8.6's outline; AC-8.5: the token is gone from the DOM after the dialog closes.

**Review point D.**

### Batch E: accessibility, end to end, docs (review point E)

**F10. Accessibility sweep, the full journey, README, manual run.**

- **Allowed files:** `frontend/e2e/**`, `frontend/README.md`; fixes inside `frontend/src/**` that only concern
  accessibility (each listed in the handoff).
- **UI:** a full screenshot set (every route, both themes, both widths) for the user; axe in both themes on every route.
- **ACs:** AC-9.2, AC-9.3, AC-9.4 (all routes); the Success journey in "Problem and goal", as a manual run against the
  real stack per standards §6, with throwaway secrets and the gateway on 8080.
- **Done when:** the axe report lists 0 violations per route, and the manual run's steps and results are in the handoff.

**Review point E**, then qa.

## Non-functional requirements

- **Security:** see D4, D14, AC-9.6. No third-party requests at runtime: no CDN fonts or analytics.
- **Performance (informational):** the initial JS of the login route ≤ 250 KB gzip, with the editor and echarts
  code-split; the tree of 2,000 features is interactive within 1 s on a laptop.
- **Browsers:** the latest two versions of Chrome, Firefox and Safari.

## Assumptions

- A1. The gateway's 0.3.4 routes and 401 Problems apply to every `/api` call (G1 resolved).
- A2. Contracts: auth-service 1.5.0, scenario-service 1.0.6 (1.0.7 after SE1), report-service 1.0.2, gateway 0.3.4.
- A3. One tab is one session (sessionStorage); a second tab needs its own login.

## Follow-ups

| ID    | Item                                                                                                                  | Owner                       | Priority |
|-------|-----------------------------------------------------------------------------------------------------------------------|-----------------------------|----------|
| FU-F1 | `GET /api/auth/me` (`fullName`, email)                                                                                | pm (auth-service)           | P2       |
| FU-F2 | A push channel (SSE) for runs                                                                                         | pm                          | P3       |
| FU-F3 | `listRuns` filters and trend aggregates                                                                               | pm (report-service phase 2) | P2       |
| FU-F4 | Refresh tokens and server-side logout                                                                                 | pm (auth-service)           | P2       |
| FU-F5 | Production hosting, CSP and CORS origins                                                                              | pm                          | P2       |
| FU-F6 | Move to ESLint 10 once eslint-plugin-jsx-a11y supports it (9.39.5's end-of-support warning accepted, user 2026-11-02) | frontend-swe                | P2       |

## Acceptance

Pending.
