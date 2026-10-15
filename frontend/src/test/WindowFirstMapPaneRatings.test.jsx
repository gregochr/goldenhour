/**
 * `components/WindowFirstMapPane.jsx` — what the pane does for the outcome-rating block
 * (outcome-rating-plan.md §2 #5 "The data", §3 O4a step 1): mounts `useOutcomeRatings`, reads the
 * reader's own ratings from the oldest PAST date it knows of through today, re-reads when an older
 * day lands and when the forecasts refresh, hands `MapView` one object, and offers nothing at all
 * while a rewind is in force (§5 #6). `MapView` is a stub; `useOutcomeRatings.test.js` owns the hook.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const MapStub = { lastProps: null };
vi.mock('../components/MapView.jsx', () => ({
  default: (props) => {
    MapStub.lastProps = props;
    return <div data-testid="stub-map" />;
  },
}));
let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));
let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import { fetchMyRatings, putRating } from '../api/outcomeRatingApi.js';
import { setRewind } from '../utils/rewind.js';

const TODAY = '2026-08-11';

function context(overrides = {}) {
  return {
    heatSpots: [],
    heatPointSets: new Map(),
    heatStripCards: [],
    reachById: new Map(),
    homePlace: null,
    todayStr: TODAY,
    ...overrides,
  };
}

const LOCATIONS = [];

async function renderPane(props = {}) {
  briefingValue = context();
  let result;
  await act(async () => {
    result = render(
      <WindowFirstMapPane
        locations={LOCATIONS}
        dates={['2026-08-12', TODAY, '2026-08-10', '2026-08-09']}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
        {...props}
      />,
    );
  });
  return result;
}

beforeEach(() => {
  MapStub.lastProps = null;
  mockRole = 'PRO_USER';
  fetchMyRatings.mockResolvedValue([]);
});
afterEach(() => {
  // Unmount BEFORE clearing the rewind: the pane subscribes to it, and a still-mounted pane
  // re-rendering against a nulled provider value is an unhandled error.
  cleanup();
  setRewind(null);
  briefingValue = null;
  vi.clearAllMocks();
});

describe('WindowFirstMapPane — the reader\'s outcome ratings', () => {
  it('reads from the OLDEST PAST date the pane knows of through today — never into the future', async () => {
    await renderPane();
    expect(fetchMyRatings).toHaveBeenCalledTimes(1);
    expect(fetchMyRatings).toHaveBeenCalledWith('2026-08-09', TODAY);
  });

  it('reads just today when no past day is known', async () => {
    await renderPane({ dates: [TODAY, '2026-08-12'] });
    expect(fetchMyRatings).toHaveBeenCalledWith(TODAY, TODAY);
  });

  it('re-reads from the older date when an older day lands', async () => {
    const { rerender } = await renderPane();
    await act(async () => {
      rerender(
        <WindowFirstMapPane
          locations={LOCATIONS}
          dates={['2026-08-08', '2026-08-09', '2026-08-10', TODAY, '2026-08-12']}
          selectedDate={TODAY}
          onSelectDate={vi.fn()}
        />,
      );
    });
    expect(fetchMyRatings).toHaveBeenLastCalledWith('2026-08-08', TODAY);
    expect(fetchMyRatings).toHaveBeenCalledTimes(2);
  });

  it('re-reads when the forecasts refresh (a new `locations` array), and not on an unrelated render', async () => {
    const { rerender } = await renderPane();
    const same = (locations) => (
      <WindowFirstMapPane
        locations={locations}
        dates={['2026-08-09', '2026-08-10', TODAY, '2026-08-12']}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
      />
    );
    await act(async () => { rerender(same(LOCATIONS)); });
    expect(fetchMyRatings).toHaveBeenCalledTimes(1);
    await act(async () => { rerender(same([])); });
    expect(fetchMyRatings).toHaveBeenCalledTimes(2);
  });

  it('hands MapView ONE object: loaded once the read lands, the reader\'s rows by key, and the actions', async () => {
    fetchMyRatings.mockResolvedValue([{
      locationId: 7, eventDate: '2026-08-10', eventType: 'SUNSET', rating: 4, updatedAt: 'x',
    }]);
    await renderPane();
    const ratings = MapStub.lastProps.outcomeRatings;
    expect(ratings.loaded).toBe(true);
    expect(ratings.mine.get('7|2026-08-10|SUNSET').rating).toBe(4);
    expect(typeof ratings.rate).toBe('function');
    expect(typeof ratings.clearSaveError).toBe('function');
    expect(ratings.covers('2026-08-10')).toBe(true);
    expect(ratings.covers('2026-08-01')).toBe(false);
  });

  it('a Pro reader\'s rate() PUTs; the object is handed through so MapView and the callout write through it', async () => {
    putRating.mockResolvedValue({ rating: 3, updatedAt: 'x' });
    await renderPane();
    await act(async () => { await MapStub.lastProps.outcomeRatings.rate(7, '2026-08-10', 'SUNSET', 3); });
    expect(putRating).toHaveBeenCalledWith({
      locationId: 7, eventDate: '2026-08-10', eventType: 'SUNSET', rating: 3,
    });
  });

  it('a Lite reader reads once and can never write', async () => {
    mockRole = 'LITE_USER';
    await renderPane();
    expect(fetchMyRatings).toHaveBeenCalledTimes(1);
    await act(async () => { await MapStub.lastProps.outcomeRatings.rate(7, '2026-08-10', 'SUNSET', 3); });
    expect(putRating).not.toHaveBeenCalled();
  });

  it('offers NOTHING while a rewind is in force: no read, and MapView is handed null', async () => {
    setRewind('2026-08-10T21:00:00Z');
    await renderPane();
    expect(fetchMyRatings).not.toHaveBeenCalled();
    expect(MapStub.lastProps.outcomeRatings).toBeNull();
  });

  it('ends the offer when a rewind begins mid-session', async () => {
    await renderPane();
    expect(MapStub.lastProps.outcomeRatings).not.toBeNull();
    await act(async () => { setRewind('2026-08-10T21:00:00Z'); });
    expect(MapStub.lastProps.outcomeRatings).toBeNull();
  });
});
