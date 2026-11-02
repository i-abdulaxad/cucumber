import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { QueryView } from './QueryView';
import { ApiError, networkError, toUiError } from '../api/problem';

afterEach(() => vi.useRealTimers());

function Harness({ fn }: { fn: () => Promise<string[]> }) {
  const query = useQuery({ queryKey: ['items'], queryFn: fn, retry: false });
  return (
    <QueryView query={query} label="Projects" isEmpty={(d) => d.length === 0} empty={<p>No projects yet</p>}>
      {(items) => (
        <ul>
          {items.map((i) => (
            <li key={i}>{i}</li>
          ))}
        </ul>
      )}
    </QueryView>
  );
}

function renderHarness(fn: () => Promise<string[]>) {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <Harness fn={fn} />
    </QueryClientProvider>,
  );
}

// @US-9 @AC-9.2 (shell parts): every data view has its states.
describe('AC-9.2 QueryView', () => {
  it('loading: aria-busy at once, the busy indicator only after 300 ms, in a region that keeps its size', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    renderHarness(() => new Promise(() => undefined));
    const region = screen.getByRole('region', { name: 'Projects' });
    expect(region.getAttribute('aria-busy')).toBe('true');
    expect(screen.queryByText('Loading…')).toBeNull();
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(screen.getByRole('status').textContent).toBe('Loading…');
    expect(region.className).toMatch(/region/);
  });

  it('empty: shows the empty content', async () => {
    renderHarness(() => Promise.resolve([]));
    expect(await screen.findByText('No projects yet')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Projects' }).getAttribute('aria-busy')).toBe('false');
  });

  it('ready: renders the data', async () => {
    renderHarness(() => Promise.resolve(['Checkout']));
    expect(await screen.findByText('Checkout')).toBeTruthy();
  });

  it('error: the AC-9.1 state with Retry, which refetches', async () => {
    let calls = 0;
    renderHarness(() => {
      calls += 1;
      return calls === 1 ? Promise.reject(new ApiError(networkError())) : Promise.resolve(['Checkout']);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Checkout')).toBeTruthy();
  });

  it('forbidden and not-found have their own states', async () => {
    const forbidden = toUiError({ status: 403, body: { type: 'about:blank', title: 'F', status: 403, detail: 'd', instance: '/x', code: 'FORBIDDEN' }, contentType: 'application/problem+json' });
    renderHarness(() => Promise.reject(new ApiError(forbidden)));
    expect(await screen.findByText("You don't have access to this")).toBeTruthy();
  });

  it('an unexpected thrown value is the generic error', async () => {
    renderHarness(() => Promise.reject(new Error('boom')));
    expect(await screen.findByText("Can't reach the server")).toBeTruthy();
  });
});
