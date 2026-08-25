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
import { render, screen, act } from '@testing-library/react';

vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
}));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getReach: vi.fn(),
  getSettings: vi.fn(),
}));
vi.mock('../api/regionApi.js', () => ({
  fetchRegions: vi.fn(),
  fetchRegionDriveTimes: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: vi.fn() }));

import {
  WindowFirstBriefingProvider, useWindowFirstBriefing,
} from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
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

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  useAuth.mockReturnValue({ role: 'ADMIN' });
  getDailyBriefing.mockResolvedValue(null);
  getAllEvaluationScores.mockResolvedValue([]);
  getReach.mockResolvedValue([]);
  getSettings.mockResolvedValue({});
  fetchRegions.mockResolvedValue([]);
  fetchRegionDriveTimes.mockResolvedValue({});
  fetchTravelDayRanges.mockResolvedValue([]);
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

describe('WindowFirstBriefingProvider day step', () => {
  /**
   * A probe rather than a real consumer, deliberately. `todayStr` / `tomorrowStr` are context
   * outputs read by many components across the Plan tab; the rule belongs to the context boundary,
   * and rendering any one of them would assert that component's wording instead. The provider
   * derives both unconditionally, so no payload is needed — `getDailyBriefing` resolves null, as it
   * does for a cold 204.
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
