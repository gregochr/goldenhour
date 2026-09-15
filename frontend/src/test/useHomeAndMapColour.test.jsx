/**
 * `useHomeAndMapColour` — `App`'s own read of the caller's settings: the home's coordinates for the
 * map's HOME marker, reach rings and ⌂ control, and the map-colour preference for the ramp.
 *
 * <p>It used to be asked on mount and again on every close of the settings dialog, unguarded, so an
 * older answer landing last could leave the marker and rings on the old home beside the tick line's
 * new one (the Plan provider's reads are guarded; this one was not). It now asks again only on a
 * home save or a colour save, and drops any answer a newer save has superseded, wherever it lands.
 * The same two places the effect cleanup parts company with a request-number guard are pinned here
 * as they are for the provider: a superseded answer landing FIRST, and one landing after the newest
 * request FAILED.
 *
 * <p>A probe renders the hook's three values, as `useTodaysLight.test.jsx` does for its hook; the
 * ramp itself is `scoreRamp`'s live module state, read through `getMode()` and reset per test.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useHomeAndMapColour from '../hooks/useHomeAndMapColour.js';
import { DEFAULT_MODE, getMode, setMode } from '../utils/scoreRamp.js';

vi.mock('../api/settingsApi.js', () => ({ getSettings: vi.fn() }));
import { getSettings } from '../api/settingsApi.js';

/** `GET /api/user/settings` for one reader before and after moving house, with a colour chosen. */
const SETTINGS_MORPETH = { homeLatitude: 55.17, homeLongitude: -1.69, mapColourScale: 'verdict' };
const SETTINGS_KESWICK = { homeLatitude: 54.6, homeLongitude: -3.13, mapColourScale: 'verdict' };

/** A request the test settles by hand, so the test — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, so the hook's `.then` has run and committed
 * before the test reads anything — an answer not on screen afterwards was dropped, not still on its
 * way. Every positive control settles through this same helper (frontend test standards, "A late
 * response is only 'dropped' once it has landed").
 */
async function land(settle) {
  await act(async () => { settle(); });
}

function Probe({ homeVersion, colourVersion }) {
  const { homeCoords, mapColourScale, colourScaleDefaulted } = useHomeAndMapColour(
    homeVersion, colourVersion,
  );
  return (
    <div>
      <span data-testid="home">{homeCoords ? `${homeCoords.lat},${homeCoords.lon}` : 'none'}</span>
      <span data-testid="scale">{mapColourScale}</span>
      <span data-testid="defaulted">{String(colourScaleDefaulted)}</span>
    </div>
  );
}

/** Mounts the probe at two counter values and lets any request answered up front land. */
async function mountAt(homeVersion, colourVersion = 0) {
  let result;
  await act(async () => {
    result = render(<Probe homeVersion={homeVersion} colourVersion={colourVersion} />);
  });
  return result;
}

/** A save in the settings dialog: `App` moves one of the two counters, without remounting. */
async function saved(result, homeVersion, colourVersion = 0) {
  await act(async () => {
    result.rerender(<Probe homeVersion={homeVersion} colourVersion={colourVersion} />);
  });
}

const home = () => screen.getByTestId('home').textContent;
const coordsOf = (s) => `${s.homeLatitude},${s.homeLongitude}`;

beforeEach(() => {
  // Reset, not cleared: a `mockReturnValueOnce` queue survives `vi.clearAllMocks()`.
  getSettings.mockReset();
  setMode(DEFAULT_MODE);
});

afterEach(() => {
  // The ramp is module state shared by every test in the file.
  setMode(DEFAULT_MODE);
});

describe('useHomeAndMapColour — the home the map draws', () => {
  it('drops the mount\'s answer when it lands after a home save\'s — the marker stays on the new home', async () => {
    // The race the Plan provider's reads were already guarded against, in App's own read: the
    // reader moves house while the mount's request is slow, and it lands last.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0);
    await saved(result, 1); // moved from Morpeth to Keswick
    // Both requests really are out at once — without that there is no race to lose.
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.resolve(SETTINGS_KESWICK));
    // Control: the newest answer is applied.
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));

    // Still Keswick. Broken, the marker and rings went back to Morpeth beside a tick line naming
    // Keswick — two homes on one screen.
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('drops a superseded answer even when it lands FIRST — it answers a question the save has changed', async () => {
    // Where the cleanup parts company with a request-number guard, which would draw the old home
    // for as long as the newest request took.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0);
    await saved(result, 1); // moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));
    // Nothing applied. Broken, the marker sat on Morpeth until Keswick's answer arrived.
    expect(home()).toBe('none');

    // Control, settled through the same helper: the newest answer still lands and applies.
    await land(() => moveAnswer.resolve(SETTINGS_KESWICK));
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('does not let a superseded answer stand in when the newest request fails', async () => {
    // The other place the two rules part company: with the newest request failed, a number guard
    // would take the superseded answer landing after it — the home the reader has just left.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    const nextSave = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(nextSave.promise);

    const result = await mountAt(0);
    await saved(result, 1); // moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings')));
    expect(home()).toBe('none');

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));

    // Still nothing. Broken, the marker sat on the house the reader had just moved away from.
    expect(home()).toBe('none');

    // Control, settled through the same helper: the next save's answer lands and applies.
    await saved(result, 2);
    await land(() => nextSave.resolve(SETTINGS_KESWICK));
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('does not ask again on a re-render that moves neither counter', async () => {
    // Every close of the dialog used to ask again; the counters are now the only trigger, so an
    // ordinary re-render — a health tick, a poll — must not.
    getSettings.mockResolvedValue(SETTINGS_MORPETH);
    const result = await mountAt(3, 2);

    await saved(result, 3, 2);

    expect(getSettings).toHaveBeenCalledTimes(1);
    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));
  });
});

describe('useHomeAndMapColour — the colour the ramp paints with', () => {
  it('asks again on a colour save and hands the new choice to the ramp', async () => {
    // The path the dialog's colour radios depend on: they no longer rely on a read at the close.
    const colourAnswer = deferred();
    getSettings
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, mapColourScale: 'temp' })
      .mockReturnValueOnce(colourAnswer.promise);

    const result = await mountAt(0, 0);
    expect(getMode()).toBe('temp');

    await saved(result, 0, 1); // the reader chose the verdict scale
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => colourAnswer.resolve({ ...SETTINGS_MORPETH, mapColourScale: 'verdict' }));

    expect(getMode()).toBe('verdict');
    expect(screen.getByTestId('scale')).toHaveTextContent('verdict');
  });

  it('drops a superseded colour answer — two quick choices end on the second', async () => {
    // A reader flicking between the two scales: the first choice's read, landing last, must not
    // put the first scale back on every heat surface.
    const firstChoice = deferred();
    const secondChoice = deferred();
    getSettings
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, mapColourScale: 'temp' })
      .mockReturnValueOnce(firstChoice.promise)
      .mockReturnValueOnce(secondChoice.promise);

    const result = await mountAt(0, 0);
    await saved(result, 0, 1); // chose verdict
    await saved(result, 0, 2); // then temp again
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => secondChoice.resolve({ ...SETTINGS_MORPETH, mapColourScale: 'temp' }));
    // Control: the newest answer is applied.
    expect(getMode()).toBe('temp');

    await land(() => firstChoice.resolve({ ...SETTINGS_MORPETH, mapColourScale: 'verdict' }));

    // Still temp. Broken, the ramp went back to verdict — the choice the reader had just undone.
    expect(getMode()).toBe('temp');
    expect(screen.getByTestId('scale')).toHaveTextContent('temp');
  });
});
