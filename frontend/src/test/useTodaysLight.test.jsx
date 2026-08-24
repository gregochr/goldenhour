import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import useTodaysLight from '../hooks/useTodaysLight.js';

vi.mock('../api/lightApi.js', () => ({ getTodaysLight: vi.fn() }));
import { getTodaysLight } from '../api/lightApi.js';

// Partial mock: the hook reads only `ukDateStr`, and the rest of mapDates must keep working for
// anything it pulls in transitively. Stubbing the clock rather than the wall time keeps this off
// fake timers, which `waitFor` does not survive.
vi.mock('../utils/mapDates.js', async (importOriginal) => ({
  ...(await importOriginal()),
  ukDateStr: vi.fn(() => '2026-08-19'),
}));
import { ukDateStr } from '../utils/mapDates.js';

/**
 * The hook that resolves today's light for the masthead.
 *
 * <p>What is pinned here is the set of rules that make the band honest and cheap: the three states
 * stay distinguishable, saving a postcode lights the rule without a reload, and a failed request
 * degrades to the nudge rather than to a masthead reporting a backend problem.
 */

/** Renders the hook's value as text, so a state change is observable without an extra library. */
function Probe({ settingsVersion = 0 }) {
  const light = useTodaysLight(settingsVersion);
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

  describe('the day turning over', () => {
    // This is a planning dashboard and a tab left open from one evening to the next morning is an
    // ordinary way to use it. A mount-only fetch left yesterday's gradient and yesterday's clock
    // times on screen — and nothing on the band carries a date, so there was no way to tell.

    beforeEach(() => ukDateStr.mockReturnValue('2026-08-19'));

    it('refetches when the tab returns on a later UK day', async () => {
      getTodaysLight.mockResolvedValue(LIGHT);
      render(<Probe />);
      await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(1));

      ukDateStr.mockReturnValue('2026-08-20');
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });

      await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(2));
    });

    it('does not refetch when the tab returns on the same day', async () => {
      // The guard is what keeps this off a poll. Every tab switch would otherwise be a request for
      // data that changes once a day, which is what the design explicitly ruled out.
      getTodaysLight.mockResolvedValue(LIGHT);
      render(<Probe />);
      await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(1));

      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      await act(async () => { window.dispatchEvent(new Event('focus')); });

      expect(getTodaysLight).toHaveBeenCalledTimes(1);
    });

    it('ignores a day change while the tab is hidden, and acts on it when it is shown', async () => {
      // `visibilitychange` fires on the way OUT as well as the way in. Refetching then would spend
      // a request on a tab nobody is looking at, and the answer could be stale again by the time
      // they return.
      getTodaysLight.mockResolvedValue(LIGHT);
      render(<Probe />);
      await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(1));

      ukDateStr.mockReturnValue('2026-08-20');
      const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      expect(getTodaysLight).toHaveBeenCalledTimes(1);

      visibility.mockReturnValue('visible');
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(2));
      visibility.mockRestore();
    });
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
