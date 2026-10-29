/**
 * `components/map/MapCallout.jsx` — where the rating block mounts, and the state the callout owns
 * for it (outcome-rating-plan.md §2 #5, §3 O4a step 3).
 *
 * <p>The block's own states are `OutcomeRatingBlock.test.jsx`'s job; this file pins the SEAM:
 * <ul>
 *   <li><b>The mount condition</b> — a past window ({@code event.past}), a sky place, this PLACE's own
 *   forecast row with an event time that has passed by the afterglow rule, the reader's ratings
 *   loaded, a known role, no rewind. Never {@code event.past} alone: that is decided on the
 *   roster's EARLIEST time, and a place further along the coast may not have had its own event yet
 *   (the server's per-place check would refuse the rating). A place with no row, or a row with no
 *   time, gets no block at all.</li>
 *   <li><b>Where it sits</b> — inside the scrolling body, after the facts row, before the actions.</li>
 *   <li><b>The owned state</b> — {@code changing} resets with the selection and with the window, and
 *   both it and the layout are repaint dependencies (a 44px grid and a 40px row are different card
 *   heights, which jsdom cannot measure, so the repaint is counted on {@code latLngToContainerPoint}
 *   the way {@code MapCallout.test.jsx}'s event-id test does).</li>
 * </ul>
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));

// The Admin's list reads one slot's ratings; no test here may reach the network.
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
  fetchAllRatingsForSlot: vi.fn(),
}));

// Passes straight through to the real hook, and records what the callout hands it.
vi.mock('../hooks/useSlotRatings.js', async (importActual) => {
  const actual = await importActual();
  return { useSlotRatings: vi.fn(actual.useSlotRatings) };
});

import MapCallout from '../components/map/MapCallout.jsx';
import { useSlotRatings } from '../hooks/useSlotRatings.js';
import { fetchAllRatingsForSlot } from '../api/outcomeRatingApi.js';
import { setRewind } from '../utils/rewind.js';
import { rowKey } from '../hooks/useOutcomeRatings.js';

function withMeasuredCard(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

function makeMap() {
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  return {
    zoom: 9,
    getZoom() { return this.zoom; },
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    panInside() {},
    flyTo() {},
    on() {},
    off() {},
  };
}

/** "Now" for every test: 10:00 UTC on the 15th, so yesterday's 20:10 sunset is long over. */
const NOW = new Date('2026-06-15T10:00:00Z');
const YESTERDAY = '2026-06-14';
const TODAY = '2026-06-15';

const rowFor = (date, solarEventTime, extra = {}) => new Map([[date, { sunset: { solarEventTime, ...extra } }]]);

const LOCATION = {
  id: 7,
  name: 'Bamburgh',
  lat: 55.6,
  lon: -1.7,
  regionName: 'North East',
  bortleClass: 3,
  tideType: ['HIGH'],
  locationType: ['SEASCAPE'],
  forecastsByDate: rowFor(YESTERDAY, `${YESTERDAY}T20:10:00`),
};

const PAST_EVENT = {
  id: `solar:${YESTERDAY}:SUNSET`,
  kind: 'solar',
  eventType: 'SUNSET',
  date: YESTERDAY,
  past: true,
  served: false,
  label: 'Yesterday sunset',
  dayLabel: 'Yesterday',
  time: '21:10',
  badges: [],
};
const KEY = rowKey(LOCATION.id, YESTERDAY, 'SUNSET');

/** What `useOutcomeRatings` returns, with `rate`/`clearSaveError` spied. */
function ratingsFor({
  mine = [], saving = [], saveError = [], loaded = true, coversDate = null, tagSaving = [],
  tagSaveError = [],
} = {}) {
  return {
    loaded,
    // Which dates' ratings have been read — by default, every date once `loaded`.
    covers: coversDate ?? (() => loaded),
    canRate: true,
    mine: new Map(mine),
    saving: new Set(saving),
    saveError: new Set(saveError),
    tagSaving: new Set(tagSaving),
    tagSaveError: new Set(tagSaveError),
    rate: vi.fn().mockResolvedValue(true),
    rateTag: vi.fn().mockResolvedValue(true),
    clearSaveError: vi.fn(),
  };
}

let frames = [];
let originalRaf;
let originalCancel;
let restore;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
  currentMap = makeMap();
  restore = withMeasuredCard(286, 260);
});
afterEach(() => {
  setRewind(null);
  restore();
  vi.useRealTimers();
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

const element = (props = {}) => (
  <MapCallout
    location={LOCATION}
    event={PAST_EVENT}
    rating={4}
    userRole="PRO_USER"
    ratings={ratingsFor()}
    {...props}
  />
);

async function mount(props = {}) {
  let result;
  await act(async () => { result = render(element(props)); });
  return result;
}

const block = () => screen.queryByTestId('outcome-rating-block');

describe('MapCallout — the rating block\'s mount condition', () => {
  it('mounts on a past window, for a Pro reader, with the place\'s own event time passed', async () => {
    await mount();
    expect(block()).toBeInTheDocument();
    expect(block()).toHaveAttribute('data-state', 'unrated');
    // The meta reads the window's own day label and time.
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Sunset was yesterday 21:10');
  });

  it('mounts for an Admin and, locked, for a Lite reader', async () => {
    const admin = await mount({ userRole: 'ADMIN' });
    expect(block()).toHaveAttribute('data-state', 'unrated');
    admin.unmount();
    await mount({ userRole: 'LITE_USER' });
    expect(block()).toHaveAttribute('data-state', 'lite');
  });

  it.each([
    ['no role is known', { userRole: null }],
    ['an unrecognised role', { userRole: 'GUEST' }],
    ['the reader\'s ratings are not offered (null)', { ratings: null }],
    ['the reader\'s ratings have not loaded', { ratings: ratingsFor({ loaded: false }) }],
    ['the reader\'s ratings for THIS window\'s date have not been read (an older day just landed)', { ratings: ratingsFor({ coversDate: (date) => date > YESTERDAY }) }],
    ['the window has not passed (a live or filler row)', { event: { ...PAST_EVENT, past: undefined } }],
    ['the window is a night', { event: { ...PAST_EVENT, id: `astro:${YESTERDAY}:ASTRO`, kind: 'astro', eventType: 'ASTRO' } }],
  ])('does not mount when %s', async (_name, props) => {
    await mount(props);
    expect(block()).not.toBeInTheDocument();
  });

  it('does not mount for a wildlife hide, which has no sky to rate', async () => {
    await mount({ location: { ...LOCATION, locationType: ['WILDLIFE'] } });
    expect(block()).not.toBeInTheDocument();
  });

  it('does not mount for a location with no id (nothing to record against)', async () => {
    await mount({ location: { ...LOCATION, id: undefined } });
    expect(block()).not.toBeInTheDocument();
  });

  it('does not mount while a rewind is in force — a rewind is a screenshot of the forecast as it stood', async () => {
    setRewind('2026-06-14T21:00:00Z');
    await mount();
    expect(block()).not.toBeInTheDocument();
  });
});

describe('MapCallout — the gate opens at the place\'s own afterglow boundary with no other render (P4e)', () => {
  it('absent just before, mounted just after, from the callout\'s own timer alone', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(NOW);
    const event = { ...PAST_EVENT, id: `solar:${TODAY}:SUNSET`, date: TODAY };
    // 09:45 plus the 30-minute afterglow: the gate opens at 10:15:00.
    await mount({ location: { ...LOCATION, forecastsByDate: rowFor(TODAY, `${TODAY}T09:45:00`) }, event });
    expect(block()).not.toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(14 * 60 * 1000); });
    expect(block()).not.toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(60 * 1000 + 100); });
    expect(block()).toBeInTheDocument();
  });
});

describe('MapCallout — placePassed is THIS place\'s own row, not the window\'s', () => {
  it('a place whose own event time has passed mounts', async () => {
    await mount({ location: { ...LOCATION, forecastsByDate: rowFor(YESTERDAY, `${YESTERDAY}T20:10:00`) } });
    expect(block()).toBeInTheDocument();
  });

  it('a place whose own event is later than "now", on a window the roster\'s EARLIEST time has made past, does not', async () => {
    // `event.past` says so (it was decided on the earliest place's time); this place's own sunset is
    // 10:30 — half an hour AFTER the clock — so the server's per-place `hasPassed` would refuse a rating.
    const later = { ...LOCATION, forecastsByDate: rowFor(TODAY, `${TODAY}T10:30:00`) };
    const event = { ...PAST_EVENT, id: `solar:${TODAY}:SUNSET`, date: TODAY };
    await mount({ location: later, event });
    expect(block()).not.toBeInTheDocument();
  });

  it('the afterglow is thirty minutes and strict: 29 minutes after the event is not past, 31 is', async () => {
    const at = (minutesAgo) => new Date(NOW.getTime() - minutesAgo * 60000).toISOString().slice(0, 19);
    const event = { ...PAST_EVENT, id: `solar:${TODAY}:SUNSET`, date: TODAY };
    const early = await mount({ location: { ...LOCATION, forecastsByDate: rowFor(TODAY, at(29)) }, event });
    expect(block()).not.toBeInTheDocument();
    early.unmount();
    await mount({ location: { ...LOCATION, forecastsByDate: rowFor(TODAY, at(31)) }, event });
    expect(block()).toBeInTheDocument();
  });

  it('a place with NO forecast row for the window gets no block at all', async () => {
    await mount({ location: { ...LOCATION, forecastsByDate: new Map() } });
    expect(block()).not.toBeInTheDocument();
  });

  it('a place with a row for another date only gets none', async () => {
    await mount({ location: { ...LOCATION, forecastsByDate: rowFor(TODAY, `${TODAY}T05:00:00`) } });
    expect(block()).not.toBeInTheDocument();
  });

  it('a row with no event time gets none (nothing vouches for it)', async () => {
    await mount({ location: { ...LOCATION, forecastsByDate: rowFor(YESTERDAY, null) } });
    expect(block()).not.toBeInTheDocument();
  });

  it('reads the slot matching the window\'s own type: a sunrise window reads the sunrise row, not the sunset one', async () => {
    const sunrise = { ...PAST_EVENT, id: `solar:${YESTERDAY}:SUNRISE`, eventType: 'SUNRISE', label: 'Yesterday sunrise' };
    const onlySunset = await mount({ event: sunrise });
    expect(block()).not.toBeInTheDocument();
    onlySunset.unmount();
    const both = new Map([[YESTERDAY, {
      sunrise: { solarEventTime: `${YESTERDAY}T04:30:00` },
      sunset: { solarEventTime: `${TODAY}T23:00:00` },
    }]]);
    await mount({ event: sunrise, location: { ...LOCATION, forecastsByDate: both } });
    expect(block()).toBeInTheDocument();
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Sunrise was yesterday');
  });
});

describe('MapCallout — where the block sits', () => {
  it('is inside the scrolling body, after the facts row and before the actions', async () => {
    await mount({ driveMinutes: 65 });
    const card = screen.getByTestId('map-callout');
    const body = card.querySelector('.wf-callout-body');
    expect(body).toContainElement(block());
    const facts = screen.getByTestId('map-callout-facts');
    const actions = card.querySelector('.wf-callout-actions');
    const order = (a, b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING;
    expect(order(facts, block())).toBeTruthy();
    expect(order(block(), actions)).toBeTruthy();
    // Not the last thing in the body: the strip toggle is after it (plan §4 #12).
    expect(order(block(), screen.getByTestId('map-callout-strip-toggle'))).toBeTruthy();
    expect(actions.parentElement).toBe(card);
  });

  it('keeps the forecast star in the verdict strip, before and after the block mounts', async () => {
    await mount({ rating: 2 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('2★ Poor');
  });
});

describe('MapCallout — what the block is handed', () => {
  it('reads the record, the saving flag and the error flag for THIS slot\'s key alone', async () => {
    const ratings = ratingsFor({
      mine: [[KEY, { rating: 4, updatedAt: 'x' }], [rowKey(8, YESTERDAY, 'SUNSET'), { rating: 1, updatedAt: 'x' }]],
    });
    await mount({ ratings });
    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(block().querySelector('.wf-rate-numeral')).toHaveTextContent('4★');
  });

  it('shows saving for this key and ignores another key\'s', async () => {
    const mine = [[KEY, { rating: 4, updatedAt: null }]];
    const own = await mount({ ratings: ratingsFor({ mine, saving: [KEY] }) });
    expect(block()).toHaveAttribute('data-state', 'saving');
    own.unmount();
    await mount({ ratings: ratingsFor({ mine, saving: [rowKey(8, YESTERDAY, 'SUNSET')] }) });
    expect(block()).toHaveAttribute('data-state', 'rated');
  });

  it('shows the failed-save line for this key', async () => {
    await mount({ ratings: ratingsFor({ saveError: [KEY] }) });
    expect(screen.getByTestId('outcome-rating-status')).toHaveTextContent('Not saved. Tap a star again.');
  });

  it('a star press calls ratings.rate with the place, the window\'s date and type, and the value', async () => {
    const ratings = ratingsFor();
    await mount({ ratings });
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[3]); });
    expect(ratings.rate).toHaveBeenCalledWith(7, YESTERDAY, 'SUNSET', 4);
    await act(async () => { fireEvent.click(screen.getByTestId('outcome-rating-not-seen')); });
    expect(ratings.rate).toHaveBeenLastCalledWith(7, YESTERDAY, 'SUNSET', null);
  });

  it('a chip press calls ratings.rateTag with the place, the window\'s date and type, and the tag', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x' }]] });
    await mount({ ratings });
    await act(async () => { fireEvent.click(screen.getByText('Mist')); });
    expect(ratings.rateTag).toHaveBeenCalledWith(7, YESTERDAY, 'SUNSET', 'MIST');
    expect(ratings.rate).not.toHaveBeenCalled();
  });

  it('pressing the held chip clears it: rateTag with null', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x', seen: 'MIST' }]] });
    await mount({ ratings });
    await act(async () => { fireEvent.click(screen.getByText('Mist')); });
    expect(ratings.rateTag).toHaveBeenCalledWith(7, YESTERDAY, 'SUNSET', null);
  });

  it('reads the tag-saving and tag-failed flags for THIS slot\'s key alone', async () => {
    const other = rowKey(8, YESTERDAY, 'SUNSET');
    const ratings = ratingsFor({
      mine: [[KEY, { rating: 4, updatedAt: 'x' }]], tagSaving: [other], tagSaveError: [other],
    });
    await mount({ ratings });
    expect(screen.getByTestId('outcome-rating-tag-status').textContent).toBe('');
    for (const c of screen.getAllByTestId('outcome-rating-seen-chip')) {
      expect(c.getAttribute('aria-disabled')).toBeNull();
    }
  });

  it('shows the tag-failed line for this key', async () => {
    const failed = ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x' }]], tagSaveError: [KEY] });
    await mount({ ratings: failed });
    expect(screen.getByTestId('outcome-rating-tag-status').textContent).toContain('Tag not saved');
  });

  it('shows the chips busy for this key\'s tag save, and a press asks for nothing (positive control)', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x' }]], tagSaving: [KEY] });
    await mount({ ratings });
    for (const c of screen.getAllByTestId('outcome-rating-seen-chip')) {
      expect(c.getAttribute('aria-disabled')).toBe('true');
    }
    await act(async () => { fireEvent.click(screen.getByText('Mist')); });
    expect(ratings.rateTag).not.toHaveBeenCalled();
  });

  it('a chip press by an Admin re-fetches the slot\'s list once it lands (the tag is part of the list)', async () => {
    fetchAllRatingsForSlot.mockResolvedValue({ eventTime: null, forecastRunAt: null, ratings: [] });
    const ratings = ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x' }]] });
    await mount({ ratings, userRole: 'ADMIN' });
    await act(async () => { fireEvent.click(screen.getByTestId('outcome-admin-toggle')); });
    const before = fetchAllRatingsForSlot.mock.calls.length;
    await act(async () => { fireEvent.click(screen.getByText('Flat')); });
    expect(fetchAllRatingsForSlot.mock.calls.length).toBeGreaterThan(before);
  });

  it('clears the failed-save line for this key when the block asks (Change, or unmounting)', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    const { unmount } = await mount({ ratings });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(ratings.clearSaveError).toHaveBeenCalledWith(KEY);
    ratings.clearSaveError.mockClear();
    unmount();
    expect(ratings.clearSaveError).toHaveBeenCalledWith(KEY);
  });
});

describe('MapCallout — the state it owns for the block', () => {
  it('Change opens the grid in the callout\'s own `changing` state, and a successful save closes it', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    await mount({ ratings });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(block()).toHaveAttribute('data-state', 'changing');
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[0]); });
    // `rate` resolved true; the (mock) record has not moved, but the callout has left `changing`.
    expect(block()).toHaveAttribute('data-state', 'rated');
  });

  it('a failed save leaves `changing` where it was', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    ratings.rate.mockResolvedValue(false);
    await mount({ ratings });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[0]); });
    expect(block()).toHaveAttribute('data-state', 'changing');
  });

  it('resets `changing` when the WINDOW changes, with the location unchanged', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    const { rerender } = await mount({ ratings });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(block()).toHaveAttribute('data-state', 'changing');

    const sunrise = { ...PAST_EVENT, id: `solar:${YESTERDAY}:SUNRISE`, eventType: 'SUNRISE' };
    const both = new Map([[YESTERDAY, {
      sunrise: { solarEventTime: `${YESTERDAY}T04:30:00` },
      sunset: { solarEventTime: `${YESTERDAY}T20:10:00` },
    }]]);
    await act(async () => {
      rerender(element({
        event: sunrise, ratings, location: { ...LOCATION, forecastsByDate: both },
      }));
    });
    expect(block()).toHaveAttribute('data-state', 'unrated');

    // And back: the sunset's record is rated, NOT carrying the earlier `changing`.
    await act(async () => {
      rerender(element({ ratings, location: { ...LOCATION, forecastsByDate: both } }));
    });
    expect(block()).toHaveAttribute('data-state', 'rated');
  });

  it('a save that lands AFTER the reader moved to another place does not close that place\'s Changing', async () => {
    const KEY_B = rowKey(8, YESTERDAY, 'SUNSET');
    const ratings = ratingsFor({ mine: [[KEY_B, { rating: 2, updatedAt: 'x' }]] });
    let landA;
    ratings.rate.mockImplementationOnce(() => new Promise((resolve) => { landA = resolve; }));
    const { rerender } = await mount({ ratings });
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[2]); });

    const placeB = { ...LOCATION, id: 8, name: 'Seahouses' };
    await act(async () => { rerender(element({ ratings, location: placeB })); });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(block()).toHaveAttribute('data-state', 'changing');

    // A's save resolves true NOW. It belongs to a block that has gone; it must not touch B's state.
    await act(async () => { landA(true); });
    expect(block()).toHaveAttribute('data-state', 'changing');
  });

  it('resets `changing` when the LOCATION changes, with the window unchanged', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    const { rerender } = await mount({ ratings });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    const other = { ...LOCATION, id: 8, name: 'Seahouses' };
    await act(async () => { rerender(element({ ratings, location: other })); });
    expect(block()).toHaveAttribute('data-state', 'unrated');
    await act(async () => { rerender(element({ ratings })); });
    expect(block()).toHaveAttribute('data-state', 'rated');
  });
});

describe('MapCallout — the rating block repaints the card (plan §1 #3)', () => {
  /** Counted on the one `map.*` read `paint()` always makes, as `MapCallout.test.jsx` does. */
  async function repaintsAfter(change, props) {
    const { rerender } = await mount(props);
    const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const before = spy.mock.calls.length;
    await act(async () => { change({ rerender }); });
    return spy.mock.calls.length - before;
  }

  it('re-measures when `changing` flips (the 44px grid and the 40px row are different heights)', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    await mount({ ratings });
    const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const before = spy.mock.calls.length;
    await act(async () => { fireEvent.click(screen.getByTestId('outcome-rating-change')); });
    expect(spy.mock.calls.length).toBeGreaterThan(before);
  });

  it('re-measures when the ratings load and the block mounts', async () => {
    const calls = await repaintsAfter(({ rerender }) => rerender(element({ ratings: ratingsFor() })), {
      ratings: ratingsFor({ loaded: false }),
    });
    expect(calls).toBeGreaterThan(0);
  });

  it('re-measures when a failed save adds its line', async () => {
    const calls = await repaintsAfter(
      ({ rerender }) => rerender(element({ ratings: ratingsFor({ saveError: [KEY] }) })),
      { ratings: ratingsFor() },
    );
    expect(calls).toBeGreaterThan(0);
  });

  it('re-measures when the record lands and the grid becomes the done row', async () => {
    const calls = await repaintsAfter(
      ({ rerender }) => rerender(element({ ratings: ratingsFor({ mine: [[KEY, { rating: 5, updatedAt: 'x' }]] }) })),
      { ratings: ratingsFor() },
    );
    expect(calls).toBeGreaterThan(0);
  });

  it('re-measures when the chip row comes with the record (the done row grows by the chips)', async () => {
    // Unrated -> rated also flips the view, so isolate the chips: the same view ('done'), a tag failure line.
    const calls = await repaintsAfter(
      ({ rerender }) => rerender(element({
        ratings: ratingsFor({ mine: [[KEY, { rating: 5, updatedAt: 'x' }]], tagSaveError: [KEY] }),
      })),
      { ratings: ratingsFor({ mine: [[KEY, { rating: 5, updatedAt: 'x' }]] }) },
    );
    expect(calls).toBeGreaterThan(0);
  });

  it('re-measures when the chip row goes away with the star (the view stays "done")', async () => {
    const calls = await repaintsAfter(
      ({ rerender }) => rerender(element({
        ratings: ratingsFor({ mine: [[KEY, { rating: null, updatedAt: 'x', seen: 'NOT_SEEN' }]] }),
      })),
      { ratings: ratingsFor({ mine: [[KEY, { rating: 4, updatedAt: 'x' }]] }) },
    );
    expect(calls).toBeGreaterThan(0);
  });

  it('control: an unrelated re-render with the same layout does not repaint', async () => {
    const calls = await repaintsAfter(
      ({ rerender }) => rerender(element({ ratings: ratingsFor(), rating: 4 })),
      { ratings: ratingsFor() },
    );
    expect(calls).toBe(0);
  });
});

describe('MapCallout — the unrated word on a past window', () => {
  it('says "Not scored", not "Not scored yet", once the rating\'s own source has answered', async () => {
    await mount({ rating: null, ratingKnown: true, ratings: null });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent(/^Not scored$/);
  });

  it('control: a live window keeps "Not scored yet"', async () => {
    await mount({
      event: { ...PAST_EVENT, past: undefined }, rating: null, ratingKnown: true, ratings: null,
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored yet');
  });

  it('control: while the source has not answered, a past window still says Loading…', async () => {
    await mount({ rating: null, ratingKnown: false, ratings: null });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Loading…');
  });
});

describe('MapCallout — the Admin\'s "All ratings" list (O5a)', () => {
  const RUN = `${YESTERDAY}T17:05:00Z`;
  const EVENT_TIME = `${YESTERDAY}T19:10:00Z`;
  const answer = (names = ['jgregory', 'mhart']) => ({
    eventTime: EVENT_TIME,
    forecastRunAt: RUN,
    pipelineRunId: 3,
    ratings: names.map((username, i) => ({
      username, rating: 3, updatedAt: `${YESTERDAY}T20:30:00Z`, mine: i === 0,
    })),
  });

  beforeEach(() => {
    fetchAllRatingsForSlot.mockReset();
    fetchAllRatingsForSlot.mockResolvedValue(answer());
  });

  const toggle = () => screen.getByTestId('outcome-admin-toggle');
  const expand = async () => {
    await act(async () => { fireEvent.click(toggle()); });
  };

  it('mounts for an ADMIN, collapsed, and asks for nothing until it is opened', async () => {
    await mount({ userRole: 'ADMIN' });
    expect(screen.getByTestId('outcome-admin-list')).toBeInTheDocument();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveTextContent(/^All ratings\s*▾$/);
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('fetches this slot on the first expand, lists the rows and counts them in the header', async () => {
    await mount({ userRole: 'ADMIN' });
    await expand();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
    expect(fetchAllRatingsForSlot).toHaveBeenCalledWith(7, YESTERDAY, 'SUNSET');
    expect(toggle()).toHaveTextContent('All ratings · 2');
    expect(screen.getAllByTestId('outcome-admin-row')).toHaveLength(2);
    expect(screen.getByTestId('outcome-admin-footer')).toHaveTextContent('· 2h before sunset');
    // Closing and opening again does not fetch again.
    await expand();
    await expand();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
  });

  it('renders a reader\'s seen tag in the list, THROUGH the hook and the callout (server row to screen)', async () => {
    fetchAllRatingsForSlot.mockResolvedValue({
      eventTime: `${YESTERDAY}T20:10:00Z`,
      forecastRunAt: `${YESTERDAY}T18:10:00Z`,
      ratings: [
        { username: 'zoe', rating: 5, updatedAt: `${YESTERDAY}T21:30:00Z`, mine: false, seen: 'MIST' },
        { username: 'mhart', rating: 4, updatedAt: `${YESTERDAY}T21:00:00Z`, mine: false },
      ],
    });
    await mount({ userRole: 'ADMIN' });
    await expand();

    const tags = screen.getAllByTestId('outcome-admin-seen');
    expect(tags).toHaveLength(1);
    expect(tags[0]).toHaveTextContent('Mist');
    expect(tags[0].closest('[data-testid="outcome-admin-row"]')).toHaveTextContent('zoe');
  });

  it.each([
    ['PRO_USER'],
    ['LITE_USER'],
  ])('never mounts the list, and never asks for it, for a %s', async (role) => {
    await mount({ userRole: role });
    expect(block()).toBeInTheDocument();
    expect(screen.queryByTestId('outcome-admin-list')).toBeNull();
    await act(async () => {});
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('hands the hook `enabled` for an ADMIN with the block mounted — and for nobody else, nor without the block', async () => {
    const enabledOf = () => useSlotRatings.mock.calls.map(([args]) => args.enabled);
    useSlotRatings.mockClear();
    const admin = await mount({ userRole: 'ADMIN' });
    expect(enabledOf()).toContain(true);
    admin.unmount();

    for (const props of [
      { userRole: 'PRO_USER' },
      { userRole: 'LITE_USER' },
      { userRole: 'ADMIN', location: { ...LOCATION, forecastsByDate: new Map() } },
      { userRole: 'ADMIN', ratings: null },
    ]) {
      useSlotRatings.mockClear();
      const { unmount } = await mount(props);
      expect(enabledOf().length).toBeGreaterThan(0);
      expect(enabledOf()).not.toContain(true);
      unmount();
    }
  });

  it('mounts no list where the rating block itself is not mounted (no row for the place, a rewind)', async () => {
    const noRow = await mount({ userRole: 'ADMIN', location: { ...LOCATION, forecastsByDate: new Map() } });
    expect(screen.queryByTestId('outcome-admin-list')).toBeNull();
    noRow.unmount();
    setRewind('2026-06-14T21:00:00Z');
    await mount({ userRole: 'ADMIN' });
    expect(screen.queryByTestId('outcome-admin-list')).toBeNull();
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('re-fetches after the admin\'s own save lands, and only then', async () => {
    const ratings = ratingsFor();
    await mount({ userRole: 'ADMIN', ratings });
    await expand();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);

    fetchAllRatingsForSlot.mockResolvedValue(answer(['jgregory', 'mhart', 'zoe']));
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[3]); });
    expect(ratings.rate).toHaveBeenCalledWith(7, YESTERDAY, 'SUNSET', 4);
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
    expect(toggle()).toHaveTextContent('All ratings · 3');
  });

  it('does not re-fetch after a save that failed', async () => {
    const ratings = ratingsFor();
    ratings.rate.mockResolvedValue(false);
    await mount({ userRole: 'ADMIN', ratings });
    await expand();
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[3]); });
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
  });

  it('does not fetch after a save when the list was never opened (the first expand will)', async () => {
    await mount({ userRole: 'ADMIN', ratings: ratingsFor() });
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[0]); });
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('a save that lands after the admin moved to another place re-fetches nothing', async () => {
    const ratings = ratingsFor();
    let landA;
    ratings.rate.mockImplementationOnce(() => new Promise((resolve) => { landA = resolve; }));
    const { rerender } = await mount({ userRole: 'ADMIN', ratings });
    await expand();
    await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[2]); });

    const placeB = { ...LOCATION, id: 8, name: 'Seahouses' };
    await act(async () => { rerender(element({ userRole: 'ADMIN', ratings, location: placeB })); });
    fetchAllRatingsForSlot.mockClear();
    await expand();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledWith(8, YESTERDAY, 'SUNSET');
    fetchAllRatingsForSlot.mockClear();

    await act(async () => { landA(true); });
    // A's save belongs to a slot the admin has left: B's list is not asked for again on its account.
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('collapses (and forgets the rows) when the WINDOW changes with the place unchanged', async () => {
    const ratings = ratingsFor();
    const both = new Map([[YESTERDAY, {
      sunrise: { solarEventTime: `${YESTERDAY}T04:30:00` },
      sunset: { solarEventTime: `${YESTERDAY}T20:10:00` },
    }]]);
    const { rerender } = await mount({
      userRole: 'ADMIN', ratings, location: { ...LOCATION, forecastsByDate: both },
    });
    await expand();
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');

    const sunrise = { ...PAST_EVENT, id: `solar:${YESTERDAY}:SUNRISE`, eventType: 'SUNRISE' };
    await act(async () => {
      rerender(element({
        userRole: 'ADMIN', ratings, event: sunrise, location: { ...LOCATION, forecastsByDate: both },
      }));
    });
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveTextContent(/^All ratings\s*▾$/);
    fetchAllRatingsForSlot.mockClear();
    await expand();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledWith(7, YESTERDAY, 'SUNRISE');
  });

  it('collapses when the PLACE changes with the window unchanged', async () => {
    const ratings = ratingsFor();
    const { rerender } = await mount({ userRole: 'ADMIN', ratings });
    await expand();
    await act(async () => {
      rerender(element({ userRole: 'ADMIN', ratings, location: { ...LOCATION, id: 8, name: 'Seahouses' } }));
    });
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
  });

  it('a failed fetch is a quiet line and the box stays openable', async () => {
    fetchAllRatingsForSlot.mockRejectedValue(new Error('502'));
    await mount({ userRole: 'ADMIN' });
    await expand();
    expect(screen.getByTestId('outcome-admin-state')).toHaveTextContent('Couldn\'t load ratings.');
    await expand();
    await expand();
    // Re-opening after the failure tries again.
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
  });

  it('keeps the box where it was across the Change / Save cycle of the reader\'s own block', async () => {
    const ratings = ratingsFor({ mine: [[KEY, { rating: 3, updatedAt: 'x' }]] });
    await mount({ userRole: 'ADMIN', ratings });
    await expand();
    expect(block()).toHaveAttribute('data-state', 'rated');
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(block()).toHaveAttribute('data-state', 'changing');
    // The list, held by the callout, is still open and still has its rows after the layout swap.
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByTestId('outcome-admin-row')).toHaveLength(2);
  });

  describe('repaints the card (plan §1 #3)', () => {
    it('re-measures when the box opens', async () => {
      await mount({ userRole: 'ADMIN' });
      const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
      const before = spy.mock.calls.length;
      await act(async () => { fireEvent.click(toggle()); });
      expect(spy.mock.calls.length).toBeGreaterThan(before);
    });

    it('re-measures when the rows land after the open (the list is taller than its loading line)', async () => {
      let land;
      fetchAllRatingsForSlot.mockReturnValue(new Promise((resolve) => { land = resolve; }));
      await mount({ userRole: 'ADMIN' });
      await expand();
      const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
      const before = spy.mock.calls.length;
      await act(async () => { land(answer()); });
      expect(spy.mock.calls.length).toBeGreaterThan(before);
    });

    it('re-measures when a re-fetch grows the list with nothing else changing (the row count is part of the layout)', async () => {
      await mount({ userRole: 'ADMIN', ratings: ratingsFor() });
      await expand();
      expect(screen.getAllByTestId('outcome-admin-row')).toHaveLength(2);
      const spy = vi.spyOn(currentMap, 'latLngToContainerPoint');
      fetchAllRatingsForSlot.mockResolvedValue(answer(['jgregory', 'mhart', 'zoe']));
      await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[3]); });
      // Settle the re-fetch, then count only what the list's growth caused.
      expect(screen.getAllByTestId('outcome-admin-row')).toHaveLength(3);
      spy.mockClear();
      fetchAllRatingsForSlot.mockResolvedValue(answer(['jgregory', 'mhart', 'zoe', 'pat']));
      await act(async () => { fireEvent.click(within(block()).getAllByTestId('outcome-rating-star')[2]); });
      expect(screen.getAllByTestId('outcome-admin-row')).toHaveLength(4);
      expect(spy.mock.calls.length).toBeGreaterThan(0);
    });

    it('control: a PRO reader\'s card has no list to repaint for', async () => {
      await mount({ userRole: 'PRO_USER' });
      expect(screen.queryByTestId('outcome-admin-list')).toBeNull();
    });
  });
});
