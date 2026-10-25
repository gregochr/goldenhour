/**
 * The window pill's "is better" line, as wired into the real `MapView`
 * (`docs/engineering/map-landing-nocard-plan.md` P2, spec checks 1-6; check 7 is
 * `MapViewNoLandingCard.test.jsx`).
 *
 * <p>The rule (§2): on the FIRST solar window, when the SECOND is in a strictly higher tier, neither is a
 * thin sample and the reader is not on a phone, the pill carries "<day> <event> is better". The tiers are
 * the served, scope-narrowed verdicts the pill itself shows (`evVerdicts`); no reader filter touches them.
 *
 * <p>jsdom lays nothing out, so the "shows only if it fits" check reads zeros here and always passes;
 * its real behaviour is `WindowControlBetter.test.jsx`'s (stubbed layout) and was measured in Chromium.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div /> }));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

const LAKES = {
  id: 1, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', skySubject: true,
};
const NORTH_EAST = {
  id: 2, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', skySubject: true,
};

const KEYS = {
  todaySunrise: `${TODAY}:SUNRISE`,
  todaySunset: `${TODAY}:SUNSET`,
  tomorrowSunrise: `${TOMORROW}:SUNRISE`,
  tomorrowSunset: `${TOMORROW}:SUNSET`,
};

function makeLocation(spot) {
  const forecast = (date) => [date, {
    sunrise: { rating: 4, solarEventTime: `${date}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunset: { rating: 4, solarEventTime: `${date}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }];
  return {
    id: spot.id,
    name: spot.name,
    lat: spot.lat,
    lon: spot.lng,
    regionName: spot.rid,
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([forecast(TODAY), forecast(TOMORROW)]),
  };
}

/**
 * The heat prop for a given set of spots. `windowStates` is withheld unless a case asks for a thin
 * sample, because it is what makes a one-place pool read as `· 1 rated`.
 */
function heatProp({ spots = [LAKES], areaSpots = spots, hasHome = false, windowStates } = {}) {
  const windows = [
    { key: KEYS.todaySunset, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1 },
    { key: KEYS.tomorrowSunrise, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:23', bestRating: 4, conf: 1 },
    { key: KEYS.tomorrowSunset, date: TOMORROW, targetType: 'SUNSET', label: 'Tomorrow sunset', time: '16:14', bestRating: 4, conf: 1 },
  ];
  return {
    enabled: true,
    hasHome,
    spots,
    areaSpots,
    pointsByKey: new Map(windows.map((w) => [w.key, spots.map((s) => ({
      id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid, r: [4],
    }))])),
    windows,
    windowStates,
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

/** One region's verdict for each of the three windows, as `displayVerdict`s. */
function regionFor(name, [todaySunset, tomorrowSunrise, tomorrowSunset]) {
  const region = (displayVerdict) => ({
    regionName: name, meanRating: 4, displayVerdict, slots: [{ canopy: false }],
  });
  return {
    [KEYS.todaySunset]: region(todaySunset),
    [KEYS.tomorrowSunrise]: region(tomorrowSunrise),
    [KEYS.tomorrowSunset]: region(tomorrowSunset),
  };
}

/** The briefing days for a set of regions, each given its three windows' verdicts. */
function daysFor(regions, extraTodaySunrise = null) {
  const summaries = (date, targetType, key) => ({
    targetType,
    regions: regions.map(([name, tiers]) => regionFor(name, tiers)[key]),
  });
  const today = [
    summaries(TODAY, 'SUNSET', KEYS.todaySunset),
  ];
  if (extraTodaySunrise) {
    today.unshift({
      targetType: 'SUNRISE',
      regions: [{
        regionName: 'The Lakes', meanRating: 4.4, displayVerdict: extraTodaySunrise, slots: [{ canopy: false }],
      }],
    });
  }
  return [
    { date: TODAY, eventSummaries: today },
    {
      date: TOMORROW,
      eventSummaries: [
        summaries(TOMORROW, 'SUNRISE', KEYS.tomorrowSunrise),
        summaries(TOMORROW, 'SUNSET', KEYS.tomorrowSunset),
      ],
    },
  ];
}

const MAYBE_THEN_WORTH_IT = [['The Lakes', ['MAYBE', 'WORTH_IT', 'WORTH_IT']]];

/** The parent `App` plays: the map's chosen day comes back down as `date`, as it does in the real tab. */
function Parent({ days, heat, ...props }) {
  const [date, setDate] = React.useState(TODAY);
  return (
    <MapView
      locations={[makeLocation(LAKES), makeLocation(NORTH_EAST)]}
      date={date}
      onSelectDate={(next) => setDate(next)}
      autoEventType={null}
      heat={heat}
      regionVerdictIndex={buildRegionVerdictIndex(days)}
      {...props}
    />
  );
}

async function renderMap({ days = daysFor(MAYBE_THEN_WORTH_IT), heat = heatProp(), ...props } = {}) {
  let result;
  await act(async () => {
    result = render(<Parent days={days} heat={heat} {...props} />);
  });
  return result;
}

beforeEach(() => {
  mockIsMobile = false;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // Midday UK: this morning's sunrise (08:24) is behind the reader, tonight's sunset still ahead.
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.useRealTimers();
});

const lines = () => screen.queryAllByTestId('wf-win-better');
const pillText = () => screen.getByTestId('wf-win-pill').textContent;

describe('MapView — the window pill\'s "is better" line', () => {
  it('opens with exactly one line, "Tomorrow sunrise is better", when tonight is Maybe and tomorrow\'s sunrise Worth it', async () => {
    await renderMap();
    // Non-vacuous: the pill is on tonight's sunset, with its verdict.
    expect(pillText()).toContain('Tonight');
    expect(screen.getByTestId('wf-win-verdict-word')).toHaveTextContent(/maybe/i);
    expect(lines()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Tomorrow sunrise is better' })).toBe(lines()[0]);
  });

  it('draws nothing when both windows are in the same tier (the one change from the case above)', async () => {
    await renderMap({ days: daysFor([['The Lakes', ['MAYBE', 'MAYBE', 'MAYBE']]]) });
    expect(screen.getByTestId('wf-win-verdict-word')).toHaveTextContent(/maybe/i);
    expect(lines()).toHaveLength(0);
  });

  it('a press selects the second window, closes the menu, and the line is gone', async () => {
    await renderMap();
    fireEvent.click(lines()[0]);
    expect(pillText()).toContain('Tomorrow');
    expect(pillText()).toContain('Sunrise');
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(lines()).toHaveLength(0);
    // Focus did not fall to <body>: it is on the pill, inside the pane.
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('the press resets the reader\'s filters on this sunset to sunrise change, exactly as choosing the row from the menu does', async () => {
    localStorage.setItem('mapFilterMinStars', '4');
    await renderMap();
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');

    fireEvent.click(lines()[0]);

    expect(screen.getByTestId('wf-filters-chip')).not.toHaveTextContent('(');
    expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
  });

  it('the menu route resets the same filter (the control for the case above)', async () => {
    localStorage.setItem('mapFilterMinStars', '4');
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getAllByTestId('wf-win-row')[1]);
    expect(screen.getByTestId('wf-filters-chip')).not.toHaveTextContent('(');
  });

  it('stepping away with › removes the line, and stepping back with ‹ returns it', async () => {
    await renderMap();
    expect(lines()).toHaveLength(1);
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(lines()).toHaveLength(0);
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(lines()).toHaveLength(1);
  });

  it('no reader filter changes whether it shows (4★+, dark-sky)', async () => {
    await renderMap();
    expect(lines()).toHaveLength(1);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('star-filter-4'));
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (');
    expect(lines()).toHaveLength(1);
  });

  it('follows the SCOPE segment, because scope narrows the served verdicts', async () => {
    // Area: The Lakes only, Maybe then Worth it. Everywhere: North East is Worth it tonight too, so the
    // best regional verdict is the same tier both nights and there is nothing better to say.
    const days = daysFor([
      ['The Lakes', ['MAYBE', 'WORTH_IT', 'WORTH_IT']],
      ['North East', ['WORTH_IT', 'WORTH_IT', 'WORTH_IT']],
    ]);
    await renderMap({
      days,
      heat: heatProp({ spots: [LAKES, NORTH_EAST], areaSpots: [LAKES], hasHome: true }),
    });
    expect(lines()).toHaveLength(1);

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));
    expect(lines()).toHaveLength(0);

    fireEvent.click(screen.getByTestId('wf-filters-scope-home'));
    expect(lines()).toHaveLength(1);
  });

  it('draws nothing on a phone, with the same fixture that shows it on desktop', async () => {
    const first = await renderMap();
    expect(lines()).toHaveLength(1);
    first.unmount();

    mockIsMobile = true;
    await renderMap();
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();
    expect(lines()).toHaveLength(0);
  });

  it('a past window ahead of the first served solar row does not move the line', async () => {
    // This morning's sunrise has gone (it is midday) and is given a Worth it verdict: were it counted as
    // the first window, the pair would read Worth it then Maybe and the line would vanish.
    await renderMap({ days: daysFor(MAYBE_THEN_WORTH_IT, 'WORTH_IT') });
    expect(lines()).toHaveLength(1);
    expect(lines()[0]).toHaveTextContent('Tomorrow sunrise is better');
  });

  it('draws nothing when the FIRST window is a thin sample (the one change from the shown case)', async () => {
    const states = (rated) => ({ states: new Map([[`#${LAKES.id}`, rated ? 'rated' : 'unrated']]) });
    const windowStates = new Map([
      [KEYS.todaySunset, states(true)],
      [KEYS.tomorrowSunrise, states(false)],
      [KEYS.tomorrowSunset, states(false)],
    ]);
    await renderMap({ heat: heatProp({ windowStates }) });
    expect(screen.getByTestId('wf-win-verdict-count')).toHaveTextContent('1 rated');
    expect(lines()).toHaveLength(0);
  });

  it('draws nothing when the SECOND window is a thin sample', async () => {
    const states = (rated) => ({ states: new Map([[`#${LAKES.id}`, rated ? 'rated' : 'unrated']]) });
    const windowStates = new Map([
      [KEYS.todaySunset, states(false)],
      [KEYS.tomorrowSunrise, states(true)],
      [KEYS.tomorrowSunset, states(false)],
    ]);
    await renderMap({ heat: heatProp({ windowStates }) });
    // Positive: the SECOND row is the thin one (the pill's `· N rated` is the first row's, so step on).
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(screen.getByTestId('wf-win-verdict-count')).toHaveTextContent('1 rated');
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(screen.queryByTestId('wf-win-verdict-count')).toBeNull();
    expect(lines()).toHaveLength(0);
  });
});
