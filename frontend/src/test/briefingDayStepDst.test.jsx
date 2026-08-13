/**
 * "Today" and "Tomorrow" on the Plan screen, stepped across a DST boundary.
 *
 * ⚠️ TZ IS PINNED, AND `Europe/London` WOULD PROVE NOTHING HERE. The defect this file guards is a
 * *hybrid* day step: `DailyBriefing` and `WindowFirstBriefingContext` each carried a private
 * `londonDate(offset)` that stepped the BROWSER's calendar (`d.setDate(d.getDate() + offset)`) and
 * only then formatted the result in `Europe/London`. Formatting in the right zone is not enough —
 * the step has to happen on the same calendar as the format, or the two disagree by a day whenever
 * a DST transition sits between them. Under `Europe/London` they are the same calendar, so every
 * assertion below would pass against the broken helper too. Only a zone that disagrees with the UK
 * separates them, and that disagreement is asserted outright in the first block rather than assumed.
 *
 * UTC is the pin because it is the zone GitHub's runners already use: this defect could have
 * shipped through a green CI run, and now cannot. `mapDatesAbroad.test.js` argues the same point at
 * `America/New_York`, where the same helpers are wrong all day rather than only at a boundary.
 *
 * Do not "harmonise" this file onto Europe/London — that deletes the only coverage of the defect.
 */
process.env.TZ = 'UTC';

import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
  getCloseToHome: vi.fn(),
}));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn(),
  getReach: vi.fn(),
  getSettings: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/hotTopicSimulationApi.js', () => ({ getSimulationState: vi.fn() }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: vi.fn() }));

import DailyBriefing from '../components/DailyBriefing.jsx';
import {
  WindowFirstBriefingProvider, useWindowFirstBriefing,
} from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing, getCloseToHome } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getDriveTimes, getReach, getSettings } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getSimulationState } from '../api/hotTopicSimulationApi.js';
import { useAuth } from '../context/AuthContext.jsx';

// ── The two instants ─────────────────────────────────────────────────────────
//
// Both are measured, not reasoned about: the `the fixture itself` block below runs the deleted
// helper at each of them and pins what it returned.

/** 00:30 BST on 25 Oct 2026 — inside the 25-hour day BST ends on. The step COLLAPSED here. */
const CLOCKS_GO_BACK = '2026-10-24T23:30:00Z';
const BACK_TODAY = '2026-10-25';
const BACK_TOMORROW = '2026-10-26';

/** 23:30 GMT on 28 Mar 2026 — the eve of the 23-hour day BST begins on. The step SKIPPED here. */
const CLOCKS_GO_FORWARD = '2026-03-28T23:30:00Z';
const FORWARD_TOMORROW = '2026-03-29';
/** What the broken step named "tomorrow" instead — T+2, jumping the real tomorrow entirely. */
const FORWARD_SKIPPED = '2026-03-30';

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

afterEach(() => {
  vi.useRealTimers();
});

// ── Fixtures ─────────────────────────────────────────────────────────────────

/**
 * Two consecutive days, one upcoming sunset each.
 *
 * `solarEventTime` is naive UTC — `isEventPast` appends the `Z` — and 16:30 keeps both events ahead
 * of either frozen instant, so the past filter never withdraws a day and cannot be mistaken for the
 * label rule under test.
 */
function twoDayBriefing(firstDate, secondDate) {
  return {
    generatedAt: `${firstDate}T06:00:00`,
    headline: '',
    days: [firstDate, secondDate].map((date) => ({
      date,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'Northumberland',
          verdict: 'GO',
          displayVerdict: 'WORTH_IT',
          summary: 'Northumberland gloss',
          tideHighlights: [],
          regionWeatherCode: 3,
          regionTemperatureCelsius: 12,
          regionWindSpeedMs: 4.47,
          slots: [{
            locationName: 'Bamburgh',
            solarEventTime: `${date}T16:30:00`,
            verdict: 'GO',
          }],
        }],
        unregioned: [],
      }],
    })),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  useAuth.mockReturnValue({ role: 'ADMIN' });
  getDailyBriefing.mockResolvedValue(null);
  getCloseToHome.mockResolvedValue(null);
  getAllEvaluationScores.mockResolvedValue([]);
  getDriveTimes.mockResolvedValue({});
  getReach.mockResolvedValue([]);
  getSettings.mockResolvedValue({});
  fetchTravelDayRanges.mockResolvedValue([]);
  getAstroConditions.mockResolvedValue([]);
  getSimulationState.mockResolvedValue({ enabled: false });
});

// ─────────────────────────────────────────────────────────────────────────────

describe('the fixture itself', () => {
  /**
   * The helper both components used to carry, reproduced verbatim. It is here so the two instants
   * can be shown to be discriminating rather than asserted to be: if this file is ever moved to
   * Europe/London, or the TZ pin stops taking effect, these two tests fail loudly instead of the
   * whole file quietly becoming a no-op that passes against the bug.
   */
  function legacyLondonDate(offsetDays = 0) {
    const d = new Date();
    d.setDate(d.getDate() + offsetDays);
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
  }

  it('reproduces the collapse: the old step returned the same date for today and tomorrow', () => {
    freeze(CLOCKS_GO_BACK);

    expect(legacyLondonDate(0)).toBe(BACK_TODAY);
    expect(legacyLondonDate(1)).toBe(BACK_TODAY);
  });

  it('reproduces the skip: the old step jumped the real tomorrow', () => {
    freeze(CLOCKS_GO_FORWARD);

    expect(legacyLondonDate(1)).toBe(FORWARD_SKIPPED);
  });
});

describe('DailyBriefing day labels across a DST boundary', () => {
  it('labels the day after today "Tomorrow" on the night the clocks go back', async () => {
    // The visible symptom: with today and tomorrow resolving to the same string, `getDayLabel`
    // tests Today first and no card anywhere on the Plan screen read "Tomorrow" — the real
    // tomorrow rendered as a bare weekday.
    freeze(CLOCKS_GO_BACK);
    getDailyBriefing.mockResolvedValue(twoDayBriefing(BACK_TODAY, BACK_TOMORROW));

    render(<DailyBriefing />);
    const pills = await screen.findAllByTestId('summary-pill');

    expect(pills).toHaveLength(2);
    expect(pills[0].textContent).toContain('Today');
    expect(pills[1].textContent).toContain('Tomorrow');
  });

  it('does not fall back to a bare weekday for tomorrow', async () => {
    // The negative half of the rule, stated separately because it is the half that regressed:
    // 26 Oct 2026 is a Monday, and "Monday" is exactly what the broken step rendered here.
    freeze(CLOCKS_GO_BACK);
    getDailyBriefing.mockResolvedValue(twoDayBriefing(BACK_TODAY, BACK_TOMORROW));

    render(<DailyBriefing />);
    const pills = await screen.findAllByTestId('summary-pill');

    expect(pills[1].textContent).not.toContain('Monday');
  });

  it('labels the real tomorrow, not T+2, on the night the clocks go forward', async () => {
    // The opposite direction of the same defect. Today's sunset has already gone at 23:30 GMT, so
    // the two days on screen are T+1 and T+2 — and the broken step labelled the WRONG one of them
    // "Tomorrow", which is worse than labelling none: it reads as correct.
    freeze(CLOCKS_GO_FORWARD);
    getDailyBriefing.mockResolvedValue(twoDayBriefing(FORWARD_TOMORROW, FORWARD_SKIPPED));

    render(<DailyBriefing />);
    const pills = await screen.findAllByTestId('summary-pill');

    expect(pills).toHaveLength(2);
    expect(pills[0].textContent).toContain('Tomorrow');
    expect(pills[1].textContent).not.toContain('Tomorrow');
  });
});

describe('Best Bet navigation across a DST boundary', () => {
  /** A rank-1 pick on tomorrow's sunset — the token `resolveEventKey` has to resolve to a date. */
  const TOMORROW_SUNSET_PICK = {
    rank: 1,
    headline: 'Bamburgh at sunset',
    detail: 'Low cloud clears.',
    event: 'tomorrow_SUNSET',
    region: 'Northumberland',
    confidence: 'high',
  };

  it('opens the map on the real tomorrow, not on today', async () => {
    // `resolveEventKey('tomorrow_SUNSET')` fed `handleBetViewOnMap` → `onShowOnMap({date})` →
    // App.jsx's `setSelectedDate`. With the two strings collapsed, "Best Bet — tomorrow's sunset"
    // opened the map on today: a pick the user could act on, pointing at the wrong evening.
    freeze(CLOCKS_GO_BACK);
    getDailyBriefing.mockResolvedValue({
      ...twoDayBriefing(BACK_TODAY, BACK_TOMORROW),
      bestBets: [TOMORROW_SUNSET_PICK],
    });
    const onShowOnMap = vi.fn();

    render(<DailyBriefing onShowOnMap={onShowOnMap} />);
    fireEvent.click(await screen.findByTestId('best-bet-view-on-map'));

    expect(onShowOnMap).toHaveBeenCalledWith({
      region: 'Northumberland',
      date: BACK_TOMORROW,
      eventType: 'SUNSET',
    });
  });

  it('does not skip the real tomorrow when the clocks go forward', async () => {
    freeze(CLOCKS_GO_FORWARD);
    getDailyBriefing.mockResolvedValue({
      ...twoDayBriefing(FORWARD_TOMORROW, FORWARD_SKIPPED),
      bestBets: [TOMORROW_SUNSET_PICK],
    });
    const onShowOnMap = vi.fn();

    render(<DailyBriefing onShowOnMap={onShowOnMap} />);
    fireEvent.click(await screen.findByTestId('best-bet-view-on-map'));

    expect(onShowOnMap).toHaveBeenCalledWith({
      region: 'Northumberland',
      date: FORWARD_TOMORROW,
      eventType: 'SUNSET',
    });
  });
});

describe('WindowFirstBriefingProvider day step', () => {
  /**
   * A probe rather than a real consumer, deliberately. `todayStr` / `tomorrowStr` are context
   * outputs read by three separate components (`CloseToHome`'s window labels, the regional panel,
   * the day rail); the rule belongs to the context boundary, and rendering one of the three would
   * assert that component's wording instead. The provider derives both unconditionally, so no
   * payload is needed — `getDailyBriefing` resolves null, as it does for a cold 204.
   */
  function DateProbe() {
    const { todayStr, tomorrowStr } = useWindowFirstBriefing();
    return (
      <>
        <span data-testid="ctx-today">{todayStr}</span>
        <span data-testid="ctx-tomorrow">{tomorrowStr}</span>
      </>
    );
  }

  async function renderProbe() {
    // `await act` rather than `findBy*`: both spans paint before any fetch resolves, so a `findBy*`
    // would be satisfied before the provider's four requests settled and the assertions would race
    // its state updates.
    await act(async () => {
      render(<WindowFirstBriefingProvider><DateProbe /></WindowFirstBriefingProvider>);
    });
  }

  it('steps to the next UK day when the clocks go back, rather than repeating today', async () => {
    freeze(CLOCKS_GO_BACK);

    await renderProbe();

    expect(screen.getByTestId('ctx-today')).toHaveTextContent(BACK_TODAY);
    expect(screen.getByTestId('ctx-tomorrow')).toHaveTextContent(BACK_TOMORROW);
  });

  it('steps exactly one UK day when the clocks go forward, rather than two', async () => {
    freeze(CLOCKS_GO_FORWARD);

    await renderProbe();

    expect(screen.getByTestId('ctx-tomorrow')).toHaveTextContent(FORWARD_TOMORROW);
  });
});
