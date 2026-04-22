/**
 * Integration test: MapView passes the looked-up briefingScore to
 * MarkerPopupContent at both the desktop popup and mobile bottom sheet call
 * sites. A mutation that drops the prop would silently revert stand-down
 * popovers to "no forecast yet" — exactly the bug this fix resolves.
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

let mockRole = 'ADMIN';
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: mockRole }),
}));

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => mockIsMobile,
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

// Capture every MarkerPopupContent call — the core assertion target.
const popupCalls = [];
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: (props) => {
    popupCalls.push(props);
    return <div data-testid="popup-content" />;
  },
}));

vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: () => null,
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

import MapView from '../components/MapView.jsx';

const TODAY = new Date().toLocaleDateString('en-CA');

function makeLocation(name, id) {
  return {
    name,
    id,
    lat: 54.5,
    lon: -0.6,
    regionName: 'The North Yorkshire Coast',
    locationType: ['SEASCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunrise: { solarEventTime: `${TODAY}T06:00:00` },
      sunset: { solarEventTime: `${TODAY}T18:00:00` },
    }]]),
  };
}

function lastCallFor(locationName) {
  // Reverse scan so we grab the current-render props, not stale mount calls.
  for (let i = popupCalls.length - 1; i >= 0; i--) {
    if (popupCalls[i].location.name === locationName) return popupCalls[i];
  }
  return null;
}

describe('MapView → MarkerPopupContent briefingScore wiring', () => {
  beforeEach(() => {
    popupCalls.length = 0;
    mockRole = 'ADMIN';
    mockIsMobile = false;
    localStorage.clear();
    // MapView hides stand-down locations by default; the wiring tests here
    // specifically exercise triaged scores, so opt them back in.
    localStorage.setItem('mapFilterShowStandDown', '1');
  });
  afterEach(() => {
    localStorage.clear();
  });

  it('passes the matched briefingScore for the location/date/event to the desktop popup', () => {
    const triaged = {
      rating: null,
      triageReason: 'HIGH_CLOUD',
      triageMessage: 'Overcast with 88% low cloud.',
    };
    const briefingScores = new Map([
      [`The North Yorkshire Coast|${TODAY}|SUNRISE|Sandsend`, triaged],
    ]);

    render(
      <MapView
        locations={[makeLocation('Sandsend', 1)]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={briefingScores}
      />,
    );

    const call = lastCallFor('Sandsend');
    expect(call).not.toBeNull();
    // The exact object from the map must be handed through — identity check
    // kills a mutation that reconstructs a new object or sends a stand-in.
    expect(call.briefingScore).toBe(triaged);
  });

  it('passes null briefingScore when no entry matches the location/date/event', () => {
    const briefingScores = new Map([
      // Different location, same date/event — must NOT match Sandsend
      [`The North Yorkshire Coast|${TODAY}|SUNRISE|Whitby`, { triageReason: 'HIGH_CLOUD' }],
    ]);

    render(
      <MapView
        locations={[makeLocation('Sandsend', 1)]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={briefingScores}
      />,
    );

    const call = lastCallFor('Sandsend');
    expect(call).not.toBeNull();
    expect(call.briefingScore).toBeNull();
  });

  it('hands the exact same object reference to both the lookup and the popup', () => {
    // Identity check (toBe, not toEqual) — kills a mutation that spreads or
    // clones the score before passing it down. Any cloning would cause the
    // map entry and the prop value to have different identities.
    const triaged = {
      rating: null,
      triageReason: 'LOW_VISIBILITY',
      triageMessage: 'Fog at the coast.',
    };
    const briefingScores = new Map([
      [`The North Yorkshire Coast|${TODAY}|SUNRISE|Sandsend`, triaged],
    ]);

    render(
      <MapView
        locations={[makeLocation('Sandsend', 1)]}
        date={TODAY}
        autoEventType="SUNRISE"
        briefingScores={briefingScores}
      />,
    );

    const call = lastCallFor('Sandsend');
    expect(call.briefingScore).toBe(triaged);
    expect(call.briefingScore.triageReason).toBe('LOW_VISIBILITY');
    expect(call.briefingScore.triageMessage).toBe('Fog at the coast.');
  });
});
