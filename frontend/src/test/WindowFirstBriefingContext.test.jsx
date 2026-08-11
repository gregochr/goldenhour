import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { storageKey, writeSwrCache } from '../utils/swrCache.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

const CACHE_KEY = 'briefing:PRO_USER';

/** A payload whose one rated day the rail can roll up. Dates are computed so "today" is real. */
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
    }],
  };
}

/** Today in the forecast's own timezone — the provider derives its labels the same way. */
function londonToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
}

/** A real consumer rather than a bespoke harness: this is what the shell reads. */
function Consumer() {
  const {
    briefing, loading, railTiles, windowCards, evaluationScores, reachLens: lens, homePlace,
    promotedStrip,
  } = useWindowFirstBriefing();
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
      {lens && <WindowFirstLensBar lens={lens} spotCount={0} windowCount={0} />}
    </div>
  );
}

/**
 * A payload spanning `dayCount` days, both solar events on each, with the times the real backend
 * sends (UTC, sunrise then sunset). Exists to exercise the event selection, which `payloadFor`'s
 * single event cannot: with one event on one day neither the past filter nor the six-event cap is
 * discriminating, so both were deletable with the suite green.
 */
function multiDayPayload(startDate, dayCount) {
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
    days.push({ date, eventSummaries: [evt('SUNRISE', '04:15:00'), evt('SUNSET', '20:11:00')] });
  }
  return { generatedAt: `${startDate}T06:00:00`, days };
}

const renderProvider = () => render(
  <WindowFirstBriefingProvider><Consumer /></WindowFirstBriefingProvider>,
);

/**
 * A fixed instant, because "upcoming" is a fact about the clock and this suite asserts it.
 *
 * <p>The fixture's events sit at 20:11Z. Run for real between 20:41Z (that time plus the 30-minute
 * afterglow) and the London date roll, `selectUpcomingEvents` drops every event, the rail is empty
 * and every test here fails — a suite that only passes before nine in the evening. Frozen at
 * mid-morning the day's events are unambiguously ahead, and the past-event cases below can state
 * their own time rather than inheriting one.
 */
const NOON_ISH = new Date('2026-08-04T09:00:00Z');

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
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(NOON_ISH);
  });

  afterEach(() => vi.useRealTimers());

  it('fetches the briefing once on mount and derives the rail from it', async () => {
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
    renderProvider();

    expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
    expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);
  });

  it('paints instantly from the cache, so a cold mount is not an empty rail', async () => {
    // The briefing IS the page here; waiting a round-trip to draw anything is the difference
    // between a rail that is there and one that appears.
    const today = londonToday();
    writeSwrCache(CACHE_KEY, payloadFor(today));
    getDailyBriefing.mockReturnValue(new Promise(() => {})); // never resolves
    renderProvider();

    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
  });

  it('keys the cache by role, so one account never paints another\'s briefing', async () => {
    const today = londonToday();
    writeSwrCache('briefing:LITE_USER', payloadFor(today));
    getDailyBriefing.mockReturnValue(new Promise(() => {}));
    renderProvider(); // mounts as PRO_USER

    expect(screen.getByTestId('tiles')).toHaveTextContent('0');
  });

  it('ignores an empty revalidation rather than blanking a good rail', async () => {
    // /api/briefing answers 204 when nothing is cached server-side, and the client turns that into
    // null. Storing it would clear the rail AND poison the SWR entry for the next cold start.
    const today = londonToday();
    writeSwrCache(CACHE_KEY, payloadFor(today));
    getDailyBriefing.mockResolvedValue(null);
    renderProvider();

    await act(async () => {});
    expect(screen.getByTestId('tiles')).toHaveTextContent('1');
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('keeps what is on screen when the fetch rejects', async () => {
    const today = londonToday();
    writeSwrCache(CACHE_KEY, payloadFor(today));
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
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
    renderProvider();
    await act(async () => {});

    const stored = JSON.parse(localStorage.getItem(storageKey(CACHE_KEY)));
    expect(stored.value.days).toHaveLength(1);
  });

  it('marks a travel day Away instead of rolling it up as a forecast', async () => {
    // Without the travel ranges an away day rolls up from whatever the briefing happens to hold,
    // which says the forecast was assessed when in fact none was run.
    const today = londonToday();
    getDailyBriefing.mockResolvedValue(payloadFor(today));
    fetchTravelDayRanges.mockResolvedValue([{ startDate: today, endDate: today }]);
    renderProvider();

    expect(await screen.findByText('✈ Away')).toBeInTheDocument();
  });

  it('still renders the rail when the travel-day fetch fails', async () => {
    // Travel days are an overlay on the forecast, not a precondition for it. A failure there must
    // not take the whole rail with it.
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
    fetchTravelDayRanges.mockRejectedValue(new Error('nope'));
    renderProvider();

    expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
  });

  it('re-fetches on window focus, so a laptop reopened at dawn is not showing last night', async () => {
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
    renderProvider();
    await act(async () => {});
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
  });

  it('polls every ten minutes, matching the payload\'s own regeneration rate', async () => {
    vi.useFakeTimers();
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
    renderProvider();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
  });

  it('tears down its poll and its focus listener on unmount', async () => {
    // The arm unmounts whenever the flag is switched back. A surviving interval would keep polling
    // /api/briefing alongside DailyBriefing's own, for a subtree nobody is looking at.
    vi.useFakeTimers();
    getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
    const today = londonToday();
    getDailyBriefing.mockResolvedValueOnce(payloadFor(today, { generatedAt: `${today}T12:00:00` }));
    renderProvider();
    await act(async () => {});
    expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');

    const reserved = payloadFor(today, { generatedAt: `${today}T12:00:00` }); // SAME build
    reserved.days[0].eventSummaries[0].regions[0].displayVerdict = 'MAYBE';
    getDailyBriefing.mockResolvedValue(reserved);

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('labels')).toHaveTextContent('Maybe · sunset');
  });

  it('picks up a genuinely newer forecast', async () => {
    const today = londonToday();
    getDailyBriefing.mockResolvedValueOnce(payloadFor(today, { generatedAt: `${today}T12:00:00` }));
    renderProvider();
    await act(async () => {});

    const newer = payloadFor(today, { generatedAt: `${today}T18:00:00` });
    newer.days[0].eventSummaries[0].regions[0].displayVerdict = 'MAYBE';
    getDailyBriefing.mockResolvedValue(newer);

    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('labels')).toHaveTextContent('Maybe · sunset');
  });

  describe('which events reach the rail', () => {
    it('drops a solar event that has already happened', async () => {
      // Frozen at 09:00Z the 04:15Z sunrise is past (plus its 30-minute afterglow) and the 20:11Z
      // sunset is not, so the day rolls up from the sunset alone and reads "· sunset". Delete the
      // past filter and both events are rated, so it reads "· both" — which is what makes this
      // assertion discriminating rather than decorative.
      getDailyBriefing.mockResolvedValue(multiDayPayload('2026-08-04', 1));
      renderProvider();
      await act(async () => {});

      expect(screen.getByTestId('labels')).toHaveTextContent('Worth it · sunset');
    });

    it('spans four days when the first live event is a sunset, three when it is a sunrise', async () => {
      // The six-event cap sits on a parity boundary, because every day always carries exactly two
      // events. Before the day's sunrise the six events cover three dates; after it, four. Both are
      // correct — the rail draws the days that have windows — but the count is not fixed at four,
      // and nothing pinned either side of the boundary.
      getDailyBriefing.mockResolvedValue(multiDayPayload('2026-08-04', 5));

      vi.setSystemTime(new Date('2026-08-04T09:00:00Z')); // after sunrise: SS,SR,SS,SR,SS,SR
      const first = renderProvider();
      await act(async () => {});
      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06|2026-08-07',
      );
      first.unmount();

      vi.setSystemTime(new Date('2026-08-04T02:00:00Z')); // before sunrise: SR,SS,SR,SS,SR,SS
      renderProvider();
      await act(async () => {});
      expect(screen.getByTestId('dates')).toHaveTextContent('2026-08-04|2026-08-05|2026-08-06');
    });

    it('does not spend an event slot on an elapsed event whose slots were withdrawn', async () => {
      // v1's defect, in v2's copy of the same selector. BriefingHonestyFilter empties the slot
      // list of a zero-coverage region, and the slots carried the event's time — so an elapsed
      // sunrise read as upcoming, took one of the six slots, and the window lost a day off its
      // far end. This fixture is the harder half of it: a payload cached before the backend
      // carried solarEventTime, where the date is the only evidence the event has happened.
      const payload = multiDayPayload('2026-08-04', 5);
      payload.days[0].eventSummaries = payload.days[0].eventSummaries.map((es) => ({
        ...es,
        regions: [{ ...es.regions[0], displayVerdict: 'STAND_DOWN', scoredLocationCount: 0, slots: [] }],
      }));

      getDailyBriefing.mockResolvedValue(payload);
      // 14:00 London. Past noon, which is all the floor has to go on — with no time in the
      // payload it cannot know the sunrise was at 04:15, so it uses the same noon boundary
      // BriefingHierarchyBuilder uses to tell a sunrise from a sunset. Deliberately coarse: at
      // 10:00 this same fixture still counts the sunrise as current, and that is the safe
      // direction to be wrong in.
      vi.setSystemTime(new Date('2026-08-04T13:00:00Z'));
      renderProvider();
      await act(async () => {});

      // Drop the date argument in selectUpcomingEvents and this stops at 2026-08-06.
      expect(screen.getByTestId('dates')).toHaveTextContent(
        '2026-08-04|2026-08-05|2026-08-06|2026-08-07',
      );
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
      const today = londonToday();
      getDailyBriefing.mockResolvedValue(multiDayPayload(today, 2));
      fetchTravelDayRanges.mockResolvedValue([{ startDate: today, endDate: today }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('card-keys').textContent).not.toContain(today);
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getAllEvaluationScores.mockResolvedValue([
        { regionName: 'N&T', date: '2026-08-04', targetType: 'SUNSET', locationName: 'Bamburgh', rating: 4 },
      ]);
      renderProvider();

      expect(await screen.findByText('N&T|2026-08-04|SUNSET|Bamburgh')).toBeInTheDocument();
    });

    it('skips a row missing the region or the location that keys it', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getAllEvaluationScores.mockRejectedValue(new Error('nope'));
      renderProvider();

      expect(await screen.findByText('Worth it · sunset')).toBeInTheDocument();
    });
  });

  describe('per-user reach — the spot strip\'s second contract', () => {
    it('joins reach onto the window\'s spots by location id', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 41, distanceMiles: 19 }]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('41/19');
    });

    it('fetches it from its own endpoint, never from the briefing', async () => {
      // Plan §2.2: /api/briefing is ETag-revalidated, which persists the body to a browser HTTP
      // cache JavaScript cannot evict on logout. Drive times must not ride it.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      renderProvider();

      await act(async () => {});
      expect(getReach).toHaveBeenCalledTimes(1);
    });

    it('never writes reach to the SWR cache, which is keyed by role and shared between users', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getReach.mockResolvedValue([{ locationId: 7, driveMinutes: 41, distanceMiles: 19 }]);
      renderProvider();

      await act(async () => {});
      expect(Object.keys(localStorage).join(',')).not.toMatch(/reach/i);
      expect(localStorage.getItem(storageKey(CACHE_KEY))).not.toMatch(/driveMinutes/);
    });

    it('renders every spot without its reach line when the request fails', async () => {
      // Indistinguishable from the first-run state — no home postcode — which is the normal one.
      // The strip must still render; a lens with no data is not a gate.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getReach.mockRejectedValue(new Error('nope'));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spots')).toHaveTextContent('Bamburgh');
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('null/null');
    });

    it('renders every spot without its reach line when the roster is empty', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getReach.mockResolvedValue([]);
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('spot-reach')).toHaveTextContent('null/null');
    });

    it('ignores a reach entry with no location id while keeping the ones beside it', async () => {
      // Both halves in one payload deliberately. With only the malformed entry the test passes
      // whether the loop skips it or abandons the whole list, so the good entry is what makes the
      // `continue` load-bearing.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('lens-locked')).toHaveTextContent('false');
      expect(screen.getByTestId('lens-tier')).toHaveTextContent('45');
    });

    it('does not refetch on an ordinary re-render', async () => {
      // The counter is the signal, not every render. Refetching on each one would put an
      // uncacheable per-user request behind the 10-minute poll and every focus event.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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

  describe('the home the reach figures are measured from', () => {
    it('prefers the resolved place name, which is what the design\'s slot reads', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth' });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth');
    });

    it('falls back to the postcode when the lookup resolved no place name', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: null });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('NE61 1AA');
    });

    it('says "no home" only on a response that actually said so', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('null');
    });

    it('stays unknown when the settings request fails, rather than claiming no home', async () => {
      // Plan §2.5 refuses a second source of truth for this, so a dropped request has no other
      // answer to fall back on — and "Home not set" shown to a user who set one is a false claim
      // where silence costs nothing.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getSettings.mockRejectedValue(new Error('nope'));
      renderProvider();

      await act(async () => {});
      expect(screen.getByTestId('home-place')).toHaveTextContent('unknown');
    });

    it('never rides the briefing payload, which is ETag-revalidated', async () => {
      // Plan §2.2. The postcode is per-user data and the briefing body is persisted to a browser
      // HTTP cache JavaScript cannot evict on logout.
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
      getSettings.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth' });
      renderProvider();

      await act(async () => {});
      expect(getSettings).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem(storageKey(CACHE_KEY))).not.toMatch(/Morpeth|NE61/);
    });

    it('refetches when the user saves a home, without a page reload', async () => {
      getDailyBriefing.mockResolvedValue(payloadFor(londonToday()));
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
      const payload = payloadFor(londonToday());
      payload.days[0].eventSummaries[0].window = {
        ...payload.days[0].eventSummaries[0].window, badges, topRarityRank,
      };
      return payload;
    };

    it('derives no strip from a payload whose windows carry no coincidence', async () => {
      getDailyBriefing.mockResolvedValue(withBadges([topic('AURORA', 'Aurora', 4)], 4));
      renderProvider();

      expect(await screen.findByTestId('cards')).toHaveTextContent('1');
      expect(screen.getByTestId('promo')).toHaveTextContent('none');
    });

    // The provider is the only place the page-wide cap can live, so this is what proves the field is
    // actually wired: the shell's own tests inject a descriptor and would pass without it.
    it('derives one strip from a payload whose window carries two topics', async () => {
      getDailyBriefing.mockResolvedValue(
        withBadges([topic('AURORA', 'Aurora', 4), topic('KING_TIDE', 'King tide', 3)], 3),
      );
      renderProvider();

      expect(await screen.findByTestId('promo'))
        .toHaveTextContent(`${londonToday()}:SUNSET`);
      expect(screen.getByTestId('promo-topics')).toHaveTextContent('King tide|Aurora');
    });
  });
});
