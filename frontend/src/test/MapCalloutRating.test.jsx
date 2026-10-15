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

import MapCallout from '../components/map/MapCallout.jsx';
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
  mine = [], saving = [], saveError = [], loaded = true, coversDate = null,
} = {}) {
  return {
    loaded,
    // Which dates' ratings have been read — by default, every date once `loaded`.
    covers: coversDate ?? (() => loaded),
    canRate: true,
    mine: new Map(mine),
    saving: new Set(saving),
    saveError: new Set(saveError),
    rate: vi.fn().mockResolvedValue(true),
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
