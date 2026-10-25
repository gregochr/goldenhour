/**
 * The drive lock's route from the role to the Map (docs/engineering/pro-features-gate-plan.md §9 D1): the
 * briefing provider derives `driveLocked = !isPro` from the role, `WindowFirstMapPane` hands it to
 * `MapView`, and a bare render (no provider) is unlocked. `MapView` is stubbed — every assertion is
 * about what it is HANDED, the same level `WindowFirstMapPane.test.jsx` works at.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';
import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';

const MapStub = { lastProps: null };
vi.mock('../components/MapView.jsx', () => ({
  default: (props) => {
    MapStub.lastProps = props;
    return <div data-testid="stub-map" />;
  },
}));
vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(() => new Promise(() => {})),
  putRating: vi.fn(),
}));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

const DATES = ['2026-08-11', '2026-08-12'];

/** Reads the context value directly, so the derivation is asserted apart from the pane. */
function Probe() {
  const { isPro, driveLocked } = useWindowFirstBriefing();
  return <span data-testid="probe" data-is-pro={String(isPro)} data-drive-locked={String(driveLocked)} />;
}

function renderPaneUnderProvider() {
  return render(
    <WindowFirstBriefingProvider>
      <Probe />
      <WindowFirstMapPane locations={[]} dates={DATES} selectedDate={DATES[0]} onSelectDate={vi.fn()} />
    </WindowFirstBriefingProvider>,
  );
}

beforeEach(() => {
  MapStub.lastProps = null;
  localStorage.clear();
  getDailyBriefing.mockReset();
  getDailyBriefing.mockResolvedValue({ generatedAt: '2026-08-11T12:00:00', days: [], renderedEvents: [] });
  fetchTravelDayRanges.mockReset();
  fetchTravelDayRanges.mockResolvedValue([]);
  getAllEvaluationScores.mockReset();
  getAllEvaluationScores.mockResolvedValue([]);
  getReach.mockReset();
  getReach.mockResolvedValue([]);
  getSettings.mockReset();
  getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockReset();
  fetchRegions.mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset();
  fetchRegionDriveTimes.mockResolvedValue({});
  vi.spyOn(document, 'hasFocus').mockReturnValue(true);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('the drive lock reaches MapView through WindowFirstMapPane', () => {
  it('LITE: the provider derives driveLocked true and the map is handed driveLocked', async () => {
    mockRole = 'LITE_USER';
    await act(async () => { renderPaneUnderProvider(); });
    expect(screen.getByTestId('probe')).toHaveAttribute('data-is-pro', 'false');
    expect(screen.getByTestId('probe')).toHaveAttribute('data-drive-locked', 'true');
    expect(MapStub.lastProps.driveLocked).toBe(true);
  });

  it.each(['PRO_USER', 'ADMIN'])('%s: driveLocked is false and the map is handed false', async (role) => {
    mockRole = role;
    await act(async () => { renderPaneUnderProvider(); });
    expect(screen.getByTestId('probe')).toHaveAttribute('data-is-pro', 'true');
    expect(screen.getByTestId('probe')).toHaveAttribute('data-drive-locked', 'false');
    expect(MapStub.lastProps.driveLocked).toBe(false);
  });

  it('a bare render with no provider (the context default) is unlocked', () => {
    mockRole = 'LITE_USER';
    render(<WindowFirstMapPane locations={[]} dates={DATES} selectedDate={DATES[0]} onSelectDate={vi.fn()} />);
    expect(MapStub.lastProps.driveLocked).toBe(false);
  });
});
