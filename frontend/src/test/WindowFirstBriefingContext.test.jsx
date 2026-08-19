import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  render, screen, act, fireEvent, waitFor,
} from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { storageKey, writeSwrCache } from '../utils/swrCache.js';
import { PLAN_RATING_KEY } from '../utils/ratingLens.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

const CACHE_KEY = 'briefing:PRO_USER';

/**
 * The instant every test in this file runs at, installed by {@link freezeClock}.
 *
 * <p>The fixture's events sit at 20:11Z. Run against the real clock between 20:41Z (that time plus
 * the 30-minute afterglow) and the London date roll, `selectUpcomingEvents` drops every event, the
 * rail is empty and every test here fails — a suite that only passes before nine in the evening.
 * Frozen at mid-morning the day's events are unambiguously ahead, and the past-event cases below
 * can state their own time rather than inheriting one.
 */
const NOON_ISH = new Date('2026-08-04T09:00:00Z');

/**
 * The fixture's "today": the London date OF THE PINNED INSTANT, computed once and never read from
 * the clock.
 *
 * <p>It was `new Intl.DateTimeFormat(…).format(new Date())`, evaluated per call inside each test.
 * That happened to agree with {@link NOON_ISH} — `vi.useFakeTimers` fakes `Date`, and the
 * `beforeEach` freezes it before any test body runs — but the agreement rested on an invariant
 * nothing stated, in a file whose later blocks move the clock (`vi.setSystemTime`) and re-install
 * it. A fixture that asks the clock what day it is has cost this repo three separate defects; a
 * date derived from the instant the suite is pinned to cannot drift from it.
 */
const TODAY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(NOON_ISH);

/** A payload whose one rated day the rail can roll up, dated {@link TODAY}. */
function payloadFor(dateStr, { generatedAt = null } = {}) {
  const region = {
    regionName: 'Northumberland & Tyneside',
    displayVerdict: 'WORTH_IT',
    verdict: 'GO',
    summary: 'clear at 3 of 7',
    glossHeadline: 'Breaking clear',
    glossDetail: 'Low cloud clears.',
    regionTemperatureCelsius: 18,
    scoredLocationCount: 3,
    slots: [{
      locationId: 7, locationName: 'Bamburgh', solarEventTime: `${dateStr}T20:11:00`, claudeRating: 4,
    }],
  };
  return {
    generatedAt: generatedAt ?? `${dateStr}T12:00:00`,
    days: [{
      date: dateStr,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [region],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [], eventTime: `${dateStr}T20:11:00` },
      }],
      peak: {
        verdict: 'WORTH_IT',
        events: ['SUNSET'],
        regions: [{
          regionName: 'Northumberland & Tyneside',
          targetType: 'SUNSET',
          displayVerdict: 'WORTH_IT',
        }],
      },
    }],
    renderedEvents: [{ date: dateStr, targetType: 'SUNSET' }],
  };
}

/**
 * Re-serves the payload with the region's verdict moved — BOTH where the region states it and where
 * the day's peak does, because a real serve re-derives them together from one set of statistics.
 * Moving only the region would model a payload the backend cannot produce, and the rail (which now
 * renders the peak rather than rolling one up) would rightly ignore it.
 */
function reserved(dateStr, generatedAt, displayVerdict) {
  const payload = payloadFor(dateStr, { generatedAt });
  payload.days[0].eventSummaries[0].regions[0].displayVerdict = displayVerdict;
  payload.days[0].peak.verdict = displayVerdict;
  payload.days[0].peak.regions[0].displayVerdict = displayVerdict;
  return payload;
}

/**
 * Fake timers pinned to {@link NOON_ISH} — the only way this file starts a clock.
 *
 * <p>Two blocks below re-install timers mid-test to drop `shouldAdvanceTime`. Vitest carries the
 * frozen instant across that re-install (the new clock reads its start time from the `Date` the
 * old one already faked), so they are correct today — but nothing says so, and the difference
 * between inheriting a pin and inheriting the wall clock is invisible at the call site. Going
 * through here states it.
 *
 * @param {object} [options] sinon fake-timer options; defaults to advancing with real time
 */
function freezeClock(options = { shouldAdvanceTime: true }) {
  vi.useFakeTimers(options);
  vi.setSystemTime(NOON_ISH);
}

/** Every `heatSpots` array the Consumer has been handed, newest last. Cleared per test. */
const heatIdentities = [];

/** The same for `heatPointSets` — its memo is a separate one and needs its own pin. */
const heatPointIdentities = [];

/** A real consumer rather than a bespoke harness: this is what the shell reads. */
function Consumer() {
  const {
    briefing, loading, railTiles, windowCards, evaluationScores, reachLens: lens, ratingLens,
    homePlace, promotedStrip, heatSpots, heatPointSets,
  } = useWindowFirstBriefing();
  // Identity, not content: plan §5.4 requires the join to be memoised on its real inputs, and a
  // recomputation is invisible in the rendered text. Collected here rather than in a bespoke
  // harness so the assertion is about what the shell actually receives.
  heatIdentities.push(heatSpots);
  heatPointIdentities.push(heatPointSets);
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="generated">{briefing?.generatedAt ?? 'none'}</span>
      <span data-testid="tiles">{railTiles.length}</span>
      <span data-testid="labels">{railTiles.map((t) => t.peakLabel).join('|')}</span>
      <span data-testid="dates">{railTiles.map((t) => t.date).join('|')}</span>
      <span data-testid="scores">{[...evaluationScores.keys()].join('|') || 'none'}</span>
      <span data-testid="cards">{windowCards.length}</span>
      {/* One descriptor or nothing — the shape is the cap. */}
      <span data-testid="promo">{promotedStrip ? promotedStrip.windowKey : 'none'}</span>
      <span data-testid="promo-topics">
        {promotedStrip ? promotedStrip.topics.map((t) => t.label).join('|') : 'none'}
      </span>
      <span data-testid="card-keys">{windowCards.map((c) => c.key).join('|')}</span>
      <span data-testid="spots">{windowCards[0]?.spots?.map((s) => s.locationName).join('|') || 'none'}</span>
      <span data-testid="spot-reach">
        {windowCards[0]?.spots?.map((s) => `${s.driveMinutes}/${s.distanceMiles}`).join('|') || 'none'}
      </span>
      <span data-testid="spot-far">
        {windowCards[0]?.spots?.map((s) => String(s.far)).join('|') || 'none'}
      </span>
      <span data-testid="reach-total">{windowCards[0]?.reachTotal ?? 'none'}</span>
      <span data-testid="lens-tier">{lens?.tierId ?? 'none'}</span>
      <span data-testid="lens-locked">{String(lens?.locked)}</span>
      <span data-testid="home-place">{homePlace === undefined ? 'unknown' : String(homePlace)}</span>
      {/* The real pairing, not a bespoke control: the shell renders exactly this from exactly this
          value, and it is the only way to move the tier the way a user does. */}
      <span data-testid="lens-floor">{ratingLens?.floorId ?? 'none'}</span>
      <span data-testid="lens-min">{String(ratingLens?.minRating)}</span>
      <span data-testid="reached-total">{windowCards[0]?.reachedTotal ?? 'none'}</span>
      <span data-testid="heat-spots">{heatSpots.map((s) => s.name).join('|') || 'none'}</span>
      <span data-testid="heat-scores">
        {heatSpots.map((s) => s.scores.join(',')).join('|') || 'none'}
      </span>
      <span data-testid="heat-regions">{heatSpots.map((s) => s.regionName).join('|') || 'none'}</span>
      <span data-testid="heat-points">
        {[...heatPointSets.entries()]
          .map(([key, set]) => `${key}=${set.map((p) => p.name).join(',') || '-'}`)
          .join(' ') || 'none'}
      </span>
      {lens && ratingLens && (
        <WindowFirstLensBar
          lens={lens}
          ratingLens={ratingLens}
          spotCount={0}
          windowCount={0}
        />
      )}
    </div>
  );
}

/**
 * A payload spanning `dayCount` days, both solar events on each, with the times the real backend
 * sends (UTC, sunrise then sunset). Exists to exercise the event selection, which `payloadFor`'s
 * single event cannot: with one event on one day neither the past filter nor the six-event cap is
 * discriminating, so both were deletable with the suite green.
 */
function multiDayPayload(startDate, dayCount, servedAt = NOON_ISH.toISOString()) {
  const days = [];
  for (let i = 0; i < dayCount; i += 1) {
    const d = new Date(`${startDate}T12:00:00Z`);
    d.setUTCDate(d.getUTCDate() + i);
    const date = d.toISOString().slice(0, 10);
    const evt = (targetType, time) => ({
      targetType,
      regions: [{
        regionName: 'N',
        displayVerdict: 'WORTH_IT',
        verdict: 'GO',
        summary: '',
        slots: [{ locationName: 'Bamburgh', solarEventTime: `${date}T${time}`, claudeRating: 4 }],
      }],
      unregioned: [],
      window: { verdict: 'WORTH_IT', badges: [], eventTime: `${date}T${time}` },
    });
    days.push({
      date,
      eventSummaries: [evt('SUNRISE', '04:15:00'), evt('SUNSET', '20:11:00')],
      peak: {
        verdict: 'WORTH_IT',
        events: ['SUNRISE', 'SUNSET'],
        regions: [
          { regionName: 'N', targetType: 'SUNRISE', displayVerdict: 'WORTH_IT' },
          { regionName: 'N', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' },
        ],
      },
    });
  }
  return {
    generatedAt: `${startDate}T06:00:00`,
    days,
    renderedEvents: renderedEventsAt(days, servedAt),
  };
}

/**
 * The list the backend would publish for these days if it served them at {@code servedAt}: the
 * leading six events whose time has not passed (plus the 30-minute afterglow), in payload order.
 *
 * <p>Every test that moves the clock has to say which instant the PAYLOAD was served at, because
 * the two are now different questions. The backend's list is fixed when the response is built; the
 * client's own guard runs later, against the browser's clock, and only ever removes. Passing one
 * instant for both is how a stale-payload test would silently become a fresh-payload one.
 */
function renderedEventsAt(days, servedAt) {
  const now = new Date(servedAt).getTime();
  return days
    .flatMap((d) => d.eventSummaries.map((es) => ({ date: d.date, targetType: es.targetType, es })))
    .filter(({ es }) => new Date(`${es.window.eventTime}Z`).getTime() + 30 * 60 * 1000 >= now)
    .slice(0, 6)
    .map(({ date, targetType }) => ({ date, targetType }));
}

const renderProvider = () => render(
  <WindowFirstBriefingProvider><Consumer /></WindowFirstBriefingProvider>,
);

/** The same, with the roster App now hands the provider — the heat field's only geography. */
const renderProviderWithLocations = (locations) => render(
  <WindowFirstBriefingProvider locations={locations}><Consumer /></WindowFirstBriefingProvider>,
);

describe('WindowFirstBriefingProvider', () => {
  beforeEach(() => {
    localStorage.clear();
    mockRole = 'PRO_USER';
    getDailyBriefing.mockReset();
    fetchTravelDayRanges.mockReset();
    fetchTravelDayRanges.mockResolvedValue([]);
    getAllEvaluationScores.mockReset();
    getAllEvaluationScores.mockResolvedValue([]);
    getReach.mockReset();
    getReach.mockResolvedValue([]);
    getSettings.mockReset();
    getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
    heatIdentities.length = 0;
    heatPointIdentities.length = 0;
    freezeClock();
  });

  afterEach(() => vi.useRealTimers());

  it('fetches the briefing once on mount and derives the rail from it', async () => {
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    renderProvider();

    expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
    expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);
  });

  it('paints instantly from the cache, so a cold mount is not an empty rail', async () => {
    // The briefing IS the page here; waiting a round-trip to draw anything is the difference
    // between a rail that is there and one that appears.
    writeSwrCache(CACHE_KEY, payloadFor(TODAY));
    getDailyBriefing.mockReturnValue(new Promise(() => {})); // never resolves
    renderProvider();

    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
  });

  it('keys the cache by role, so one account never paints another\'s briefing', async () => {
    writeSwrCache('briefing:LITE_USER', payloadFor(TODAY));
    getDailyBriefing.mockReturnValue(new Promise(() => {}));
    renderProvider(); // mounts as PRO_USER

    expect(screen.getByTestId('tiles')).toHaveTextContent('0');
  });

  it('ignores an empty revalidation rather than blanking a good rail', async () => {
    // /api/briefing answers 204 when nothing is cached server-side, and the client turns that into
    // null. Storing it would clear the rail AND poison the SWR entry for the next cold start.
    writeSwrCache(CACHE_KEY, payloadFor(TODAY));
    getDailyBriefing.mockResolvedValue(null);
    renderProvider();

    await act(async () => {});
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('keeps what is on screen when the fetch rejects', async () => {
    writeSwrCache(CACHE_KEY, payloadFor(TODAY));
    getDailyBriefing.mockRejectedValue(new Error('offline'));
    renderProvider();

    await act(async () => {});
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
  });

  it('stops loading even when the very first fetch fails', async () => {
    // With no cache there is nothing to fall back to, so a stuck `loading` would leave the arm
    // showing a spinner for ever rather than its honest empty state.
    getDailyBriefing.mockRejectedValue(new Error('offline'));
    renderProvider();

    await act(async () => {});
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(screen.getByTestId('tiles')).toHaveTextContent('0');
  });

  it('writes a successful fetch back to the cache for the next cold start', async () => {
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    renderProvider();
    await act(async () => {});

    const stored = JSON.parse(localStorage.getItem(storageKey(CACHE_KEY)));
    expect(stored.value.days).toHaveLength(1);
  });

  it('marks a travel day Away instead of rolling it up as a forecast', async () => {
    // Without the travel ranges an away day rolls up from whatever the briefing happens to hold,
    // which says the forecast was assessed when in fact none was run.
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    fetchTravelDayRanges.mockResolvedValue([{ startDate: TODAY, endDate: TODAY }]);
    renderProvider();

    expect(await screen.findByText('✈ Away')).toBeInTheDocument();
  });

  it('still renders the rail when the travel-day fetch fails', async () => {
    // Travel days are an overlay on the forecast, not a precondition for it. A failure there must
    // not take the whole rail with it.
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    fetchTravelDayRanges.mockRejectedValue(new Error('nope'));
    renderProvider();

    expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
  });

  it('re-fetches on window focus, so a laptop reopened at dawn is not showing last night', async () => {
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    renderProvider();
    await act(async () => {});
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
  });

  it('polls every ten minutes, matching the payload\'s own regeneration rate', async () => {
    freezeClock({ shouldAdvanceTime: false });
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    renderProvider();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
  });

  it('tears down its poll and its focus listener on unmount', async () => {
    // The arm unmounts whenever the flag is switched back. A surviving interval would keep polling
    // /api/briefing alongside DailyBriefing's own, for a subtree nobody is looking at.
    freezeClock({ shouldAdvanceTime: false });
    getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
    const { unmount } = renderProvider();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    unmount();
    getDailyBriefing.mockClear();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30 * 60 * 1000);
      window.dispatchEvent(new Event('focus'));
    });
    expect(getDailyBriefing).not.toHaveBeenCalled();
  });

  it('picks up re-derived content even when generatedAt has not moved', async () => {
    // The trap this guards. generatedAt is the BUILD time, but the window projection, the picks and
    // each region's confidence are all computed at SERVE time — so two responses can carry the same
    // timestamp and different content. A rail memoised on generatedAt holds the stale one, and a
    // pick that moved between two serves of the same build would never appear.
    getDailyBriefing.mockResolvedValueOnce(payloadFor(TODAY, { generatedAt: `${TODAY}T12:00:00` }));
    renderProvider();
    await act(async () => {});
    expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');

    // SAME build, re-derived content.
    getDailyBriefing.mockResolvedValue(reserved(TODAY, `${TODAY}T12:00:00`, 'MAYBE'));

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('labels')).toHaveTextContent('Maybe · sunset');
  });

  it('picks up a genuinely newer forecast', async () => {
    getDailyBriefing.mockResolvedValueOnce(payloadFor(TODAY, { generatedAt: `${TODAY}T12:00:00` }));
    renderProvider();
    await act(async () => {});

    getDailyBriefing.mockResolvedValue(reserved(TODAY, `${TODAY}T18:00:00`, 'MAYBE'));

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('labels')).toHaveTextContent('Maybe · sunset');
  });

  describe('which events reach the rail', () => {
    // The ORDER and the six-event CAP are the backend's since Phase 3 — the projector publishes
    // `renderedEvents` and scopes the BEST/ALSO picks to exactly that list, so a pick can no longer
    // name a window with no card. What remains here is the stale-cache defence and the degrade, and
    // that is what these assert. The cap itself is pinned in PlanWindowProjectorTest.

    it('renders the backend list, in the order the backend gave it', async () => {
      // The pass-through. The payload deliberately lists its events in an order the client would
      // not choose — the second day before the first — so anything re-sorting or re-capping here
      // shows up rather than agreeing by luck.
      const payload = multiDayPayload('2026-08-04', 3);
      payload.renderedEvents = [
        { date: '2026-08-05', targetType: 'SUNSET' },
        { date: '2026-08-04', targetType: 'SUNSET' },
      ];
      getDailyBriefing.mockResolvedValue(payload);
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('dates')).toHaveTextContent('2026-08-05|2026-08-04');
    });

    it('drops an event that has passed since the payload was built', async () => {
      // The stale-cache guard, and the only aggregation-adjacent thing left in the provider. The
      // payload was served at 02:00Z when both events were live; the browser is now at 09:00Z, past
      // the 04:15Z sunrise and its 30-minute afterglow. So the day rolls up from the sunset alone
      // and reads "· sunset". Delete the guard and both are rated, so it reads "· both" — which is
      // what makes this assertion discriminating rather than decorative.
      getDailyBriefing.mockResolvedValue(
        multiDayPayload('2026-08-04', 1, '2026-08-04T02:00:00Z'),
      );
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');
    });

    it('spans four days when the first live event is a sunset, three when it is a sunrise', async () => {
      // The six-event horizon sits on a parity boundary, because every day always carries exactly
      // two events. Before the day's sunrise the six span three dates; after it, four. Both are
      // correct — the rail draws the days that have windows — and the count is not fixed at four.
      // The backend decides this now, so each arm serves a payload built at its own instant.
      vi.setSystemTime(new Date('2026-08-04T09:00:00Z')); // after sunrise: SS,SR,SS,SR,SS,SR
      getDailyBriefing.mockResolvedValue(
        multiDayPayload('2026-08-04', 5, '2026-08-04T09:00:00Z'),
      );
      const first = renderProvider();
      await act(async () => {});
      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06|2026-08-07',
      );
      first.unmount();

      vi.setSystemTime(new Date('2026-08-04T02:00:00Z')); // before sunrise: SR,SS,SR,SS,SR,SS
      getDailyBriefing.mockResolvedValue(
        multiDayPayload('2026-08-04', 5, '2026-08-04T02:00:00Z'),
      );
      renderProvider();
      await act(async () => {});
      expect(screen.getByTestId('dates')).toHaveTextContent('2026-08-04|2026-08-05|2026-08-06');
    });

    it('gives back the far day when the payload already excluded the elapsed event', async () => {
      // The healthy path, and the counterpart to the degrade below. A payload SERVED at 13:00 has
      // already dropped that morning's sunrise, so its six events reach a fourth date and the
      // client's guard removes nothing.
      getDailyBriefing.mockResolvedValue(
        multiDayPayload('2026-08-04', 5, '2026-08-04T13:00:00Z'),
      );
      vi.setSystemTime(new Date('2026-08-04T13:00:00Z'));
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06|2026-08-07',
      );
    });

    it('shows one day fewer, rather than refilling, when a listed event has since elapsed', async () => {
      // The deliberate cost of moving the cap to the backend, on the hardest payload there is:
      // BriefingHonestyFilter empties a zero-coverage region's slot list, and this fixture is one
      // cached before the summary carried its own solarEventTime — so the date is the only evidence
      // the sunrise has happened, and only the CLIENT has it. The provider drops that event; it
      // does NOT reach further down `days` to replace it, because refilling means re-implementing
      // the cap this phase deleted. Three dates rather than four, on a stale payload, self-healing
      // at the next poll. Under-showing is the safe direction.
      const payload = multiDayPayload('2026-08-04', 5, '2026-08-04T00:00:00Z');
      payload.days[0].eventSummaries = payload.days[0].eventSummaries.map((es) => ({
        ...es,
        window: { ...es.window, eventTime: undefined },
        regions: [{ ...es.regions[0], displayVerdict: 'STAND_DOWN', scoredLocationCount: 0, slots: [] }],
      }));

      getDailyBriefing.mockResolvedValue(payload);
      // 14:00 London. Past noon, which is all the floor has to go on — with no time in the payload
      // it cannot know the sunrise was at 04:15, so it uses the same noon boundary
      // BriefingHierarchyBuilder uses to tell a sunrise from a sunset.
      vi.setSystemTime(new Date('2026-08-04T13:00:00Z'));
      renderProvider();
      await act(async () => {});

      // The DATE span alone cannot see this — six events from 00:00 cover three dates whether or
      // not the sunrise is dropped, so a test asserting only dates passes with the guard deleted.
      // The card keys are what discriminate: five, starting at the sunset, not six starting at the
      // sunrise. And they are what says the far end did NOT move: no 2026-08-07.
      expect(screen.getByTestId('card-keys')).toHaveTextContent(
        '2026-08-04:SUNSET|2026-08-05:SUNRISE|2026-08-05:SUNSET|2026-08-06:SUNRISE|2026-08-06:SUNSET',
      );
      expect(screen.getByTestId('cards')).toHaveTextContent(/^5$/);
      expect(screen.getByTestId('dates')).toHaveTextContent('2026-08-04|2026-08-05|2026-08-06');
    });

    it('falls back to walking the days when the payload names no rendered events', async () => {
      // The deploy-window degrade: a payload cached before the field existed, or served by an older
      // backend. Returning nothing would blank the pane. Uncapped on purpose — the cap belongs to
      // the backend, and a client-side copy for the degrade is the constant this phase deleted.
      //
      // The day peak is deleted TOO, because that is the only payload production can actually
      // produce: both fields come from the same projector call, so one cannot be absent while the
      // other is present. Deleting only `renderedEvents` builds a shape the backend cannot emit and
      // hides what such a payload does to the rail — which is the whole of its user-visible effect.
      const payload = multiDayPayload('2026-08-04', 3);
      delete payload.renderedEvents;
      payload.days.forEach((d) => { delete d.peak; });
      getDailyBriefing.mockResolvedValue(payload);
      renderProvider();
      await act(async () => {});

      // Six events would stop at the 6th; the walk keeps going, so the third day survives.
      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06',
      );
      // And the tiles say they have no answer rather than asserting a stand-down. Such a payload
      // still carries its windows, so the CARDS read Worth it — "All poor" beside them would be
      // the exact contradiction this phase exists to remove.
      expect(screen.getByTestId('labels')).toHaveTextContent('Awaiting|Awaiting|Awaiting');
      expect(screen.getByTestId('labels').textContent).not.toMatch(/poor/i);
    });

    it('draws nothing when the backend published an empty list', async () => {
      // Empty is not absent. An entirely elapsed forecast says so, and must not be read as "the
      // projector did not run" and degrade into drawing every past window.
      const payload = multiDayPayload('2026-08-04', 3);
      payload.renderedEvents = [];
      getDailyBriefing.mockResolvedValue(payload);
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('tiles')).toHaveTextContent('0');
    });

    it('never draws more than four days however many the briefing carries', async () => {
      // The cap belongs to the rail, not to the event window: six events could in principle span
      // six dates if a day ever carried one event.
      getDailyBriefing.mockResolvedValue(multiDayPayload('2026-08-04', 5));
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('tiles')).toHaveTextContent('4');
    });
  });

  describe('the window cards', () => {
    it('derives a card for every window the rail rolled up, from ONE event evaluation', async () => {
      // The past-event rule and the six-event cap are applied once and shared. Run separately, the
      // rail and the pane could disagree about which windows exist — and the card's pick badge and
      // the rail's pick flag would then point at different sets.
      getDailyBriefing.mockResolvedValue(multiDayPayload('2026-08-04', 5));
      renderProvider();

      // 09:00Z: today's 04:15Z sunrise is past, so the six live events are SS,SR,SS,SR,SS,SR.
      expect(await screen.findByText(
        '2026-08-04:SUNSET|2026-08-05:SUNRISE|2026-08-05:SUNSET|2026-08-06:SUNRISE|2026-08-06:SUNSET|2026-08-07:SUNRISE',
      )).toBeInTheDocument();
      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06|2026-08-07',
      );
    });

    it('draws no card for a day the rail is showing as Away', async () => {
      // The travel day still carries slots, so the projector gives it a verdict — a naive card list
      // would put a "Poor" card under a rail tile reading "Not forecast".
      getDailyBriefing.mockResolvedValue(multiDayPayload(TODAY, 2));
      fetchTravelDayRanges.mockResolvedValue([{ startDate: TODAY, endDate: TODAY }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('card-keys').textContent).not.toContain(TODAY);
      // And the rail still shows the day, so the absence is explained on screen.
      expect(screen.getByTestId('labels')).toHaveTextContent('✈ Away');
    });

    it('is empty with no briefing, rather than undefined', () => {
      getDailyBriefing.mockReturnValue(new Promise(() => {}));
      renderProvider();
      expect(screen.getByTestId('cards')).toHaveTextContent('0');
    });
  });

  describe('the batch evaluation scores', () => {
    it('fetches them and exposes them keyed for the map handoff', async () => {
      // Not read by anything the rail draws — they feed the overlay's narrative and MapView's
      // visibility filter, both reachable from this arm for the first time. The key format is
      // load-bearing: buildBriefingScoreIndex splits on the last three `|` fields.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([
        { regionName: 'N&T', date: '2026-08-04', targetType: 'SUNSET', locationName: 'Bamburgh', rating: 4 },
      ]);
      renderProvider();

      expect(await screen.findByText('N&T|2026-08-04|SUNSET|Bamburgh')).toBeInTheDocument();
    });

    it('skips a row missing the region or the location that keys it', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([
        { regionName: null, date: '2026-08-04', targetType: 'SUNSET', locationName: 'Bamburgh', rating: 4 },
        { regionName: 'N&T', date: '2026-08-04', targetType: 'SUNSET', locationName: null, rating: 4 },
      ]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('scores')).toHaveTextContent('none');
    });

    it('still renders the rail when the scores fetch fails', async () => {
      // They are an input to a downstream surface, not a precondition for this one.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockRejectedValue(new Error('nope'));
      renderProvider();

      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
    });
  });

  describe('per-user reach — the spot strip\'s second contract', () => {
    it('joins reach onto the window\'s spots by location id', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 41, distanceMiles: 19 }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('41/19');
    });

    it('fetches it from its own endpoint, never from the briefing', async () => {
      // Plan §2.2: /api/briefing is ETag-revalidated, which persists the body to a browser HTTP
      // cache JavaScript cannot evict on logout. Drive times must not ride it.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      renderProvider();

      await act(async () => {});
      expect(getReach).toHaveBeenCalledTimes(1);
    });

    it('never writes reach to the SWR cache, which is keyed by role and shared between users', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 41, distanceMiles: 19 }]);
      renderProvider();

      await act(async () => {});
      expect(Object.keys(localStorage).join(',')).not.toMatch(/reach/i);
      expect(localStorage.getItem(storageKey(CACHE_KEY))).not.toMatch(/driveMinutes/);
    });

    it('renders every spot without its reach line when the request fails', async () => {
      // Indistinguishable from the first-run state — no home postcode — which is the normal one.
      // The strip must still render; a lens with no data is not a gate.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockRejectedValue(new Error('nope'));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('null/null');
    });

    it('renders every spot without its reach line when the roster is empty', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('null/null');
    });

    it('ignores a reach entry with no location id while keeping the ones beside it', async () => {
      // Both halves in one payload deliberately. With only the malformed entry the test passes
      // whether the loop skips it or abandons the whole list, so the good entry is what makes the
      // `continue` load-bearing.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([
        { driveMinutes: 99, distanceMiles: 99 },
        { locationId: 7, driveMinutes: 41, distanceMiles: 19 },
      ]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('41/19');
    });

    it('refetches when the user saves a home postcode, without a page reload', async () => {
      // The defect this guards is one this app has already shipped once, on the v1 arm: a bare []
      // dep list meant "a user who widened their radius saw the block keep its old contents until
      // a full reload — the setting appeared to do nothing". It is worse here, because the modal
      // that saves the postcode is a SIBLING of this provider in App — so it re-renders it and
      // never remounts it, and a first-run user would wait forever.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([]);
      const { rerender } = render(
        <WindowFirstBriefingProvider homeSettingsVersion={0}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('null/null');

      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 41, distanceMiles: 19 }]);
      rerender(
        <WindowFirstBriefingProvider homeSettingsVersion={1}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});

      expect(getReach).toHaveBeenCalledTimes(2);
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('41/19');
    });

    it('gates a spot beyond the day-derived tier, and marks nothing that survives it', async () => {
      // The frozen clock is a Tuesday, so the default is 45 min. This is the gate running for
      // real, end to end: the lens the provider derives reaching the cards it builds.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 66, distanceMiles: 47 }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-tier')).toHaveTextContent('45');
      expect(screen.getByTestId('spots')).toHaveTextContent('none');
      expect(screen.getByTestId('reach-total')).toHaveTextContent('1');
    });

    it('marks a spot the widened lens let through as beyond today\'s reach', async () => {
      // The pairing that makes the `far` mark meaningful: at the default nothing past it is on
      // screen, so the tint appears exactly when the user has widened to see it.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 66, distanceMiles: 47 }]);
      renderProvider();
      await act(async () => {});

      fireEvent.click(screen.getByRole('button', { name: 'Any' }));

      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spot-far')).toHaveTextContent('true');
    });

    it('pins a LITE user to Any, so the greyed control withholds no spot', async () => {
      // Plan §7 gates the bar; `useReachLens` decides what the gate then DOES, and this is the
      // half that is only observable from the provider — `role` enters the arm here and nowhere
      // below it.
      mockRole = 'LITE_USER';
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 66, distanceMiles: 47 }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-locked')).toHaveTextContent('true');
      expect(screen.getByTestId('lens-tier')).toHaveTextContent('any');
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
    });

    it('leaves a PRO user\'s control live', async () => {
      // The negative half. Without it the gating assertion above passes on a component that locks
      // every role, which is a strictly worse product than one that locks none.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-locked')).toHaveTextContent('false');
      expect(screen.getByTestId('lens-tier')).toHaveTextContent('45');
    });

    it('does not refetch on an ordinary re-render', async () => {
      // The counter is the signal, not every render. Refetching on each one would put an
      // uncacheable per-user request behind the 10-minute poll and every focus event.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      const { rerender } = render(
        <WindowFirstBriefingProvider homeSettingsVersion={3}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});
      rerender(
        <WindowFirstBriefingProvider homeSettingsVersion={3}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});

      expect(getReach).toHaveBeenCalledTimes(1);
    });
  });

  describe('the rating floor, the lens\'s second axis', () => {
    /** {@link payloadFor}'s one slot, plus a second the floor can act on. */
    const twoRatedSpots = (dateStr) => {
      const payload = payloadFor(dateStr);
      payload.days[0].eventSummaries[0].regions[0].slots.push({
        locationId: 8, locationName: 'Cresswell', solarEventTime: `${dateStr}T20:11:00`, claudeRating: 2,
      });
      return payload;
    };

    it('gates the cards from a STORED floor, which is the only path production has', async () => {
      // This provider is the sole site handing `minRating` to `buildWindowCards`, and that builder
      // derives no fallback from `floorId` — so dropping the one object key makes the floor filter
      // nothing while every chip on the bar still moves. The gate itself is pinned in
      // `windowFirstCards.test.js` and the control in `WindowFirstLensBar.test.jsx`; this is the
      // wire between them, and neither of those files can see it.
      localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '4' }));
      getDailyBriefing.mockResolvedValue(twoRatedSpots(TODAY));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-floor')).toHaveTextContent('4');
      expect(screen.getByTestId('lens-min')).toHaveTextContent('4');
      // Reach gated nothing (no drive times), so the pool is both spots and the floor took one.
      expect(screen.getByTestId('reached-total')).toHaveTextContent('2');
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spots')).not.toHaveTextContent('Cresswell');
    });

    it('gates nothing while the control sits on Any, so an untouched lens is a no-op', async () => {
      // The negative half. Without it the assertion above passes on a provider that floors
      // everything at 4 regardless of what is stored.
      getDailyBriefing.mockResolvedValue(twoRatedSpots(TODAY));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-floor')).toHaveTextContent('any');
      expect(screen.getByTestId('lens-min')).toHaveTextContent('null');
      expect(screen.getByTestId('spots')).toHaveTextContent('Cresswell');
    });

    it('drops an unrated spot, which the reach gate would have passed', async () => {
      // The two gates are deliberately not symmetrical, and this is the end-to-end proof: an
      // unknown drive time passes every tier, an unknown rating meets no floor.
      localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: '3' }));
      const payload = payloadFor(TODAY);
      payload.days[0].eventSummaries[0].regions[0].slots[0].claudeRating = null;
      getDailyBriefing.mockResolvedValue(payload);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spots')).toHaveTextContent('none');
      expect(screen.getByTestId('reached-total')).toHaveTextContent('1');
    });
  });

  describe('the home the reach figures are measured from', () => {
    it('prefers the resolved place name, which is what the design\'s slot reads', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth' });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth');
    });

    it('falls back to the postcode when the lookup resolved no place name', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: null });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('NE61 1AA');
    });

    it('says "no home" only on a response that actually said so', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('null');
    });

    it('stays unknown when the settings request fails, rather than claiming no home', async () => {
      // Plan §2.5 refuses a second source of truth for this, so a dropped request has no other
      // answer to fall back on — and "Home not set" shown to a user who set one is a false claim
      // where silence costs nothing.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockRejectedValue(new Error('nope'));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('unknown');
    });

    it('never rides the briefing payload, which is ETag-revalidated', async () => {
      // Plan §2.2. The postcode is per-user data and the briefing body is persisted to a browser
      // HTTP cache JavaScript cannot evict on logout.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth' });
      renderProvider();

      await act(async () => {});
      expect(getSettings).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem(storageKey(CACHE_KEY))).not.toMatch(/Morpeth|NE61/);
    });

    it('refetches when the user saves a home, without a page reload', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
      const { rerender } = render(
        <WindowFirstBriefingProvider homeSettingsVersion={0}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('null');

      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth' });
      rerender(
        <WindowFirstBriefingProvider homeSettingsVersion={1}><Consumer /></WindowFirstBriefingProvider>,
      );
      await act(async () => {});

      expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth');
    });
  });

  it('degrades to an empty rail rather than throwing with no provider above it', () => {
    // The context default. A consumer rendered outside the provider — in a test, or after a badly
    // ordered refactor — should render nothing, not crash the arm.
    render(<Consumer />);
    expect(screen.getByTestId('tiles')).toHaveTextContent('0');
    expect(screen.getByTestId('generated')).toHaveTextContent('none');
  });
  describe('the promoted strip', () => {
    /** A badge as `BriefingWindow.Badge` serialises one. */
    const topic = (type, label, rarityRank) => ({
      type, label, detail: null, eventTime: null, rarityRank,
      facts: [{ key: 'k', value: 'v', dir: null, emphasis: true, optional: false }],
    });

    /** `payloadFor`'s single day, with badges put on its one window. */
    const withBadges = (badges, topRarityRank) => {
      const payload = payloadFor(TODAY);
      payload.days[0].eventSummaries[0].window = {
        ...payload.days[0].eventSummaries[0].window, badges, topRarityRank,
      };
      return payload;
    };

    it('derives no strip from a payload whose windows carry no coincidence', async () => {
      getDailyBriefing.mockResolvedValue(withBadges([topic('AURORA', 'Aurora', 4)], 4));
      renderProvider();

      // Wait on the rail label, which only exists once the response has been folded in — NOT on
      // `cards`, which the consumer renders from the first paint. Waiting for an element that is
      // already on screen is satisfied before the fetch resolves, and this file's other blocks
      // failed the same way once already (see `frontend-test-standards.md` on SkyRatingEvalView).
      // Here it made a §6 clause 3 test pass vacuously in the exact shape that clause names: with
      // no cards drawn, "no strip" is true of an empty pane and says nothing about the rule. Under
      // full-suite load it lost the race outright and failed on `cards` reading 0.
      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
      expect(screen.getByTestId('cards')).toHaveTextContent('1');
      expect(screen.getByTestId('promo')).toHaveTextContent('none');
    });

    // The provider is the only place the page-wide cap can live, so this is what proves the field is
    // actually wired: the shell's own tests inject a descriptor and would pass without it.
    it('derives one strip from a payload whose window carries two topics', async () => {
      getDailyBriefing.mockResolvedValue(
        withBadges([topic('AURORA', 'Aurora', 4), topic('KING_TIDE', 'King tide', 3)], 3),
      );
      renderProvider();

      // Same reason as the sibling above: `promo` is on screen from the first paint reading
      // "none", so waiting for the element proves nothing about the fetch. This one has not been
      // seen to fail, and it is the same defect — it wins the race rather than avoiding it.
      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
      expect(screen.getByTestId('promo')).toHaveTextContent(`${TODAY}:SUNSET`);
      expect(screen.getByTestId('promo-topics')).toHaveTextContent('King tide|Aurora');
    });
  });

  /**
   * The heat field's data plumbing (plan P1). What is under test here is the WIRING — the pure
   * join has its own file (`heatSpots.test.js`). Three things can only be seen from the provider:
   * that the roster prop reaches the join at all, that the scores response is retained with its
   * `locationId` rather than only in the name-keyed map, and that the derivation is memoised.
   */
  describe('heat field catalogue', () => {
    /** The roster App hands in — `useForecasts`' shape, with `lon` rather than `lng`. */
    const ROSTER = [
      { id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.709, regionName: 'North East', bortleClass: 3 },
      { id: 8, name: 'Alnmouth', lat: 55.386, lon: -1.611, regionName: 'North East', bortleClass: 4 },
    ];

    /** A scores row for the fixture's single rendered window. */
    const scoreRow = (locationId, locationName, rating) => ({
      locationId,
      locationName,
      regionName: 'Northumberland & Tyneside',
      date: TODAY,
      targetType: 'SUNSET',
      rating,
      summary: 'Colour building.',
    });

    it('joins the roster to the scores response, one score per rendered window', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([
        scoreRow(7, 'Bamburgh', 4), scoreRow(8, 'Alnmouth', 2),
      ]);
      renderProviderWithLocations(ROSTER);

      // Wait on something the SCORES fetch gates, not the briefing: the spots exist from the
      // first paint (the roster is a prop) and would read 'Bamburgh|Alnmouth' before any score
      // arrived, so a wait on the names would be satisfied by an empty join. `waitFor` rather
      // than `findBy*` because the element is on screen throughout — it is its CONTENT the
      // fetch gates, which is not a findBy written the long way.
      await waitFor(() => expect(screen.getByTestId('heat-scores')).toHaveTextContent('4|2'));
      expect(screen.getByTestId('heat-spots')).toHaveTextContent('Bamburgh|Alnmouth');
      expect(screen.getByTestId('heat-regions')).toHaveTextContent('North East|North East');
    });

    it('joins on locationId, which the provider\'s name-keyed score map discards', async () => {
      // The actual P1 gap (§2.10): `evaluationScores` is keyed
      // `regionName|date|targetType|locationName` and the id is dropped on the way in. Rename the
      // location since it was scored and only the retained raw rows can still match it.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([scoreRow(7, 'Bamburgh', 5)]);
      renderProviderWithLocations([{ ...ROSTER[0], name: 'Bamburgh Castle' }]);

      await waitFor(() => expect(screen.getByTestId('heat-scores')).toHaveTextContent('5'));
      expect(screen.getByTestId('heat-spots')).toHaveTextContent('Bamburgh Castle');
    });

    it('keeps the name-keyed map intact beside the raw rows', async () => {
      // Retaining the rows must not cost the map-handoff path its own index — the two shapes
      // answer different questions and both are live.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([scoreRow(7, 'Bamburgh', 4)]);
      renderProviderWithLocations(ROSTER);

      expect(await screen.findByText(`Northumberland & Tyneside|${TODAY}|SUNSET|Bamburgh`))
        .toBeInTheDocument();
    });

    it('renders the rest of the arm and no spots when App hands it no roster', async () => {
      // The degrade that matters: `locations` arrives from a second fetch (`useForecasts`), so
      // there is a real window in which the briefing has painted and the roster has not.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([scoreRow(7, 'Bamburgh', 4)]);
      renderProvider();

      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
      expect(screen.getByTestId('heat-spots')).toHaveTextContent('none');
      expect(screen.getByTestId('cards')).toHaveTextContent('1');
    });

    it('keeps the roster and drops the field when the scores fetch fails', async () => {
      // §7.2's named degrade. The locations are still real places — the rail and the region list
      // want them — but nothing is scored, so every window's point set is empty and the kernel
      // is handed nothing rather than a field of zeroes.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockRejectedValue(new Error('502'));
      renderProviderWithLocations(ROSTER);

      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
      // The rejection leaves no trace in the rendered output, so waiting on the briefing proves
      // nothing about it: flush the microtask queue and assert the request was actually made,
      // or this test passes identically with the whole scores effect deleted.
      await act(async () => {});
      expect(getAllEvaluationScores).toHaveBeenCalled();
      expect(screen.getByTestId('heat-spots')).toHaveTextContent('Bamburgh|Alnmouth');
      // Exact, not substring: '|' is a substring of '4|2', so the loose form passed in the very
      // state this test exists to rule out.
      expect(screen.getByTestId('heat-scores').textContent).toBe('|');
      expect(screen.getByTestId('heat-points').textContent)
        .toBe(`${TODAY}:SUNSET=-`);
    });

    it('builds one point set per rendered window, holding only the spots scored in it', async () => {
      // A MULTI-window payload, because this file's own `multiDayPayload` comment records why a
      // single-event fixture is dangerous: with one window, a hard-coded count of 1 — or of 6 —
      // reads identically, and a substring assertion cannot tell which window a name landed in.
      // Bamburgh is scored at Thursday's sunrise only; Alnmouth at neither.
      const payload = multiDayPayload(TODAY, 2);
      // The SECOND rendered window, so a point landing in the first (or in all of them) fails.
      const scored = payload.renderedEvents[1];
      getDailyBriefing.mockResolvedValue(payload);
      getAllEvaluationScores.mockResolvedValue([{
        ...scoreRow(7, 'Bamburgh', 4), date: scored.date, targetType: scored.targetType,
      }]);
      renderProviderWithLocations(ROSTER);

      // Every rendered window has an entry, in render order; only the scored one carries a
      // point. Left in, an unscored spot would have its absent score read as ZERO by the kernel
      // and paint a confident 1★ patch around itself.
      const expected = payload.renderedEvents
        .map((e) => `${e.date}:${e.targetType}=${e === scored ? 'Bamburgh' : '-'}`)
        .join(' ');
      await waitFor(() => expect(screen.getByTestId('heat-points').textContent).toBe(expected));
      // The fixture really does have more than one window, or none of the above discriminates.
      expect(payload.renderedEvents.length).toBeGreaterThan(1);
    });

    it('does not rebuild the catalogue when an unrelated provider state changes', async () => {
      // Plan §5.4. The provider re-renders on every health tick, poll and focus event, and this
      // walks the whole roster against the whole score response. Moving the reach lens is the
      // cheapest real re-render to trigger — it changes provider state and touches no join input.
      getDailyBriefing.mockResolvedValue(payloadFor(TODAY));
      getAllEvaluationScores.mockResolvedValue([scoreRow(7, 'Bamburgh', 4)]);
      renderProviderWithLocations(ROSTER);
      await waitFor(() => expect(screen.getByTestId('heat-scores')).toHaveTextContent('4'));
      const settled = heatIdentities[heatIdentities.length - 1];
      const settledPoints = heatPointIdentities[heatPointIdentities.length - 1];

      fireEvent.click(screen.getByRole('button', { name: 'Any' }));

      expect(screen.getByTestId('lens-tier')).toHaveTextContent('any');
      // Identity, not deep equality: a recomputation returning an equal array would still
      // invalidate every downstream memo and repaint six canvases.
      expect(heatIdentities[heatIdentities.length - 1]).toBe(settled);
      // BOTH memos, because they are two `useMemo`s and the second was unpinned: removing it
      // returns a fresh Map per render, which is exactly the repaint this asserts against.
      expect(heatPointIdentities[heatPointIdentities.length - 1]).toBe(settledPoints);
    });
  });
});
