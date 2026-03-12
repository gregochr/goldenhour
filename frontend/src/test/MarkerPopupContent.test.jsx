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
  isExpanded: true,
  onToggleExpanded: vi.fn(),
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
    renderPopup({ role: 'PRO_USER', isExpanded: false });
    expect(screen.getByTestId('more-details-toggle')).toHaveTextContent('More details');
  });

  it('calls onToggleExpanded when toggle clicked', () => {
    const onToggle = vi.fn();
    renderPopup({ role: 'PRO_USER', isExpanded: false, onToggleExpanded: onToggle });
    fireEvent.click(screen.getByTestId('more-details-toggle'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  describe('LITE vs PRO/ADMIN score bar visibility', () => {
    it('hides score bars for LITE_USER', () => {
      renderPopup({ role: 'LITE_USER' });
      expect(screen.queryByText('Fiery Sky')).not.toBeInTheDocument();
      expect(screen.queryByText('Golden Hour')).not.toBeInTheDocument();
      expect(screen.queryByText('Scores')).not.toBeInTheDocument();
    });

    it('shows score bars for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
      expect(screen.getByText('Golden Hour')).toBeInTheDocument();
      expect(screen.getByText('78')).toBeInTheDocument();
      expect(screen.getByText('62')).toBeInTheDocument();
    });

    it('shows score bars for ADMIN', () => {
      renderPopup({ role: 'ADMIN' });
      expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
      expect(screen.getByText('Golden Hour')).toBeInTheDocument();
    });

    it('hides score bars when collapsed even for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER', isExpanded: false });
      expect(screen.queryByText('Fiery Sky')).not.toBeInTheDocument();
    });
  });

  describe('footer visibility by role', () => {
    it('shows forecast-generated footer for PRO_USER', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.getByText(/Forecast generated/)).toBeInTheDocument();
    });

    it('shows forecast-generated footer for LITE_USER', () => {
      renderPopup({ role: 'LITE_USER' });
      expect(screen.getByText(/Forecast generated/)).toBeInTheDocument();
    });

    it('shows forecast-generated footer for ADMIN', () => {
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
  });

  describe('tide display for coastal vs non-coastal', () => {
    it('shows tide pills for coastal location with HIGH', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['HIGH'] } });
      expect(screen.getByText(/High tide/)).toBeInTheDocument();
    });

    it('shows tide pills for coastal location with LOW', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['LOW'] } });
      expect(screen.getByText(/Low tide/)).toBeInTheDocument();
    });

    it('shows multiple tide pills when location has several tide types', () => {
      renderPopup({ role: 'PRO_USER', location: { ...BASE_LOCATION, tideType: ['HIGH', 'MID'] } });
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
      expect(screen.getByTestId('tide-indicator')).toBeInTheDocument();
      expect(screen.getByText('Tides for Bamburgh')).toBeInTheDocument();
    });

    it('does not render TideIndicator when collapsed', () => {
      renderPopup({ role: 'PRO_USER', isExpanded: false });
      expect(screen.queryByTestId('tide-indicator')).not.toBeInTheDocument();
    });

    it('hides tide pills when collapsed', () => {
      renderPopup({ role: 'PRO_USER', isExpanded: false, location: { ...BASE_LOCATION, tideType: ['HIGH'] } });
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
    expect(screen.getByText('No forecast available')).toBeInTheDocument();
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

    it('shows dust badge for LITE_USER (visible to all roles)', () => {
      renderPopup({ role: 'LITE_USER', forecast: { ...BASE_FORECAST, aerosolOpticalDepth: 0.5, dust: 60, pm25: 3 } });
      expect(screen.getByTestId('dust-badge')).toBeInTheDocument();
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

  describe('comfort rows', () => {
    it('shows temperature and wind when expanded', () => {
      renderPopup({ role: 'PRO_USER' });
      expect(screen.getByText(/8°C/)).toBeInTheDocument();
      expect(screen.getByText(/feels like/)).toBeInTheDocument();
      expect(screen.getByText(/rain chance/)).toBeInTheDocument();
    });
  });
});
