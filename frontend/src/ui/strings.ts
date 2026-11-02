// Shared UI text (English only; one module per area).
export const uiStrings = {
  loading: 'Loading…',
  retry: 'Retry',
  reference: (requestId: string) => `Reference: ${requestId}`,
  summaryTitle: 'There is a problem',
  backToStart: 'Back to start',
  errors: {
    unauthenticated: 'Your session ended. Log in again.',
    forbidden: "You don't have access to this",
    refused: 'The request was refused',
    'not-found': "This doesn't exist",
    archived: 'Project archived. It is read only.',
    'lock-conflict': 'Someone else is changing this. Try again.',
    validation: 'Some fields need attention.',
    malformed: 'The request could not be read.',
    unavailable: 'The service is unavailable. Try again.',
    server: 'Something went wrong',
    network: "Can't reach the server",
    specific: 'The request could not be completed.',
  },
} as const;
