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
 */
import { describe, it, expect } from 'vitest';
import { recordReducer, INITIAL_RECORD } from '../hooks/useReaderSettings.js';

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
    expect(after.homeSettingsVersion).toBe(0);
    expect(after.driveTimesVersion).toBe(0);
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

  it('keeps the answer\'s own fields only, whatever else a response carries', () => {
    const after = recordReducer(readOf(MORPETH), answer({
      ...KESWICK, role: 'PRO_USER', mapColourScale: 'verdict', comingUpLastSeenDate: '2026-09-10',
    }));

    expect(after.settings).toEqual(KESWICK);
  });
});
