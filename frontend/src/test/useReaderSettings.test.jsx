/**
 * `useReaderSettings`' record — what makes the two counters move.
 *
 * <p>The page reads the reader's settings once, on mount, and after that takes the settings
 * dialog's own answers: its read on opening, a saved home's response, a recalculation's new stamp.
 * Each answer is compared with the record, and a counter moves only where it differs — the home
 * counter when the postcode or coordinates do (the server's own `originMoved` test), the drive-time
 * counter when the stamp does. The reads keyed on those counters (the Plan provider's reach, the
 * masthead's light) drop any request a newer move supersedes, which is only right while every move
 * is a real change: a counter that moved on a re-save of the same postcode would supersede, and
 * drop, a correct answer.
 *
 * <p>These rules live in a pure reducer, so they are tested as one, directly. How the hook applies
 * them — the mount read, its supersession by the dialog, the colour, the last-seen date — is tested
 * through its one consumer, `App`, with the real settings dialog and the real provider
 * (`App.test.jsx`), as the frontend test standards prefer to a harness.
 *
 * <p><b>The Map tab's tide mode is the one exception</b> (map-mobile-sheet-plan.md M4): it ships no
 * settings control in this phase, so there is no dialog to route it through and no `App.test.jsx`
 * flow that presses a choice. Its save line (`mapTideMode`/`saveTideMode`, below) is tested directly
 * against the hook with `renderHook`, mocking `../api/settingsApi.js` — the same API-module-boundary
 * mock every other frontend test in this suite already uses.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useReaderSettings, { recordReducer, INITIAL_RECORD } from '../hooks/useReaderSettings.js';

vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  saveMapTideMode: vi.fn(),
}));

import { getSettings, saveMapTideMode } from '../api/settingsApi.js';

/** `GET /api/user/settings` for one reader, before and after moving from Morpeth to Keswick. */
const MORPETH = {
  homePostcode: 'NE61 1AA', homeLatitude: 55.17, homeLongitude: -1.69,
  homePlaceName: 'Morpeth', driveTimesCalculatedAt: '2026-09-01T10:00:00Z',
};
const KESWICK = {
  homePostcode: 'CA12 5JR', homeLatitude: 54.6, homeLongitude: -3.13,
  homePlaceName: 'Keswick', driveTimesCalculatedAt: null,
};
const NO_HOME = {
  homePostcode: null, homeLatitude: null, homeLongitude: null,
  homePlaceName: null, driveTimesCalculatedAt: null,
};

const read = (settings) => ({ type: 'read', settings });
const answer = (settings) => ({ type: 'answer', settings });
const recalculated = (calculatedAt) => ({ type: 'recalculated', calculatedAt });

/** The record once the mount read has answered with `settings`. */
const readOf = (settings) => recordReducer(INITIAL_RECORD, read(settings));

describe('useReaderSettings — the mount read', () => {
  it('puts the answer on record and moves no counter — the keyed reads were asked at mount too', () => {
    const record = readOf(MORPETH);

    expect(record.settings).toEqual(MORPETH);
    expect(record.homeSettingsVersion).toBe(0);
    expect(record.driveTimesVersion).toBe(0);
  });

  it('keeps "no postcode" as a record of its own, apart from not knowing', () => {
    expect(INITIAL_RECORD.settings).toBeUndefined();
    expect(readOf(NO_HOME).settings).toEqual(NO_HOME);
  });
});

describe('useReaderSettings — a dialog answer moves a counter only where it differs', () => {
  it('moves nothing for the same home and stamp — a re-saved postcode — and keeps the record object', () => {
    const record = readOf(MORPETH);

    const after = recordReducer(record, answer({ ...MORPETH }));

    // The same object, so React bails out: nothing re-renders, nothing is asked again.
    expect(after).toBe(record);
  });

  it('keeps the place name on record when a save\'s response for the same home carries none', () => {
    // A save does not geocode, so its response names no place. Blanking it put the bare postcode
    // on the tick line in place of "Morpeth".
    const record = readOf(MORPETH);

    const after = recordReducer(record, answer({ ...MORPETH, homePlaceName: null }));

    expect(after.settings.homePlaceName).toBe('Morpeth');
    // Nothing else differs either, so it is the record itself: nothing re-renders.
    expect(after).toBe(record);
  });

  it('takes a new place name for the same home without moving a counter', () => {
    const record = readOf({ ...MORPETH, homePlaceName: null }); // the mount read's geocode failed

    const after = recordReducer(record, answer(MORPETH));

    expect(after.settings.homePlaceName).toBe('Morpeth');
    expect(after.homeSettingsVersion).toBe(0);
    expect(after.driveTimesVersion).toBe(0);
  });

  it('moves the home counter for a moved postcode, even with the same coordinates', () => {
    const after = recordReducer(readOf(MORPETH), answer({ ...MORPETH, homePostcode: 'NE61 1AB' }));

    expect(after.homeSettingsVersion).toBe(1);
  });

  it('moves the home counter for moved coordinates, even with the same postcode', () => {
    // The server's own test, exactly: distance and routing are computed from the coordinates, so
    // a re-geocode that lands somewhere new has moved the origin whatever the postcode says.
    const after = recordReducer(readOf(MORPETH), answer({ ...MORPETH, homeLatitude: 55.18 }));

    expect(after.homeSettingsVersion).toBe(1);
    expect(after.driveTimesVersion).toBe(0);
  });

  it('moves the home counter for a moved longitude alone', () => {
    // The third term of the server's test. Each coordinate counts on its own.
    const after = recordReducer(readOf(MORPETH), answer({ ...MORPETH, homeLongitude: -1.7 }));

    expect(after.homeSettingsVersion).toBe(1);
  });

  it('does not carry the old place name to a moved home', () => {
    const after = recordReducer(readOf(MORPETH), answer({ ...KESWICK, homePlaceName: null }));

    expect(after.settings.homePlaceName).toBeNull();
  });

  it('moves only the drive-time counter for a new stamp on the same home — a recalculation', () => {
    const record = readOf({ ...MORPETH, driveTimesCalculatedAt: null });

    const after = recordReducer(record, answer(MORPETH));

    expect(after.driveTimesVersion).toBe(1);
    // The light cannot change with a recalculation, so it is not asked again.
    expect(after.homeSettingsVersion).toBe(0);
    expect(after.settings.driveTimesCalculatedAt).toBe(MORPETH.driveTimesCalculatedAt);
  });

  it('moves both counters for a move that cleared the drive times', () => {
    const after = recordReducer(readOf(MORPETH), answer(KESWICK));

    expect(after.homeSettingsVersion).toBe(1);
    expect(after.driveTimesVersion).toBe(1);
    expect(after.settings).toEqual(KESWICK);
  });

  it('moves both counters for an answer while nothing is on record — a retry after a failed read', () => {
    const after = recordReducer(INITIAL_RECORD, answer(MORPETH));

    expect(after.settings).toEqual(MORPETH);
    expect(after.homeSettingsVersion).toBe(1);
    expect(after.driveTimesVersion).toBe(1);
  });

  it('counts each real change, not just the first', () => {
    // Two moves in a row must read as two: the keyed reads supersede on each.
    const once = recordReducer(readOf(MORPETH), answer(KESWICK));
    const twice = recordReducer(once, answer({ ...MORPETH, driveTimesCalculatedAt: null }));

    expect(twice.homeSettingsVersion).toBe(2);
    expect(twice.settings.homePostcode).toBe('NE61 1AA');
  });

  it('does not take one instant, spelled at two precisions, for a new stamp', () => {
    // The server hands a recalculation's stamp back from its clock (nanoseconds on Linux) and
    // stores it to the microsecond, so the next answer spells the same instant differently.
    const record = readOf({ ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.123456789Z' });

    const after = recordReducer(record, answer({
      ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.123457Z',
    }));

    expect(after).toBe(record);
  });

  it('does not take a stamp rounded into the next millisecond for a new one', () => {
    // Rounded to the microsecond on its way into the database, 12.123999789 is stored as 12.124:
    // the same instant, a millisecond on at the precision the page compares.
    const record = readOf({ ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.123999789Z' });

    const after = recordReducer(record, answer({
      ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.124Z',
    }));

    expect(after).toBe(record);
  });

  it('still takes a stamp a moment later for a new one', () => {
    // Control for the comparisons above: it is the instant that counts, not the spelling.
    const record = readOf({ ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.123Z' });

    const after = recordReducer(record, answer({
      ...MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:13.123Z',
    }));

    expect(after.driveTimesVersion).toBe(1);
  });

  it('counts each new stamp, not just the first', () => {
    // Two recalculations made elsewhere, each found by an opening of the dialog.
    const record = readOf({ ...MORPETH, driveTimesCalculatedAt: null });
    const once = recordReducer(record, answer(MORPETH));
    const twice = recordReducer(once, answer({
      ...MORPETH, driveTimesCalculatedAt: '2026-09-02T10:00:00Z',
    }));

    expect(twice.driveTimesVersion).toBe(2);
    expect(twice.homeSettingsVersion).toBe(0);
  });

  it('keeps the answer\'s own fields only, whatever else a response carries', () => {
    const after = recordReducer(readOf(MORPETH), answer({
      ...KESWICK, role: 'PRO_USER', mapColourScale: 'verdict', comingUpLastSeenDate: '2026-09-10',
    }));

    expect(after.settings).toEqual(KESWICK);
  });
});

describe('useReaderSettings — a recalculation', () => {
  it('moves only the drive-time counter, and puts its stamp on the home on record', () => {
    // It measures from the server's stored home; the dialog's copy of that home may be older.
    const record = readOf({ ...KESWICK, driveTimesCalculatedAt: null });

    const after = recordReducer(record, recalculated('2026-09-15T11:00:00Z'));

    expect(after.settings).toEqual({ ...KESWICK, driveTimesCalculatedAt: '2026-09-15T11:00:00Z' });
    expect(after.driveTimesVersion).toBe(1);
    expect(after.homeSettingsVersion).toBe(0);
  });

  it('still moves the drive-time counter while nothing is on record', () => {
    const after = recordReducer(INITIAL_RECORD, recalculated('2026-09-15T11:00:00Z'));

    expect(after.settings).toBeUndefined();
    expect(after.driveTimesVersion).toBe(1);
    expect(after.homeSettingsVersion).toBe(0);
  });

  it('counts each recalculation, not just the first', () => {
    const once = recordReducer(readOf(KESWICK), recalculated('2026-09-15T11:00:00Z'));
    const twice = recordReducer(once, recalculated('2026-09-15T12:00:00Z'));

    expect(twice.driveTimesVersion).toBe(2);
  });

  it('moves nothing for the stamp already on record, at whatever precision', () => {
    // Only a new stamp is a change. The same instant, spelled to the nanosecond, is not one.
    const record = readOf({ ...KESWICK, driveTimesCalculatedAt: '2026-09-15T11:00:00.123457Z' });

    expect(recordReducer(record, recalculated('2026-09-15T11:00:00.123456789Z'))).toBe(record);
  });
});

describe('useReaderSettings — mapTideMode / saveTideMode (map-mobile-sheet-plan.md M4)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /**
   * Flushes the mount read's effect and any queued save's continuations — the line is a few
   * `then`s deep per turn, so twenty microtask ticks (`colourSaveQueue.test.js`'s own margin) is
   * ample. Wrapped in one `act()` so every state update the flush produces is captured.
   */
  async function drain() {
    await act(async () => {
      for (let i = 0; i < 20; i += 1) {
        await Promise.resolve();
      }
    });
  }

  /** Mounts the hook with a settled mount read of `settings`. */
  async function mountSettled(settings) {
    getSettings.mockResolvedValue(settings);
    const view = renderHook(() => useReaderSettings());
    await drain();
    return view;
  }

  /** A tide-mode save the test settles by hand — the same shape `colourSaveQueue.test.js` uses. */
  function heldSave() {
    const calls = [];
    saveMapTideMode.mockImplementation((mode) => new Promise((resolve, reject) => {
      calls.push({ mode, resolve, reject });
    }));
    return calls;
  }

  it('defaults to auto before the mount read answers', () => {
    getSettings.mockReturnValue(new Promise(() => {})); // never settles in this test
    const { result } = renderHook(() => useReaderSettings());

    expect(result.current.mapTideMode).toBe('auto');
  });

  it("takes the mount read's mode", async () => {
    const { result } = await mountSettled({ mapTideMode: 'always' });

    expect(result.current.mapTideMode).toBe('always');
  });

  it('reads a never-chosen (null) server value as auto', async () => {
    const { result } = await mountSettled({ mapTideMode: null });

    expect(result.current.mapTideMode).toBe('auto');
  });

  it('a press BEFORE the mount read lands does not drop that read, and the press stays on screen', async () => {
    // Found by M4's review (and again by Codex on #925): a tide save that took part in the shared
    // answers' order — at press time or at landing — dropped a slow getSettings() WHOLE, home,
    // colour and last-seen date with it. The tide now has an order of its own.
    let landRead;
    getSettings.mockReturnValue(new Promise((resolve) => { landRead = resolve; }));
    const { result } = renderHook(() => useReaderSettings());
    const calls = heldSave();

    act(() => { result.current.saveTideMode('off'); });
    expect(result.current.mapTideMode, 'optimistic while the save is out').toBe('off');

    await act(async () => {
      landRead({ mapColourScale: 'temp', mapTideMode: 'always', comingUpLastSeenDate: '2026-09-10' });
      await drain();
    });
    expect(result.current.comingUpLastSeenDate, 'the read landed — it was not dropped')
        .toBe('2026-09-10');
    expect(result.current.mapColourScale).toBe('temp');
    expect(result.current.mapTideMode, 'the press is newer than the read, so it stays').toBe('off');

    calls[0].resolve({ mapTideMode: 'off' });
    await drain();
    expect(result.current.mapTideMode).toBe('off');

    // And the landed save is the rollback baseline from here, not the read's older value.
    const later = heldSave();
    act(() => { result.current.saveTideMode('auto'); });
    later[0].reject(new Error('502'));
    await drain();
    expect(result.current.mapTideMode).toBe('off');
  });

  it('a save that LANDS before the mount read does not drop that read either (Codex on #925)', async () => {
    let landRead;
    getSettings.mockReturnValue(new Promise((resolve) => { landRead = resolve; }));
    const { result } = renderHook(() => useReaderSettings());
    const calls = heldSave();

    act(() => { result.current.saveTideMode('off'); });
    calls[0].resolve({ mapTideMode: 'off' });
    await drain();
    expect(result.current.mapTideMode).toBe('off');

    await act(async () => {
      landRead({ mapColourScale: 'temp', mapTideMode: 'always', comingUpLastSeenDate: '2026-09-10' });
      await drain();
    });
    expect(result.current.comingUpLastSeenDate, 'home/colour/date applied from the read').toBe('2026-09-10');
    expect(result.current.mapColourScale).toBe('temp');
    expect(result.current.mapTideMode, "the read's older tide mode does not overwrite the landed save")
        .toBe('off');

    // The baseline is the landed save, not the read's value: a later failure reverts to 'off'.
    const later = heldSave();
    act(() => { result.current.saveTideMode('auto'); });
    later[0].reject(new Error('502'));
    await drain();
    expect(result.current.mapTideMode).toBe('off');
  });

  it('an older choice landing or failing after a NEWER press leaves the newer choice on screen (Codex on #925)', async () => {
    const { result } = await mountSettled({ mapTideMode: 'auto' });
    const calls = heldSave();

    act(() => { result.current.saveTideMode('always'); }); // A, in flight
    act(() => { result.current.saveTideMode('off'); });    // B, queued behind A
    expect(result.current.mapTideMode, 'B is what the reader chose last').toBe('off');

    calls[0].resolve({ mapTideMode: 'always' }); // A lands
    await drain();
    expect(result.current.mapTideMode, "A's landing must not put 'always' back while B is out").toBe('off');
    expect(saveMapTideMode, 'B went out once A landed').toHaveBeenCalledTimes(2);

    calls[1].resolve({ mapTideMode: 'off' }); // B lands
    await drain();
    expect(result.current.mapTideMode).toBe('off');

    // The failure arm: an older choice FAILING after a newer press does not revert the newer one.
    const later = heldSave();
    act(() => { result.current.saveTideMode('auto'); });   // C, in flight
    act(() => { result.current.saveTideMode('always'); }); // D, queued
    later[0].reject(new Error('502'));                     // C fails
    await drain();
    expect(result.current.mapTideMode, "C's failure must not revert D's optimistic value").toBe('always');
  });

  it('holds the first save alone in flight, then sends only the newest queued choice', async () => {
    const { result } = await mountSettled({ mapTideMode: 'auto' });
    const calls = heldSave();

    act(() => { result.current.saveTideMode('always'); });
    expect(saveMapTideMode, 'starts at once — nothing ahead of it').toHaveBeenCalledTimes(1);
    expect(saveMapTideMode).toHaveBeenCalledWith('always');

    // Three more choices arrive while the first is still held. Only the LAST is ever sent — the
    // earlier two are overtaken before their own turn comes and never reach the network at all.
    act(() => {
      result.current.saveTideMode('off');
      result.current.saveTideMode('auto');
      result.current.saveTideMode('always');
    });
    expect(result.current.mapTideMode, 'the newest choice is shown at once, optimistically')
        .toBe('always');
    expect(saveMapTideMode, 'nothing goes out beside a save already in flight')
        .toHaveBeenCalledTimes(1);

    calls[0].resolve({ mapTideMode: 'always' });
    await drain();

    expect(saveMapTideMode, 'exactly one more call — the newest queued choice')
        .toHaveBeenCalledTimes(2);
    expect(saveMapTideMode).toHaveBeenLastCalledWith('always');

    calls[1].resolve({ mapTideMode: 'always' });
    await drain();

    expect(result.current.mapTideMode).toBe('always');
  });

  it('reverts to the last mode the server holds when the newest choice fails — never the '
      + 'discarded queued value, and never the pre-session value', async () => {
    const { result } = await mountSettled({ mapTideMode: 'auto' });
    const calls = heldSave();

    act(() => { result.current.saveTideMode('always'); });
    calls[0].resolve({ mapTideMode: 'always' });
    await drain();
    expect(result.current.mapTideMode).toBe('always');

    let outcome;
    act(() => { outcome = result.current.saveTideMode('off'); });
    calls[1].reject(new Error('502'));
    await drain();

    expect(await outcome).toBe('failed');
    expect(result.current.mapTideMode,
        'reverts to the baseline the server actually holds — "always" — not "off" (never sent) '
        + 'and not "auto" (the pre-session default)')
        .toBe('always');
  });
});
