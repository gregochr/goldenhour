import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import useTodaysLight from '../hooks/useTodaysLight.js';

vi.mock('../api/lightApi.js', () => ({ getTodaysLight: vi.fn() }));
import { getTodaysLight } from '../api/lightApi.js';

/**
 * The hook that resolves today's light for the masthead.
 *
 * <p>What is pinned here is the set of rules that make the band honest and cheap: the three states
 * stay distinguishable, the v1 arm pays nothing for a band it does not render, saving a postcode
 * lights the rule without a reload, and a failed request degrades to the nudge rather than to a
 * masthead reporting a backend problem.
 */

/** Renders the hook's value as text, so a state change is observable without an extra library. */
function Probe({ enabled = true, settingsVersion = 0 }) {
  const light = useTodaysLight(enabled, settingsVersion);
  return (
    <span data-testid="state">
      {light === undefined ? 'unresolved' : (light?.label ?? 'no-home')}
    </span>
  );
}

const LIGHT = { label: 'Home · NE66 1NG', stops: [] };

const state = () => screen.getByTestId('state').textContent;

beforeEach(() => vi.clearAllMocks());

describe('useTodaysLight', () => {
  it('starts unresolved, so the nudge never flashes at a reader who has a postcode', () => {
    // The distinction the whole three-state contract exists for. If this collapsed to null, every
    // load would paint "set a postcode" for one frame before replacing it with the times.
    getTodaysLight.mockReturnValue(new Promise(() => {}));
    render(<Probe />);

    expect(state()).toBe('unresolved');
  });

  it('resolves to the day when a home is saved', async () => {
    getTodaysLight.mockResolvedValue(LIGHT);
    render(<Probe />);

    await waitFor(() => expect(state()).toBe('Home · NE66 1NG'));
  });

  it('resolves to null — not to unresolved — when there is no home saved', async () => {
    // A 204 is the empty state, and the masthead has a designed answer for it.
    getTodaysLight.mockResolvedValue(null);
    render(<Probe />);

    await waitFor(() => expect(state()).toBe('no-home'));
  });

  it('degrades a failed request to no-answer, NOT to "you have no postcode"', async () => {
    // The distinction that separates a fact about the server from a claim about the reader. `null`
    // renders "Set your home postcode…", which a 502 or a dropped connection is no evidence for;
    // `undefined` renders the unlit rule and a blank row, claiming nothing. The masthead is chrome
    // and must not report a backend problem — but it must not invent an account state either.
    getTodaysLight.mockRejectedValue(new Error('network'));
    render(<Probe />);

    // Waiting on the call rather than on the value, because the value is unchanged by the failure —
    // a `waitFor` on `state()` would be satisfied by the initial render, before the request settled.
    await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(1));
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 0); }); });

    expect(state()).toBe('unresolved');
  });

  it('drops back to no-answer when a refetch fails after a good one', async () => {
    // The arm of the rule above that the initial state cannot fake: a reader whose light resolved,
    // then saved a postcode while the backend was down, must not be told they have no postcode.
    getTodaysLight.mockResolvedValueOnce(LIGHT);
    const { rerender } = render(<Probe settingsVersion={0} />);
    await waitFor(() => expect(state()).toBe('Home · NE66 1NG'));

    getTodaysLight.mockRejectedValueOnce(new Error('network'));
    rerender(<Probe settingsVersion={1} />);

    await waitFor(() => expect(state()).toBe('unresolved'));
  });

  it('asks for nothing when the arm does not render a band', async () => {
    // A hook cannot be called conditionally, so the flag comes in as an argument. Without this,
    // every v1 reader would pay for a request whose answer nothing draws.
    render(<Probe enabled={false} />);

    await waitFor(() => expect(state()).toBe('unresolved'));
    expect(getTodaysLight).not.toHaveBeenCalled();
  });

  it('refetches when the home settings change, so a new postcode lights the rule', async () => {
    // The counter is the one App already bumps when the settings modal closes. Without this the
    // reader would follow the nudge, save a postcode, and watch the rule stay dim until a reload.
    getTodaysLight.mockResolvedValue(null);
    const { rerender } = render(<Probe settingsVersion={0} />);
    await waitFor(() => expect(state()).toBe('no-home'));

    getTodaysLight.mockResolvedValue(LIGHT);
    rerender(<Probe settingsVersion={1} />);

    await waitFor(() => expect(state()).toBe('Home · NE66 1NG'));
    expect(getTodaysLight).toHaveBeenCalledTimes(2);
  });

  it('does not refetch on an unrelated re-render', async () => {
    // Sun times change once a day. The band is not a poll, and a request per render of App would
    // be one of the busiest calls in the app for data that cannot have moved.
    getTodaysLight.mockResolvedValue(LIGHT);
    const { rerender } = render(<Probe settingsVersion={3} />);
    await waitFor(() => expect(state()).toBe('Home · NE66 1NG'));

    rerender(<Probe settingsVersion={3} />);
    rerender(<Probe settingsVersion={3} />);

    expect(getTodaysLight).toHaveBeenCalledTimes(1);
  });

  it('lets the newest request win when an older one lands late', async () => {
    // The `live` guard's real job. Saving a postcode bumps the version while the first request may
    // still be open, and the two race: without the guard the stale answer overwrites the fresh one
    // and the band shows the light of a home the reader has just changed.
    //
    // Deliberately NOT written as an unmount test. React has not warned on a setState into an
    // unmounted tree since 18, so that version passes with the guard deleted — it looks like
    // coverage and pins nothing.
    let settleFirst;
    getTodaysLight.mockReturnValueOnce(new Promise((resolve) => { settleFirst = resolve; }));
    const { rerender } = render(<Probe settingsVersion={0} />);
    expect(state()).toBe('unresolved');

    getTodaysLight.mockResolvedValueOnce(LIGHT);
    rerender(<Probe settingsVersion={1} />);
    await waitFor(() => expect(state()).toBe('Home · NE66 1NG'));

    // Flushed inside `act` and through a macrotask, so the stale `.catch().then()` chain has
    // genuinely run by the time this asserts. Two bare microtask hops were not enough — the
    // assertion passed with the guard deleted, which is exactly the shape of test this file's
    // standards call worse than none.
    settleFirst({ label: 'Home · OLD POSTCODE', stops: [] });
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 0); }); });

    expect(state()).toBe('Home · NE66 1NG');
  });

});
