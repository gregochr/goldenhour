import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { storageKey, writeSwrCache } from '../utils/swrCache.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));

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
    slots: [{ locationName: 'Bamburgh', solarEventTime: `${dateStr}T20:11:00`, claudeRating: 4 }],
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
  const { briefing, loading, railTiles, windowCards, evaluationScores } = useWindowFirstBriefing();
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="generated">{briefing?.generatedAt ?? 'none'}</span>
      <span data-testid="tiles">{railTiles.length}</span>
      <span data-testid="labels">{railTiles.map((t) => t.peakLabel).join('|')}</span>
      <span data-testid="dates">{railTiles.map((t) => t.date).join('|')}</span>
      <span data-testid="scores">{[...evaluationScores.keys()].join('|') || 'none'}</span>
      <span data-testid="cards">{windowCards.length}</span>
      <span data-testid="card-keys">{windowCards.map((c) => c.key).join('|')}</span>
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

  it('degrades to an empty rail rather than throwing with no provider above it', () => {
    // The context default. A consumer rendered outside the provider — in a test, or after a badly
    // ordered refactor — should render nothing, not crash the arm.
    render(<Consumer />);
    expect(screen.getByTestId('tiles')).toHaveTextContent('0');
    expect(screen.getByTestId('generated')).toHaveTextContent('none');
  });
});
