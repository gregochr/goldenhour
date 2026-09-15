/**
 * `useHomeAndMapColour` — `App`'s own read of the caller's settings: the home's coordinates for the
 * map's HOME marker, reach rings and ⌂ control, and the map-colour preference for the ramp.
 *
 * <p>It used to be asked on mount and again on every close of the settings dialog, unguarded, so an
 * older answer landing last could leave the marker and rings on the old home beside the tick line's
 * new one (the Plan provider's reads are guarded; this one was not). It now asks again only on a
 * home save or a colour save, and drops any answer a newer save has superseded, wherever it lands.
 * The places the effect cleanup parts company with a request-number guard are pinned here as they
 * are for the provider: a superseded answer landing FIRST, one landing after the newest request
 * FAILED, and — now that the `.catch` writes — a superseded failure landing first.
 *
 * <p>A failed read after a home save empties the home back to unknown (an owner decision,
 * 2026-09-15) — and `undefined` must stay distinct from `null` all the way down, because the map's
 * ⌂ control answers `null` with a prompt to set a postcode.
 *
 * <p>A probe renders the hook's three values, as `useTodaysLight.test.jsx` does for its hook, with
 * the home's three states told apart; the ramp itself is `scoreRamp`'s live module state, read
 * through `getMode()` and reset per test.
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
/** The same reader before saving any postcode — the server's own "no home". */
const SETTINGS_NO_HOME = { homeLatitude: null, homeLongitude: null, mapColourScale: 'verdict' };

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

/** The home's three states, as the probe prints them: not known, no postcode, or the home. */
const homeState = (coords) => {
  if (coords === undefined) return 'unknown';
  if (coords === null) return 'none';
  return `${coords.lat},${coords.lon}`;
};

function Probe({ homeVersion, colourVersion }) {
  const { homeCoords, mapColourScale, colourScaleDefaulted } = useHomeAndMapColour(
    homeVersion, colourVersion,
  );
  return (
    <div>
      <span data-testid="home">{homeState(homeCoords)}</span>
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
    expect(home()).toBe('unknown');

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
    expect(home()).toBe('unknown');

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));

    // Still not known. Broken, the marker sat on the house the reader had just moved away from.
    expect(home()).toBe('unknown');

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

describe('useHomeAndMapColour — not known is not "no postcode", and a failed read says so', () => {
  it('is not known until the first answer lands — and "no postcode" only when the server says so', async () => {
    const mountAnswer = deferred();
    getSettings.mockReturnValueOnce(mountAnswer.promise);

    await mountAt(0);
    // Not `none`: the ⌂ answers that with "Set your home postcode", which it used to say to every
    // reader for the length of every page load.
    expect(home()).toBe('unknown');

    await land(() => mountAnswer.resolve(SETTINGS_NO_HOME));
    expect(home()).toBe('none');
  });

  it('empties the home when the read after a home save fails — the marker does not stay on the old house', async () => {
    // A failed read is no evidence the home is where it was: the save may have moved it. An owner
    // decision, 2026-09-15 — it used to keep the answer from before.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));
    await saved(result, 1); // moved to Keswick
    // Nothing is cleared when the counter moves: until the read settles, Morpeth stands.
    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings')));

    // Not known — and not `none`, which would have the ⌂ ask for a postcode. It used to keep
    // Morpeth: the marker and rings on the house the reader had just left.
    expect(home()).toBe('unknown');

    // Control, settled through the same helper: the next save's answer draws the home again.
    await saved(result, 2); // recalculated the drive times
    await land(() => recalcAnswer.resolve(SETTINGS_KESWICK));
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('keeps the home the newest read drew when a superseded read FAILS after it', async () => {
    // The guard on the `.catch`, load-bearing now that it writes.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0);
    await saved(result, 1); // moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.resolve(SETTINGS_KESWICK));
    // Control: the newest answer is applied.
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));

    await land(() => mountAnswer.reject(new Error('Network Error')));

    // Still Keswick. Unguarded, the superseded failure emptied it — marker, rings and ⌂ gone.
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('keeps the home on screen when a superseded read FAILS first — only the newest failure empties', async () => {
    // Where the cleanup and a request-number guard part company for the `.catch`: with nothing
    // newer applied, a number guard would let the move's failure empty the home while the
    // recalculation's read, the newest, is still out.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    await saved(result, 1); // moved to Keswick
    await saved(result, 2); // recalculated the drive times from it
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => moveAnswer.reject(new Error('Network Error')));

    // Unchanged. Broken, the home went to unknown while the newest read was on its way.
    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));

    // Control, settled through the same helper: the newest answer lands and applies.
    await land(() => recalcAnswer.resolve(SETTINGS_KESWICK));
    expect(home()).toBe(coordsOf(SETTINGS_KESWICK));
  });

  it('leaves the home — and the colour — where they were when a COLOUR save\'s read fails', async () => {
    // A colour save asks about a home that has not changed since the answer on screen, and the
    // provider asks nothing on one: emptying the map's home would drop the marker, the rings and
    // the ⌂ beside a tick line still naming it.
    const colourAnswer = deferred();
    const moveAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // chose 'verdict' earlier; the default is 'temp'
      .mockReturnValueOnce(colourAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0, 0);
    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));
    expect(getMode()).toBe('verdict');

    await saved(result, 0, 1); // a colour save; the home did not move
    await land(() => colourAnswer.reject(new Error('502 from /api/user/settings')));

    expect(home()).toBe(coordsOf(SETTINGS_MORPETH));
    // The ramp keeps the last loaded choice rather than falling back to the default.
    expect(getMode()).toBe('verdict');
    expect(screen.getByTestId('scale')).toHaveTextContent('verdict');

    // Control, settled through the same helper: a HOME save's failed read does empty it.
    await saved(result, 1, 1); // moved to Keswick
    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings')));
    expect(home()).toBe('unknown');
    expect(getMode()).toBe('verdict');
  });

  it('empties the home when a colour save\'s read fails after a home save nothing has answered yet', async () => {
    // The rule is "has the home been saved since the answer on screen", not "which counter moved
    // last": the colour save superseded the move's read, so its own read was the only one left that
    // could say where the reader lives now — and it failed.
    const moveAnswer = deferred();
    const colourAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(colourAnswer.promise);

    const result = await mountAt(0, 0);
    await saved(result, 1, 0); // moved to Keswick
    await saved(result, 1, 1); // and chose a colour before the move's read answered
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => colourAnswer.reject(new Error('502 from /api/user/settings')));

    // Not known. Keyed on the last counter to move, it kept Morpeth — the house the reader had
    // left.
    expect(home()).toBe('unknown');
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
