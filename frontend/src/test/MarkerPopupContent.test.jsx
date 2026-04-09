import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MarkerPopupContent from '../components/MarkerPopupContent.jsx';

vi.mock('../components/TideIndicator.jsx', () => ({
  default: ({ locationName }) => <div data-testid="tide-indicator">Tides for {locationName}</div>,
}));

const BASE_LOCATION = {
  name: 'Bamburgh',
  solarEventType: ['SUNSET'],
  locationType: ['SEASCAPE'],
  tideType: ['HIGH'],
};

const BASE_FORECAST = {
  rating: 4,
  fierySkyPotential: 78,
  goldenHourPotential: 62,
  summary: 'Good conditions expected.',
  solarEventTime: '2026-03-03T18:15:00Z',
  forecastRunAt: '2026-03-03T06:00:00Z',
  evaluationModel: 'SONNET',
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
  forecast: BASE_FORECAST,
  hourlyData: [],
  eventType: 'SUNSET',
  isPureWildlife: false,
  date: '2026-03-03',
  onTideFetchedAt: vi.fn(),
  tideFetchedAt: null,
};

function renderPopup(overrides = {}) {
  return render(<MarkerPopupContent {...DEFAULT_PROPS} {...overrides} />);
}

describe('MarkerPopupContent', () => {
  it('renders location name', () => {
    renderPopup({ role: 'PRO_USER' });
    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
  });

  it('renders star rating for all roles', () => {
    renderPopup({ role: 'LITE_USER' });
    expect(screen.getByText('4/5')).toBeInTheDocument();
  });

  it('renders summary text', () => {
    renderPopup({ role: 'PRO_USER' });
    expect(screen.getByText('Good conditions expected.')).toBeInTheDocument();
  });

  it('shows more-details toggle', () => {
    renderPopup({ role: 'PRO_USER' });
    expect(screen.getByTestId('more-details-toggle')).toHaveTextContent('More details');
  });

  it('toggles expanded state when toggle clicked', () => {
    renderPopup({ role: 'PRO_USER' });
    expect(screen.queryByText('Fiery Sky')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('more-details-toggle'));
    expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
  });

  describe('LITE vs PRO/ADMIN score bar visibility', () => {
    it('hides score bars entirely for LITE_USER when expanded', () => {
      renderPopup({
        role: 'LITE_USER',
        forecast: { ...BASE_FORECAST, fierySkyPotential: 78, goldenHourPotential: 62 },
      });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.queryByText('Scores')).not.toBeInTheDocument();
      expect(screen.queryByText('Fiery Sky')).not.toBeInTheDocument();
      expect(screen.queryByText('Golden Hour')).not.toBeInTheDocument();
    });

    it('shows upgrade hint for LITE_USER', () => {
      renderPopup({ role: 'LITE_USER' });
      expect(screen.getByTestId('upgrade-hint')).toBeInTheDocument();
      expect(screen.getByTestId('upgrade-hint').textContent).toContain('Upgrade to Pro');
    });

    it('does not show upgrade hint for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('upgrade-hint')).not.toBeInTheDocument();
    });

    it('shows score bars for PRO_USER when expanded', () => {
      renderPopup({ role: 'PRO_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
      expect(screen.getByText('Golden Hour')).toBeInTheDocument();
      expect(screen.getByText('78')).toBeInTheDocument();
      expect(screen.getByText('62')).toBeInTheDocument();
    });

    it('does not show pro-pill for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.queryByTestId('pro-pill')).not.toBeInTheDocument();
    });

    it('shows score bars for ADMIN when expanded', () => {
      renderPopup({ role: 'ADMIN' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
      expect(screen.getByText('Golden Hour')).toBeInTheDocument();
    });

    it('does not show pro-pill for ADMIN', () => {
      renderPopup({ role: 'ADMIN' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.queryByTestId('pro-pill')).not.toBeInTheDocument();
    });

    it('hides score bars when collapsed even for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByText('Fiery Sky')).not.toBeInTheDocument();
    });
  });

  describe('footer visibility by role', () => {
    it('shows forecast-generated footer for PRO_USER when expanded', () => {
      renderPopup({ role: 'PRO_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/Forecast generated/)).toBeInTheDocument();
    });

    it('shows forecast-generated footer for LITE_USER when expanded', () => {
      renderPopup({ role: 'LITE_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/Forecast generated/)).toBeInTheDocument();
    });

    it('shows forecast-generated footer for ADMIN (always visible)', () => {
      renderPopup({ role: 'ADMIN' });
      expect(screen.getByText(/Forecast generated/)).toBeInTheDocument();
    });

    it('includes evaluation model in ADMIN footer', () => {
      renderPopup({ role: 'ADMIN' });
      expect(screen.getByText(/by Sonnet/)).toBeInTheDocument();
    });

    it('shows tide fetched-at for ADMIN when available', () => {
      renderPopup({ role: 'ADMIN', tideFetchedAt: '2026-03-02T02:00:00Z' });
      expect(screen.getByText(/Tide data fetched/)).toBeInTheDocument();
    });

    it('does not show tide fetched-at for non-ADMIN', () => {
      renderPopup({ role: 'PRO_USER', tideFetchedAt: '2026-03-02T02:00:00Z' });
      expect(screen.queryByText(/Tide data fetched/)).not.toBeInTheDocument();
    });

    it('shows weather triage note in footer when forecast was triaged by cloud', () => {
      const triaged = {
        ...BASE_FORECAST,
        rating: 1,
        summary: 'Conditions unsuitable — Solar horizon low cloud 85% — sun blocked',
        evaluationModel: 'HAIKU',
      };
      renderPopup({ role: 'ADMIN', forecast: triaged });
      expect(screen.getByText(/not evaluated by Claude due to weather triage/)).toBeInTheDocument();
      expect(screen.getByText(/Haiku run/)).toBeInTheDocument();
    });

    it('shows sentinel skip note in footer when forecast was sentinel-skipped', () => {
      const sentinel = {
        ...BASE_FORECAST,
        rating: 1,
        summary: 'Conditions unsuitable — Region sentinel sampling — all sentinels rated ≤2',
        evaluationModel: 'SONNET',
      };
      renderPopup({ role: 'ADMIN', forecast: sentinel });
      expect(screen.getByText(/not evaluated by Claude due to regional sentinel sampling/)).toBeInTheDocument();
    });

    it('shows normal model footer when forecast was evaluated by Claude', () => {
      renderPopup({ role: 'ADMIN' });
      expect(screen.getByText(/by Sonnet/)).toBeInTheDocument();
      expect(screen.queryByText(/not evaluated by Claude/)).not.toBeInTheDocument();
    });

    it('shows weather triage note for non-ADMIN when expanded', () => {
      const triaged = {
        ...BASE_FORECAST,
        rating: 1,
        summary: 'Conditions unsuitable — Precipitation 3.2 mm — active rain',
        evaluationModel: 'HAIKU',
      };
      renderPopup({ role: 'PRO_USER', forecast: triaged });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/not evaluated by Claude due to weather triage/)).toBeInTheDocument();
    });
  });

  describe('tide display for coastal vs non-coastal', () => {
    it('shows tide pills for coastal location with HIGH when expanded', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['HIGH'] } });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/High tide/)).toBeInTheDocument();
    });

    it('shows tide pills for coastal location with LOW when expanded', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['LOW'] } });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/Low tide/)).toBeInTheDocument();
    });

    it('shows multiple tide pills when location has several tide types', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['HIGH', 'MID'] } });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/High tide/)).toBeInTheDocument();
      expect(screen.getByText(/Mid tide/)).toBeInTheDocument();
    });

    it('does not show tide pills when tideType is empty', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: [] } });
      expect(screen.queryByText(/High tide/)).not.toBeInTheDocument();
    });

    it('does not show tide pills when tideType is null', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: null } });
      expect(screen.queryByText(/High tide/)).not.toBeInTheDocument();
    });

    it('renders TideIndicator when expanded', () => {
      renderPopup({ role: 'PRO_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByTestId('tide-indicator')).toBeInTheDocument();
      expect(screen.getByText('Tides for Bamburgh')).toBeInTheDocument();
    });

    it('does not render TideIndicator when collapsed', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('tide-indicator')).not.toBeInTheDocument();
    });

    it('hides tide pills when collapsed', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['HIGH'] } });
      expect(screen.queryByText(/High tide/)).not.toBeInTheDocument();
    });
  });

  describe('wildlife locations', () => {
    it('shows hourly comfort header instead of rating', () => {
      renderPopup({
        role: 'PRO_USER',
        isPureWildlife: true,
        hourlyData: [
          { solarEventTime: '2026-03-03T08:00:00Z', temperatureCelsius: 6, apparentTemperatureCelsius: 3, windSpeed: 5, windDirection: 180, precipitationProbabilityPercent: 20 },
        ],
      });
      expect(screen.getByText(/Hourly comfort/)).toBeInTheDocument();
      expect(screen.queryByText(/★/)).not.toBeInTheDocument();
    });

    it('shows no-hourly message when hourlyData is empty', () => {
      renderPopup({ role: 'PRO_USER', isPureWildlife: true, hourlyData: [] });
      expect(screen.getByText('No hourly forecast available')).toBeInTheDocument();
    });
  });

  it('shows no-forecast message when forecast is null', () => {
    renderPopup({ role: 'PRO_USER', forecast: null });
    expect(screen.getByText('no forecast yet')).toBeInTheDocument();
  });

  describe('dust badge', () => {
    it('shows dust badge when AOD high and PM2.5 low', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5, dust: 12, pm25: 8 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
      expect(screen.getByText(/Elevated dust/)).toBeInTheDocument();
    });

    it('shows dust badge when dust concentration high and PM2.5 low', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.1, dust: 65, pm25: 5 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
    });

    it('shows dust badge when PM2.5 is absent (null treated as low)', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
    });

    it('hides dust badge when PM2.5 is high (smoke/haze)', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5, dust: 60, pm25: 45 } });
      expect(screen.queryByTestId('dust-badge')).not.toBeInTheDocument();
    });

    it('hides dust badge when AOD and dust both below threshold', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.2, dust: 30, pm25: 5 } });
      expect(screen.queryByTestId('dust-badge')).not.toBeInTheDocument();
    });

    it('hides dust badge when aerosol fields are absent', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('dust-badge')).not.toBeInTheDocument();
    });

    it('shows simplified dust badge for LITE_USER', () => {
      renderPopup({ role: 'LITE_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5, dust: 60, pm25: 3 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
      expect(screen.getByText(/High aerosols/)).toBeInTheDocument();
      expect(screen.queryByText(/Elevated dust/)).not.toBeInTheDocument();
    });

    it('shows detailed dust badge for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5, dust: 60, pm25: 3 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
      expect(screen.getByText(/Elevated dust/)).toBeInTheDocument();
      expect(screen.queryByText(/High aerosols/)).not.toBeInTheDocument();
    });
  });

  describe('inversion badge', () => {
    it('shows strong inversion badge when inversionPotential is STRONG', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, inversionPotential: 'STRONG', inversionScore: 9 } });
      expect(screen.getByTestId('inversion-badge')).toBeInTheDocument();
      expect(screen.getByText(/Strong cloud inversion/)).toBeInTheDocument();
    });

    it('shows moderate inversion badge when inversionPotential is MODERATE', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, inversionPotential: 'MODERATE', inversionScore: 7 } });
      expect(screen.getByTestId('inversion-badge')).toBeInTheDocument();
      expect(screen.getByText(/Moderate cloud inversion/)).toBeInTheDocument();
    });

    it('hides inversion badge when inversionPotential is NONE', () => {
      renderPopup({ role: 'PRO_USER', forecast: { ...BASE_FORECAST, inversionPotential: 'NONE', inversionScore: 3 } });
      expect(screen.queryByTestId('inversion-badge')).not.toBeInTheDocument();
    });

    it('hides inversion badge when inversionPotential is absent', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('inversion-badge')).not.toBeInTheDocument();
    });

    it('hides inversion badge for LITE_USER', () => {
      renderPopup({ role: 'LITE_USER', forecast: { ...BASE_FORECAST, inversionPotential: 'STRONG', inversionScore: 9 } });
      expect(screen.queryByTestId('inversion-badge')).not.toBeInTheDocument();
    });
  });

  describe('rising tide badge', () => {
    it('shows badge when high tide is 33 min after sunrise', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNRISE',
        forecast: {
          ...BASE_FORECAST,
          solarEventTime: '2026-03-10T06:34:00',
          nextHighTideTime: '2026-03-10T07:07:00',
        },
      });
      expect(screen.getByTestId('rising-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/Rising tide/)).toBeInTheDocument();
      expect(screen.getByText(/33 min after sunrise/)).toBeInTheDocument();
    });

    it('shows badge when high tide is 30 min before sunset (golden hour)', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNSET',
        forecast: {
          ...BASE_FORECAST,
          solarEventTime: '2026-03-10T18:15:00',
          nextHighTideTime: '2026-03-10T17:45:00',
        },
      });
      expect(screen.getByTestId('rising-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/30 min before sunset/)).toBeInTheDocument();
    });

    it('shows badge when high tide is 78 min after sunrise (within 90 min window)', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNRISE',
        forecast: {
          ...BASE_FORECAST,
          solarEventTime: '2026-03-10T06:31:00',
          nextHighTideTime: '2026-03-10T07:49:00',
        },
      });
      expect(screen.getByTestId('rising-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/78 min after sunrise/)).toBeInTheDocument();
    });

    it('does not show badge when high tide is > 90 min away', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNRISE',
        forecast: {
          ...BASE_FORECAST,
          solarEventTime: '2026-03-10T06:34:00',
          nextHighTideTime: '2026-03-10T09:00:00',
        },
      });
      expect(screen.queryByTestId('rising-tide-badge')).not.toBeInTheDocument();
    });

    it('does not show badge when nextHighTideTime is null', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNRISE',
        forecast: {
          ...BASE_FORECAST,
          nextHighTideTime: null,
        },
      });
      expect(screen.queryByTestId('rising-tide-badge')).not.toBeInTheDocument();
    });

    it('shows badge when high tide is exactly at sunrise', () => {
      renderPopup({
        role: 'PRO_USER',
        eventType: 'SUNRISE',
        forecast: {
          ...BASE_FORECAST,
          solarEventTime: '2026-03-10T06:34:00',
          nextHighTideTime: '2026-03-10T06:34:00',
        },
      });
      expect(screen.getByTestId('rising-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/at sunrise/)).toBeInTheDocument();
    });

    it('does not show badge when forecast is null', () => {
      renderPopup({ role: 'PRO_USER', forecast: null });
      expect(screen.queryByTestId('rising-tide-badge')).not.toBeInTheDocument();
    });
  });

  describe('spring/king tide badge', () => {
    const SPRING_CLASSIFICATION = [
      { time: '2026-03-03T18:00:00', height: 5.2, isSpring: true, isKing: false, nearSolarEvent: true },
    ];
    const KING_CLASSIFICATION = [
      { time: '2026-03-03T18:00:00', height: 6.1, isSpring: false, isKing: true, nearSolarEvent: true },
    ];
    const SPRING_OUTSIDE = [
      { time: '2026-03-03T10:00:00', height: 5.2, isSpring: true, isKing: false, nearSolarEvent: false },
    ];
    const KING_OUTSIDE = [
      { time: '2026-03-03T10:00:00', height: 6.1, isSpring: false, isKing: true, nearSolarEvent: false },
    ];

    it('shows spring tide badge when high tide is within ±90 min of the forecast solar event', () => {
      renderPopup({ role: 'PRO_USER', tideClassification: SPRING_CLASSIFICATION });
      expect(screen.getByTestId('spring-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/Spring tide/)).toBeInTheDocument();
      expect(screen.getByText(/5\.2m/)).toBeInTheDocument();
      expect(screen.queryByTestId('king-tide-badge')).not.toBeInTheDocument();
    });

    it('shows king tide badge when high tide is within ±90 min of the forecast solar event', () => {
      renderPopup({ role: 'PRO_USER', tideClassification: KING_CLASSIFICATION });
      expect(screen.getByTestId('king-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/King tide/)).toBeInTheDocument();
      expect(screen.getByText(/6\.1m/)).toBeInTheDocument();
      expect(screen.queryByTestId('spring-tide-badge')).not.toBeInTheDocument();
    });

    it('shows muted spring tide badge outside golden/blue hours', () => {
      renderPopup({ role: 'PRO_USER', tideClassification: SPRING_OUTSIDE });
      const badge = screen.getByTestId('spring-tide-badge');
      expect(badge).toBeInTheDocument();
      expect(screen.getByText(/but outside golden\/blue hours/)).toBeInTheDocument();
    });

    it('shows muted king tide badge outside golden/blue hours', () => {
      renderPopup({ role: 'PRO_USER', tideClassification: KING_OUTSIDE });
      const badge = screen.getByTestId('king-tide-badge');
      expect(badge).toBeInTheDocument();
      expect(screen.getByText(/but outside golden\/blue hours/)).toBeInTheDocument();
    });

    it('king tide trumps spring tide (only king shown)', () => {
      const both = [
        { time: '2026-03-03T18:00:00', height: 6.1, isSpring: false, isKing: true, nearSolarEvent: true },
      ];
      renderPopup({ role: 'PRO_USER', tideClassification: both });
      expect(screen.getByTestId('king-tide-badge')).toBeInTheDocument();
      expect(screen.queryByTestId('spring-tide-badge')).not.toBeInTheDocument();
    });

    it('does not show badge when tideClassification is null', () => {
      renderPopup({ role: 'PRO_USER', tideClassification: null });
      expect(screen.queryByTestId('spring-tide-badge')).not.toBeInTheDocument();
      expect(screen.queryByTestId('king-tide-badge')).not.toBeInTheDocument();
    });

    it('does not show badge when tideClassification is undefined', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('spring-tide-badge')).not.toBeInTheDocument();
      expect(screen.queryByTestId('king-tide-badge')).not.toBeInTheDocument();
    });

    it('shows badge for LITE_USER (visible to all roles)', () => {
      renderPopup({ role: 'LITE_USER', tideClassification: SPRING_CLASSIFICATION });
      expect(screen.getByTestId('spring-tide-badge')).toBeInTheDocument();
    });

    it('shows multiple badges when two high tides qualify', () => {
      const twoHighs = [
        { time: '2026-03-03T06:00:00', height: 5.3, isSpring: true, isKing: false, nearSolarEvent: false },
        { time: '2026-03-03T18:20:00', height: 5.1, isSpring: true, isKing: false, nearSolarEvent: true },
      ];
      renderPopup({ role: 'PRO_USER', tideClassification: twoHighs });
      const badges = screen.getAllByTestId('spring-tide-badge');
      expect(badges).toHaveLength(2);
    });
  });

  describe('lunar tide combined labels', () => {
    const STAT_KING_CLASSIFICATION = [
      { time: '2026-03-03T18:00:00', height: 6.1, isSpring: false, isKing: true, nearSolarEvent: true },
    ];
    const STAT_SPRING_CLASSIFICATION = [
      { time: '2026-03-03T18:00:00', height: 5.2, isSpring: true, isKing: false, nearSolarEvent: true },
    ];
    const LUNAR_SPRING_FORECAST = { ...BASE_FORECAST, lunarTideType: 'SPRING_TIDE', lunarPhase: 'Full Moon' };
    const LUNAR_KING_FORECAST = { ...BASE_FORECAST, lunarTideType: 'KING_TIDE', lunarPhase: 'New Moon' };

    it('shows combined "King Tide, Extra Extra High" when lunar=KING + stat=king', () => {
      renderPopup({
        role: 'PRO_USER',
        forecast: LUNAR_KING_FORECAST,
        tideClassification: STAT_KING_CLASSIFICATION,
      });
      expect(screen.getByTestId('king-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/King Tide, Extra Extra High/)).toBeInTheDocument();
    });

    it('shows combined "Spring Tide, Extra High" when lunar=SPRING + stat=spring', () => {
      renderPopup({
        role: 'PRO_USER',
        forecast: LUNAR_SPRING_FORECAST,
        tideClassification: STAT_SPRING_CLASSIFICATION,
      });
      expect(screen.getByTestId('spring-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/Spring Tide, Extra High/)).toBeInTheDocument();
    });

    it('shows "Spring Tide" when lunar=SPRING but stat neither king nor spring', () => {
      const regularStatClassification = [
        { time: '2026-03-03T18:00:00', height: 4.0, isSpring: false, isKing: false, nearSolarEvent: true },
      ];
      renderPopup({
        role: 'PRO_USER',
        forecast: LUNAR_SPRING_FORECAST,
        tideClassification: regularStatClassification,
      });
      expect(screen.getByTestId('spring-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/Spring Tide/)).toBeInTheDocument();
    });

    it('shows king-tide-badge when lunar=KING even if stat is not king', () => {
      const regularStatClassification = [
        { time: '2026-03-03T18:00:00', height: 4.0, isSpring: false, isKing: false, nearSolarEvent: true },
      ];
      renderPopup({
        role: 'PRO_USER',
        forecast: LUNAR_KING_FORECAST,
        tideClassification: regularStatClassification,
      });
      expect(screen.getByTestId('king-tide-badge')).toBeInTheDocument();
      expect(screen.getByText(/King Tide/)).toBeInTheDocument();
    });

    it('shows moon phase info in badge when lunar tide is present', () => {
      renderPopup({
        role: 'PRO_USER',
        forecast: LUNAR_KING_FORECAST,
        tideClassification: STAT_KING_CLASSIFICATION,
      });
      expect(screen.getByText(/King Tide, Extra Extra High/)).toBeInTheDocument();
    });
  });

  describe('comfort rows', () => {
    it('shows temperature and wind when expanded', () => {
      renderPopup({ role: 'PRO_USER' });
      fireEvent.click(screen.getByTestId('more-details-toggle'));
      expect(screen.getByText(/8°C/)).toBeInTheDocument();
      expect(screen.getByText(/feels like/)).toBeInTheDocument();
      expect(screen.getByText(/rain chance/)).toBeInTheDocument();
    });
  });

  describe('aurora score section', () => {
    const MODERATE_SCORE = {
      stars: 3,
      alertLevel: 'MODERATE',
      cloudPercent: 20,
      summary: 'Good aurora conditions — partly clear',
      detail: 'Cloud: ✓ Clear (20%) — good\nMoon: ✓ below horizon\nLight pollution: — Bortle 3\nAlert: 🟠 Amber',
    };

    const STRONG_SCORE = {
      stars: 5,
      alertLevel: 'STRONG',
      cloudPercent: 5,
      summary: 'Excellent aurora conditions',
      detail: 'Cloud: ✓ Clear (5%) — excellent\nMoon: ✓ below horizon\nLight pollution: ✓ Bortle 2\nAlert: 🔴 Red',
    };

    it('does not render aurora section when auroraScore is null', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: null });
      expect(screen.queryByTestId('aurora-score-section')).not.toBeInTheDocument();
    });

    it('does not render aurora section when auroraScore is omitted', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.queryByTestId('aurora-score-section')).not.toBeInTheDocument();
    });

    it('renders aurora section for PRO_USER with MODERATE score', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: MODERATE_SCORE });
      expect(screen.getByTestId('aurora-score-section')).toBeInTheDocument();
    });

    it('renders aurora section for ADMIN with STRONG score', () => {
      renderPopup({ role: 'ADMIN', auroraScore: STRONG_SCORE });
      expect(screen.getByTestId('aurora-score-section')).toBeInTheDocument();
    });

    it('renders aurora section for LITE_USER when score is provided', () => {
      renderPopup({ role: 'LITE_USER', auroraScore: MODERATE_SCORE });
      expect(screen.getByTestId('aurora-score-section')).toBeInTheDocument();
    });

    it('shows correct star count for 3-star amber score', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: MODERATE_SCORE });
      const starsEl = screen.getByTestId('aurora-score-stars');
      expect(starsEl).toHaveTextContent('★★★☆☆');
    });

    it('shows correct star count for 5-star red score', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: STRONG_SCORE });
      const starsEl = screen.getByTestId('aurora-score-stars');
      expect(starsEl).toHaveTextContent('★★★★★');
    });

    it('renders the detail factor breakdown text', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: MODERATE_SCORE });
      const detail = screen.getByTestId('aurora-score-detail');
      expect(detail).toBeInTheDocument();
      expect(detail).toHaveTextContent('Cloud:');
      expect(detail).toHaveTextContent('Moon:');
      expect(detail).toHaveTextContent('Light pollution:');
    });

    it('renders aurora section above tide badges (before More details)', () => {
      renderPopup({ role: 'PRO_USER', auroraScore: MODERATE_SCORE });
      const section = screen.getByTestId('aurora-score-section');
      const toggle = screen.getByTestId('more-details-toggle');
      // aurora section should appear before the toggle in DOM
      expect(section.compareDocumentPosition(toggle) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('does not render aurora section when forecast is null', () => {
      renderPopup({ role: 'PRO_USER', forecast: null, auroraScore: MODERATE_SCORE });
      expect(screen.queryByTestId('aurora-score-section')).not.toBeInTheDocument();
    });
  });

  describe('empty state enrichment', () => {
    const EMPTY_LOCATION = {
      name: 'Kielder',
      solarEventType: ['SUNRISE', 'SUNSET'],
      locationType: ['LANDSCAPE'],
      tideType: [],
      bortleClass: 3,
      regionName: 'Northumberland',
      forecastsByDate: new Map([
        ['2026-03-03', {
          sunrise: { solarEventTime: '2026-03-03T06:34:00' },
          sunset: { solarEventTime: '2026-03-03T18:15:00' },
        }],
      ]),
    };

    function renderEmpty(overrides = {}) {
      return render(
        <MarkerPopupContent
          {...DEFAULT_PROPS}
          location={EMPTY_LOCATION}
          forecast={null}
          role="PRO_USER" // eslint-disable-line jsx-a11y/aria-role
          {...overrides}
        />,
      );
    }

    it('renders enriched empty state with data-testid', () => {
      renderEmpty();
      expect(screen.getByTestId('empty-popup')).toBeInTheDocument();
      expect(screen.getByText('no forecast yet')).toBeInTheDocument();
    });

    it('shows location name and event badge', () => {
      renderEmpty();
      expect(screen.getByText('Kielder')).toBeInTheDocument();
      expect(screen.getByText(/Sunset · 18:15/)).toBeInTheDocument();
    });

    it('shows location type and region sub-row', () => {
      renderEmpty();
      expect(screen.getByText(/Landscape · Northumberland/)).toBeInTheDocument();
    });

    it('shows solar times row with both sunrise and sunset', () => {
      renderEmpty();
      const solarRow = screen.getByTestId('solar-times-row');
      expect(solarRow).toBeInTheDocument();
      // Both sunrise and sunset times should be present
      expect(solarRow.textContent).toMatch(/🌅/);
      expect(solarRow.textContent).toMatch(/🌇/);
    });

    it('shows aurora friendly and light pollution chips when bortleClass is set', () => {
      renderEmpty();
      expect(screen.getByTestId('aurora-badge')).toBeInTheDocument();
      expect(screen.getByTestId('light-pollution-badge')).toBeInTheDocument();
    });

    it('hides aurora and light pollution chips when bortleClass is null', () => {
      renderEmpty({
        location: { ...EMPTY_LOCATION, bortleClass: null },
      });
      expect(screen.queryByTestId('aurora-badge')).not.toBeInTheDocument();
      expect(screen.queryByTestId('light-pollution-badge')).not.toBeInTheDocument();
    });

    it('shows drive time chip when driveMinutes is provided', () => {
      renderEmpty({ driveMinutes: 95 });
      expect(screen.getByTestId('drive-time-badge')).toHaveTextContent('1 hr 35 mins');
    });

    it('hides drive time chip when driveMinutes is null', () => {
      renderEmpty({ driveMinutes: null });
      expect(screen.queryByText(/\d+ hr/)).not.toBeInTheDocument();
      expect(screen.queryByText(/\d+ mins/)).not.toBeInTheDocument();
    });

    it('shows Run Forecast button for ADMIN', () => {
      renderEmpty({ role: 'ADMIN' });
      expect(screen.getByTestId('run-forecast-btn')).toBeInTheDocument();
    });

    it('hides Run Forecast button for non-admin roles', () => {
      renderEmpty({ role: 'PRO_USER' });
      expect(screen.queryByTestId('run-forecast-btn')).not.toBeInTheDocument();
    });

    it('does not show solar times row when forecastsByDate has no data for date', () => {
      renderEmpty({
        location: { ...EMPTY_LOCATION, forecastsByDate: new Map() },
      });
      expect(screen.queryByTestId('solar-times-row')).not.toBeInTheDocument();
    });

    it('shows sunrise event badge when eventType is SUNRISE', () => {
      renderEmpty({ eventType: 'SUNRISE' });
      expect(screen.getByText(/Sunrise · 06:34/)).toBeInTheDocument();
    });

    it('hides event badge in aurora mode', () => {
      renderEmpty({ isAuroraMode: true });
      expect(screen.queryByText(/Sunset/)).not.toBeInTheDocument();
    });

    it('shows region only when locationType is empty', () => {
      renderEmpty({
        location: { ...EMPTY_LOCATION, locationType: [] },
      });
      expect(screen.getByText('Northumberland')).toBeInTheDocument();
      expect(screen.queryByText(/Landscape/)).not.toBeInTheDocument();
    });

    it('shows type only when regionName is null', () => {
      renderEmpty({
        location: { ...EMPTY_LOCATION, regionName: null },
      });
      expect(screen.getByText('Landscape')).toBeInTheDocument();
      expect(screen.queryByText(/Northumberland/)).not.toBeInTheDocument();
    });

    it('hides sub-row when both locationType and regionName are empty/null', () => {
      renderEmpty({
        location: { ...EMPTY_LOCATION, locationType: [], regionName: null },
      });
      // Sub-row should not render at all
      expect(screen.queryByText(/Landscape/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Northumberland/)).not.toBeInTheDocument();
    });

    it('populated state is unchanged — still renders rating and summary', () => {
      render(
        <MarkerPopupContent
          {...DEFAULT_PROPS}
          role="PRO_USER" // eslint-disable-line jsx-a11y/aria-role
        />,
      );
      expect(screen.getByText('4/5')).toBeInTheDocument();
      expect(screen.getByText('Good conditions expected.')).toBeInTheDocument();
      expect(screen.queryByTestId('empty-popup')).not.toBeInTheDocument();
    });
  });

  describe('drive time badge', () => {
    describe('forecast branch', () => {
      it('renders drive-time-badge when driveMinutes is a positive number', () => {
        renderPopup({ role: 'PRO_USER', driveMinutes: 75 });
        expect(screen.getByTestId('drive-time-badge')).toBeInTheDocument();
        expect(screen.getByTestId('drive-time-badge')).toHaveTextContent('1 hr 15 mins');
      });

      it('does not render drive-time-badge when driveMinutes is null', () => {
        renderPopup({ role: 'PRO_USER', driveMinutes: null });
        expect(screen.queryByTestId('drive-time-badge')).not.toBeInTheDocument();
      });

      it('does not render drive-time-badge when driveMinutes is 0', () => {
        renderPopup({ role: 'PRO_USER', driveMinutes: 0 });
        expect(screen.queryByTestId('drive-time-badge')).not.toBeInTheDocument();
      });
    });

    describe('empty branch (no forecast)', () => {
      const EMPTY_LOCATION = {
        name: 'Dunstanburgh',
        solarEventType: ['SUNSET'],
        locationType: ['SEASCAPE'],
        tideType: [],
        forecastsByDate: new Map(),
      };

      it('renders drive-time-badge when driveMinutes is positive and forecast is null', () => {
        render(
          <MarkerPopupContent
            {...DEFAULT_PROPS}
            location={EMPTY_LOCATION}
            forecast={null}
            role="PRO_USER" // eslint-disable-line jsx-a11y/aria-role
            driveMinutes={90}
          />,
        );
        expect(screen.getByTestId('drive-time-badge')).toBeInTheDocument();
        expect(screen.getByTestId('drive-time-badge')).toHaveTextContent('1 hr 30 mins');
      });
    });
  });
});
