import { setupServer } from 'msw/node';

// Every test declares its own handlers; unhandled requests fail the test.
export const server = setupServer();

export const problemHeaders = { 'Content-Type': 'application/problem+json' } as const;
