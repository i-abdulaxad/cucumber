import {defineConfig, devices} from '@playwright/test';

// D16: journeys run against `vite preview` with page.route fixtures; no backend is started.
const port = 4173;

export default defineConfig({
    testDir: './e2e',
    fullyParallel: true,
    forbidOnly: true,
    retries: 0,
    reporter: 'list',
    use: {
        baseURL: `http://127.0.0.1:${port}`,
        trace: 'retain-on-failure',
    },
    projects: [
        {name: 'chromium', use: {...devices['Desktop Chrome']}, testIgnore: /screenshots\.spec\.ts/},
        // AC-10.5: "npm run screenshots" writes review-screenshots/ (light and dark, 1440 and 390 wide).
        {name: 'screenshots', use: {...devices['Desktop Chrome']}, testMatch: /screenshots\.spec\.ts/},
    ],
    webServer: {
        command: `npm run build && npx vite preview --host 127.0.0.1 --port ${port} --strictPort`,
        url: `http://127.0.0.1:${port}`,
        reuseExistingServer: false,
        timeout: 120_000,
    },
});
