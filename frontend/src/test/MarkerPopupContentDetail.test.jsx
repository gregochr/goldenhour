import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';
import { getForecastDetail, clearForecastDetailCache } from '../api/forecastApi.js';

// Mock only the detail fetch (and the unrelated runForecast the component imports);
// the cache helpers (peek/cache/clear) stay real so the caching precedence is exercised.
vi.mock('../api/forecastApi.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    getForecastDetail: vi.fn(),
    runForecast: vi.fn(),
  };
});

vi.mock('../components/TideIndicator.jsx', () => ({
  default: ({ locationName }) => <div data-testid="tide-indicator">Tides for {locationName}</div>,
}));

const BASE_LOCATION = {
  name: 'Bamburgh',
  solarEventType: ['SUNSET'],
  locationType: ['SEASCAPE'],
  tideType: ['HIGH'],
};

/** Slim header/comfort fields that live on the list payload for every rich row. */
const SLIM_HEADER = {
  rating: 4,
  fierySkyPotential: 78,
  goldenHourPotential: 62,
  solarEventTime: '2026-03-03T18:15:00',
  forecastRunAt: '2026-03-03T06:00:00',
  temperatureCelsius: 8,
  apparentTemperatureCelsius: 5,
  windSpeed: 4.2,
  windDirection: 220,
  precipitationProbabilityPercent: 10,
  precipitation: 0,
  azimuthDeg: 250,
};

const DEFAULT_PROPS = {
  location: BASE_LOCATION,
  hourlyData: [],
  eventType: 'SUNSET',
  isPureWildlife: false,
  date: '2026-03-03',
  role: 'PRO_USER',
  onTideFetchedAt: vi.fn(),
  tideFetchedAt: null,
};

function renderPopup(overrides = {}) {
  return render(<MarkerPopupContent {...DEFAULT_PROPS} {...overrides} />);
}

beforeEach(() => {
  clearForecastDetailCache();
  getForecastDetail.mockReset();
});

describe('MarkerPopupContent lazy detail resolution', () => {
  it('(a) renders header from a slim rich row without waiting on the detail fetch', () => {
    // A never-resolving fetch keeps the popup in the loading state.
    getForecastDetail.mockReturnValue(new Promise(() => {}));
    const slimRich = { ...SLIM_HEADER, id: 42 }; // no summary, no lowCloud → needs fetch

    renderPopup({ forecast: slimRich });

    // Header paints immediately from the slim prop, gated on nothing.
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
    expect(screen.getByText('4/5')).toBeInTheDocument();
    expect(screen.getByText(/Sunset · 18:15/)).toBeInTheDocument();
    // Detail section shows the pulse skeleton while the fetch is in flight.
    expect(screen.getByTestId('detail-skeleton')).toBeInTheDocument();
    // The rich row triggered exactly one detail fetch, keyed by id.
    expect(getForecastDetail).toHaveBeenCalledTimes(1);
    expect(getForecastDetail).toHaveBeenCalledWith(42);
  });

  it('(b) fills the summary from the detail fetch once it resolves', async () => {
    getForecastDetail.mockResolvedValue({
      id: 42,
      summary: 'Fetched detail summary.',
      evaluationModel: 'SONNET',
      lowCloud: 20,
    });
    const slimRich = { ...SLIM_HEADER, id: 42 };

    renderPopup({ forecast: slimRich });

    // Skeleton first, then the resolved summary swaps in.
    expect(screen.getByTestId('detail-skeleton')).toBeInTheDocument();
    expect(await screen.findByText('Fetched detail summary.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-skeleton')).not.toBeInTheDocument();
    expect(getForecastDetail).toHaveBeenCalledWith(42);
  });

  it('(c) renders an inline summary for a sparse (id == null) row without fetching', () => {
    const sparse = {
      ...SLIM_HEADER,
      id: null,
      summary: 'Inline sparse summary.', // sparse rows carry summary inline
    };

    renderPopup({ forecast: sparse });

    expect(screen.getByText('Inline sparse summary.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-skeleton')).not.toBeInTheDocument();
    expect(getForecastDetail).not.toHaveBeenCalled();
  });

  it('(d) renders a full pass-through object (has lowCloud) inline without fetching', () => {
    // The PromptTestView pass-through carries full detail. Even with an id set,
    // the lowCloud sentinel must short-circuit the fetch (precedence rule 1).
    const full = {
      ...SLIM_HEADER,
      id: 7,
      lowCloud: 30,
      summary: 'Full pass-through summary.',
      evaluationModel: 'HAIKU',
    };

    renderPopup({ forecast: full, role: 'ADMIN' });

    expect(screen.getByText('Full pass-through summary.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-skeleton')).not.toBeInTheDocument();
    expect(getForecastDetail).not.toHaveBeenCalled();
  });

  it('degrades to a header-only popup (no skeleton) when the detail fetch fails', async () => {
    getForecastDetail.mockRejectedValue(new Error('boom'));
    const slimRich = { ...SLIM_HEADER, id: 99 };

    renderPopup({ forecast: slimRich });

    // Header is unaffected by the failure.
    expect(screen.getByText('4/5')).toBeInTheDocument();
    // Once the rejection settles, the skeleton clears and no summary renders.
    await waitFor(() => {
      expect(screen.queryByTestId('detail-skeleton')).not.toBeInTheDocument();
    });
    expect(getForecastDetail).toHaveBeenCalledWith(99);
  });

  it('serves a second popup for the same id from cache without a second fetch', async () => {
    getForecastDetail.mockResolvedValue({ id: 55, summary: 'Cached summary.', lowCloud: 10 });
    const slimRich = { ...SLIM_HEADER, id: 55 };

    const { unmount } = renderPopup({ forecast: slimRich });
    expect(await screen.findByText('Cached summary.')).toBeInTheDocument();
    unmount();

    renderPopup({ forecast: slimRich });
    // Cache hit: summary paints synchronously, no skeleton, still just one fetch.
    expect(screen.getByText('Cached summary.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-skeleton')).not.toBeInTheDocument();
    expect(getForecastDetail).toHaveBeenCalledTimes(1);
  });
});
