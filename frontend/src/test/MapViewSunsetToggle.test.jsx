/**
 * Regression test: the sunset toggle should be enabled when briefingScores
 * has sunset entries for the current date, even if forecast_evaluation
 * (forecastsByDate) has no sunset data.
 *
 * Bug B: sunset toggle was disabled for dates where only cached_evaluation
 * had data, because the availability check only looked at forecastsByDate.
 *
 * ⚠️ Rewritten for map-tab-v2-plan.md §3 P6, which removed `ForecastTypeSelector` from the Map
 * TAB mount — it survives unchanged on the Plan-tab overlay, which still inherits its event from
 * the card that opened it and reads `sunriseAvailable`/`sunsetAvailable` off the exact same
 * `MapView` computation (`hasBriefingScoreForType`) this file has always pinned. Rendering with
 * `overlayMode` is the only change: it is now the one surface where `ForecastTypeSelector` (and
 * so these two props) actually reaches the DOM, but the availability logic under test is neither
 * duplicated nor different there — `WindowFirstMapPane`'s D-13 rows (the tab's own successor to
 * this concern) are `mapEvents.test.js`'s "served-vs-client-max discipline" cases.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
  }),
}));

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: 'ADMIN' }),
}));

vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null }),
}));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));

vi.mock('../components/BottomSheet.jsx', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));

vi.mock('../components/InfoTip.jsx', () => ({
  default: () => null,
}));

vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  STAND_DOWN_COLOUR: '#501313',
  makeMarkerIcon: () => ({}),
}));

// Capture ForecastTypeSelector props so we can assert sunriseAvailable/sunsetAvailable. The
// overlay's own context bar reads the real `EVENT_TYPE_LABELS` alongside the component, so the
// mock has to carry that named export too now that these tests render with `overlayMode`.
const selectorCalls = [];
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: (props) => {
    selectorCalls.push(props);
    return <div data-testid="forecast-type-selector" />;
  },
  EVENT_TYPE_LABELS: {
    SUNRISE: '☀️ Sunrise', SUNSET: '🌇 Sunset', ASTRO: '🌙 Astro', AURORA: '🌌 Aurora',
  },
}));

import MapView from '../components/MapView.jsx';

const TODAY = new Date().toLocaleDateString('en-CA');

describe('MapView sunset toggle availability from briefingScores', () => {
  beforeEach(() => {
    selectorCalls.length = 0;
    localStorage.clear();
  });
  afterEach(() => {
    localStorage.clear();
  });

  it('enables sunset toggle when briefingScores has sunset entries but forecastsByDate has none', () => {
    // Location has sunrise data in forecastsByDate but NO sunset data
    const location = {
      name: 'Sandsend',
      id: 1,
      lat: 54.5,
      lon: -0.6,
      regionName: 'NE Yorks',
      locationType: ['SEASCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunrise: { solarEventTime: `${TODAY}T06:00:00` },
        sunset: null,
      }]]),
    };

    // briefingScores has a sunset entry — this should make sunset available
    const briefingScores = new Map([
      [`NE Yorks|${TODAY}|SUNSET|Sandsend`, {
        rating: 3,
        fierySkyPotential: 50,
        goldenHourPotential: 40,
        summary: 'Decent',
      }],
    ]);

    // `overlayMode` — the one mount `ForecastTypeSelector` still reaches since map-tab-v2-plan.md
    // §3 P6 (see file header). The Map TAB no longer renders it at all, but the availability
    // computation this file pins is identical on both mounts.
    render(
      <MapView
        overlayMode
        locations={[location]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={briefingScores}
      />,
    );

    // Get the most recent ForecastTypeSelector render
    const lastCall = selectorCalls[selectorCalls.length - 1];
    expect(lastCall.sunsetAvailable).toBe(true);
    expect(lastCall.sunriseAvailable).toBe(true);
  });

  it('keeps sunset disabled when neither forecastsByDate nor briefingScores have data', () => {
    const location = {
      name: 'Sandsend',
      id: 1,
      lat: 54.5,
      lon: -0.6,
      regionName: 'NE Yorks',
      locationType: ['SEASCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunrise: { solarEventTime: `${TODAY}T06:00:00` },
        sunset: null,
      }]]),
    };

    render(
      <MapView
        overlayMode
        locations={[location]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={new Map()}
      />,
    );

    const lastCall = selectorCalls[selectorCalls.length - 1];
    expect(lastCall.sunsetAvailable).toBe(false);
  });

  it('the Map TAB (not overlay) never mounts ForecastTypeSelector at all — its job is the window control now', () => {
    // The negative space this rewrite has to state explicitly: the pre-P6 version of this file
    // rendered the tab and read the selector's props off it. Rendering the tab today and finding
    // no such call is the one-line proof that the absorption actually happened, not just that the
    // overlay's own copy still works.
    const location = {
      name: 'Sandsend',
      id: 1,
      lat: 54.5,
      lon: -0.6,
      regionName: 'NE Yorks',
      locationType: ['SEASCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunrise: { solarEventTime: `${TODAY}T06:00:00` },
        sunset: null,
      }]]),
    };
    render(
      <MapView
        locations={[location]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={new Map()}
      />,
    );
    expect(selectorCalls).toHaveLength(0);
  });
});
