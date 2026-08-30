import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PlanErrorBoundary from '../components/PlanErrorBoundary.jsx';

/**
 * A boundary can only be tested by throwing at it, and React routes every caught error to
 * `console.error` regardless of the boundary. The spy is scoped to this file and restored after
 * each test rather than installed in `setup.js`, which would hide genuine PropTypes failures and
 * React warnings across all other test files.
 */
const silenceReactErrorLog = () => vi.spyOn(console, 'error').mockImplementation(() => {});

function Boom({ message = 'the plan fell' }) {
  throw new Error(message);
}

const Fine = () => <p data-testid="healthy-subtree">the subtree rendered</p>;

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

describe('PlanErrorBoundary', () => {
  // The negative baseline. Without it, every assertion below would still pass for a boundary that
  // renders its fallback unconditionally.
  it('renders the subtree it guards when nothing throws', () => {
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Fine />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('healthy-subtree')).toBeInTheDocument();
    expect(screen.queryByTestId('plan-error')).toBeNull();
  });

  it('shows the failure rather than an empty page', () => {
    const spy = silenceReactErrorLog();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Boom message="tide rollup exploded" />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('plan-error')).toBeInTheDocument();
    // The message, specifically — a fallback that swallowed it would still pass a presence check,
    // and the message is the only thing a bug report can quote.
    expect(screen.getByTestId('plan-error-detail')).toHaveTextContent('tide rollup exploded');
    // By CONTENT, not `toHaveBeenCalled()`. React logs every caught error itself, so a bare
    // called-check is satisfied by React's own log and stays green with `componentDidCatch` deleted
    // — which is the half that carries `info.componentStack`, the one thing a crash report needs and
    // the one thing the on-screen panel deliberately does not show.
    expect(spy.mock.calls.some(([first]) => first === 'Plan crashed:')).toBe(true);
  });

  // The decision this pins: the boundary must not act on its own. Catching has already closed the
  // reload trap, and an automatic clear-and-reload or sign-out would make a real crash
  // indistinguishable from a mis-click.
  it('never acts on its own', () => {
    silenceReactErrorLog();
    const onSignOut = vi.fn();
    const reload = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={onSignOut} reload={reload}>
        <Boom />
      </PlanErrorBoundary>,
    );
    expect(screen.getByTestId('plan-error')).toBeInTheDocument();
    expect(onSignOut).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });

  // By role and accessible name, not by testid: a testid keeps passing while the words rot.
  it('signs out on request, and takes no other action', () => {
    silenceReactErrorLog();
    const onSignOut = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={onSignOut}>
        <Boom />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  // The clear-and-reload action, by name: it removes the persisted SELECTIONS that would otherwise
  // re-select the same broken render path on reload, leaves the auth token alone, and reloads.
  it('clears the plan caches and selections, leaves the reader signed in, and reloads', async () => {
    silenceReactErrorLog();
    localStorage.setItem('photocast_swr:v2:briefing', '{"stale":true}');
    localStorage.setItem('photocast.planReach', JSON.stringify({ reach: '45', reachDay: '2026-08-24' }));
    localStorage.setItem('photocast.planRating', JSON.stringify({ rating: 'any' }));
    sessionStorage.setItem('photocast.planDoors', JSON.stringify(['regional']));
    localStorage.setItem('goldenhour_token', 'still-here');

    const reload = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()} reload={reload}>
        <Boom />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Clear cached data and reload' }));
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));

    expect(localStorage.getItem('photocast_swr:v2:briefing')).toBeNull();
    expect(localStorage.getItem('photocast.planReach')).toBeNull();
    expect(localStorage.getItem('photocast.planRating')).toBeNull();
    expect(sessionStorage.getItem('photocast.planDoors')).toBeNull();
    expect(localStorage.getItem('goldenhour_token')).toBe('still-here');
  });

  // This is the fix for the defect a stale post-deploy reload exposed: the PWA service worker (and
  // the Cache Storage it owns) — not just the app's own localStorage/sessionStorage — decides what
  // a reload serves. Without unregistering it, "Clear cached data and reload" reloads straight back
  // into the same pre-deploy shell the worker precached and crashes again on the exact same
  // now-404ing lazy chunk. A bare `toHaveBeenCalled()` on `reload` alone cannot tell this apart from
  // that regression, since the button still reloads either way — only the service worker teardown
  // proves the fix is doing anything.
  it('unregisters the service worker and empties Cache Storage before reloading', async () => {
    silenceReactErrorLog();
    const registrations = [{ unregister: vi.fn().mockResolvedValue(true) }, { unregister: vi.fn().mockResolvedValue(true) }];
    // Object.create(navigator, ...), not a spread: navigator's own properties are prototype
    // getters, so `{...navigator}` silently drops everything (userAgent included) that
    // testing-library/jsdom still read during this render. Delegating via the prototype chain
    // keeps the real navigator intact and only overrides serviceWorker.
    vi.stubGlobal(
      'navigator',
      Object.create(navigator, {
        serviceWorker: { value: { getRegistrations: vi.fn().mockResolvedValue(registrations) }, configurable: true },
      }),
    );
    const cacheDelete = vi.fn().mockResolvedValue(true);
    vi.stubGlobal('caches', { keys: vi.fn().mockResolvedValue(['workbox-precache-v2', 'photocast-assets']), delete: cacheDelete });

    const reload = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()} reload={reload}>
        <Boom />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Clear cached data and reload' }));

    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    registrations.forEach((registration) => expect(registration.unregister).toHaveBeenCalledTimes(1));
    expect(cacheDelete).toHaveBeenCalledWith('workbox-precache-v2');
    expect(cacheDelete).toHaveBeenCalledWith('photocast-assets');
    vi.unstubAllGlobals();
  });

  // The service worker / Cache Storage APIs are absent in most test environments and in browsers
  // that disable them — this pins that their absence is a no-op, not a thrown error that would
  // abort the reload the button exists to guarantee.
  it('still reloads when the service worker and Cache Storage APIs are unavailable', async () => {
    silenceReactErrorLog();
    const reload = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()} reload={reload}>
        <Boom />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Clear cached data and reload' }));
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });

  // The subtree that died normally supplies the page's only <h1>. A fallback without one leaves the
  // signed-in app with no heading at all.
  it('keeps the page heading when the subtree that owned it is gone', () => {
    silenceReactErrorLog();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Boom />
      </PlanErrorBoundary>,
    );
    expect(screen.getByRole('heading', { level: 1, name: /photocast/i })).toBeInTheDocument();
  });

  it('announces the failure to assistive tech', () => {
    silenceReactErrorLog();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Boom />
      </PlanErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  // Without this, a crash orphans focus at <body>: the entire subtree that held it just unmounted,
  // and nothing moves focus to the two controls that are now the whole app's only function.
  it('moves focus to the fallback heading on crash, so a keyboard/AT reader is not left at <body>', () => {
    silenceReactErrorLog();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()}>
        <Boom />
      </PlanErrorBoundary>,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'The Plan stopped working' })).toHaveFocus();
  });

  // A storage-denied browser (Safari "Block all cookies", an enterprise site-data policy) throws
  // SecurityError on bare localStorage/sessionStorage access. The clear-and-reload button is the
  // reader's only remaining control at this point, so it must still reload even when clearing the
  // lens keys is impossible — an unguarded throw here would leave the button doing nothing, forever.
  it('still reloads when storage access throws', async () => {
    silenceReactErrorLog();
    const removeItemSpy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    const reload = vi.fn();
    render(
      <PlanErrorBoundary onSignOut={vi.fn()} reload={reload}>
        <Boom />
      </PlanErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Clear cached data and reload' }));
    await vi.waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    removeItemSpy.mockRestore();
  });
});
