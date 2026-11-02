import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ErrorState } from './ErrorState';
import { networkError, toUiError } from '../api/problem';

const json = 'application/problem+json';
const ui = (status: number, code?: string, requestId?: string) =>
  toUiError({
    status,
    body: { type: 'about:blank', title: 'T', status, detail: 'Server detail', instance: '/x', ...(code ? { code } : {}), ...(requestId ? { requestId } : {}) },
    contentType: json,
  });

// @US-9 @AC-9.1: what the UI shows for each mapped Problem.
describe('AC-9.1 ErrorState', () => {
  it.each([
    [ui(403, 'FORBIDDEN'), "You don't have access to this"],
    [ui(403), 'The request was refused'],
    [ui(409, 'CONFLICT'), 'Someone else is changing this. Try again.'],
    [ui(400, 'MALFORMED_REQUEST'), 'The request could not be read.'],
    [ui(503), 'The service is unavailable. Try again.'],
    [ui(500, 'INTERNAL_ERROR'), 'Something went wrong'],
    [networkError(), "Can't reach the server"],
    [ui(409, 'PROJECT_ARCHIVED'), 'Project archived'],
  ])('shows the text for %#', (error, text) => {
    render(<ErrorState error={error} />);
    expect(screen.getByText(text, { exact: false })).toBeTruthy();
  });

  it.each([
    [ui(409, 'CONFLICT')],
    [ui(502)],
    [ui(503, 'SERVICE_UNAVAILABLE')],
    [ui(504)],
    [networkError()],
  ])('offers Retry for retryable errors %#', (error) => {
    const onRetry = vi.fn();
    render(<ErrorState error={error} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('offers no Retry for a 500', () => {
    render(<ErrorState error={ui(500, 'INTERNAL_ERROR')} onRetry={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('shows "Reference: <requestId>" when present, also without code', () => {
    render(<ErrorState error={ui(503, undefined, 'rid-503')} />);
    expect(screen.getByText('Reference: rid-503')).toBeTruthy();
  });

  it('shows the not-found state with a link back', () => {
    render(<ErrorState error={ui(404, 'NOT_FOUND')} back={{ href: '/orgs', label: 'Back to organizations' }} />);
    expect(screen.getByText("This doesn't exist")).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Back to organizations' }).getAttribute('href')).toBe('/orgs');
  });

  it('shows the server detail only for a code without UI text', () => {
    render(<ErrorState error={ui(409, 'SEAT_LIMIT_REACHED')} />);
    expect(screen.getByText('Server detail')).toBeTruthy();
  });

  it('is announced (role alert)', () => {
    render(<ErrorState error={ui(500, 'INTERNAL_ERROR')} />);
    expect(screen.getByRole('alert')).toBeTruthy();
  });
});
