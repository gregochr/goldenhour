import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraScores.js', () => ({ useAuroraScores: vi.fn() }));
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => ({ heatSpots: [], reachById: new Map(), effectiveReachById: new Map(), origin: null }),
}));
vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: function ThumbStub({ viewline }) {
    return <div data-testid="thumb-stub" data-line={viewline ? 'yes' : 'no'} />;
  },
}));

import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraScores } from '../hooks/useAuroraScores.js';
import AuroraBanner from '../components/AuroraBanner.jsx';
import { expandBanner } from './auroraBannerHarness.js';
import { AuroraViewlineProvider } from '../context/AuroraViewlineContext.jsx';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

const LIVE = {
  points: [{ longitude: -5, latitude: 49 }],
  southernmostLatitude: 49,
  active: true,
  isForecast: false,
  kp: null,
  asOf: '2026-10-10T21:40:00Z',
};
const STATUS = {
  level: 'MODERATE', hexColour: '#ff0000', description: 'x', active: true, eligibleLocations: 5,
  forecastKp: 6, triggerType: 'forecast', darkSkyLocationCount: 150, clearLocationCount: 147,
};
const POLL_MS = 5 * 60 * 1000;

/** The Map tab's side of the union (MapView's predicate: not Lite and the alert worth showing). */
function MapSide() {
  const { viewline } = useAuroraViewline(true);
  return <div data-testid="map-side">{viewline ? 'line' : 'none'}</div>;
}

describe('the real banner and a map consumer share one poll (P3d)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: false });
    getAuroraViewline.mockResolvedValue(LIVE);
    getAuroraForecastViewline.mockResolvedValue(LIVE);
    useAuroraStatus.mockReturnValue({ status: STATUS, loading: false });
    useAuroraScores.mockReturnValue({
      ready: true,
      spots: [{ id: 1, name: 'Kielder', lat: 55, lng: -2, rid: 'Kielder', stars: 5 }],
    });
  });
  afterEach(() => { vi.useRealTimers(); });

  it('one request per endpoint per interval with the banner and the map both wanting the line', async () => {
    render(
      <AuroraViewlineProvider>
        <AuroraBanner />
        <MapSide />
      </AuroraViewlineProvider>,
    );
    expandBanner();
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByTestId('thumb-stub')).toHaveAttribute('data-line', 'yes');
    expect(screen.getByTestId('map-side')).toHaveTextContent('line');
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_MS); });
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);
  });
});
