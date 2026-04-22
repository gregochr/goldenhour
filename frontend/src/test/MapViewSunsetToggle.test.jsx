/**
 * Regression test: the sunset toggle should be enabled when briefingScores
 * has sunset entries for the current date, even if forecast_evaluation
 * (forecastsByDate) has no sunset data.
 *
 * Bug B: sunset toggle was disabled for dates where only cached_evaluation
 * had data, because the availability check only looked at forecastsByDate.
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
  RATING_COLOURS: { 1: '#A32D2D', 2: '#D85A30', 3: '#FAC775', 4: '#97C459', 5: '#3B6D11' },
  STAND_DOWN_COLOUR: '#501313',
  makeMarkerIcon: () => ({}),
}));

// Capture ForecastTypeSelector props so we can assert sunriseAvailable/sunsetAvailable
const selectorCalls = [];
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: (props) => {
    selectorCalls.push(props);
    return <div data-testid="forecast-type-selector" />;
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

    render(
      <MapView
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
        locations={[location]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={new Map()}
      />,
    );

    const lastCall = selectorCalls[selectorCalls.length - 1];
    expect(lastCall.sunsetAvailable).toBe(false);
  });
});
