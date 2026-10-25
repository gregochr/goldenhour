/**
 * `MapView` — when aurora mode stops being available under the reader, the map goes back to Sunset
 * and leaves the rating floor as the reader had it: on the page, and for the next visit.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The rating floor (`minStars`) is a "this and above" threshold that always holds a value: its
 * initialiser returns a saved 1–5 or `DEFAULT_MIN_STARS` (3★+), and nothing else. The effect that
 * moves the map from AURORA to SUNSET when aurora becomes unavailable set it to `null` and cleared the
 * saved floor. Every reader of the floor went wrong:
 *
 * <ul>
 *   <li>(on the Plan-tab overlay, retired in overlay convergence C5) the context bar drew an empty
 *       chip and the drawer's stars all read pressed;</li>
 *   <li>every star button in the tab's popover read pressed (`star >= null`);</li>
 *   <li>the Filters counts took it for a filter, so the tab's chip read "Filters (1)" for a floor
 *       the reader never chose;</li>
 *   <li>the pins ignored the floor altogether: `rating >= null` holds for every rating;</li>
 *   <li>and the next visit opened on the 3★+ default, whatever floor the reader had saved.</li>
 * </ul>
 *
 * <p>No reader asks for that change of event, so the owner decided it touches nothing the reader set:
 * the floor keeps its value and its saved copy. (An explicit kind change still resets it —
 * `MapViewStarFilter.test.jsx` pins that.)
 *
 * <h2>The route</h2>
 *
 * <p>Aurora is available to a PRO or ADMIN reader while the live alert is active, or while this map's
 * list of stored aurora nights is non-empty. An alert that ends reaches the page as
 * `{ level: 'QUIET', active: false }`. The list names every night that currently holds a real
 * (non-simulated) stored result, but a map asks for it once, when it mounts — and the Map tab's map
 * stays mounted once visited — so it is empty when no night held one as that map asked, when that
 * request failed, and until it answers. The route that leads here is the aurora banner's door to the
 * Map tab, which hands it the banner's aurora night.
 *
 * <h2>Why the real provider</h2>
 *
 * <p>An alert ends through `AuroraStatusProvider`: a poll or a window focus publishes a fresh status
 * through context. `MapView` is `React.memo`'d, so the ref-backed `useAuroraStatus` stub the older
 * sibling files use reaches it only on a render something else causes. Only context delivers the
 * change the way production does (`MapViewAuroraLiveFetch.test.jsx` sets out the recipe).
 *
 * <p>The floor tests start on a SAVED 4★+ floor, or on none: a floor already at the default cannot
 * tell "left alone" from "reset to the default", and a saved one cannot tell "left alone" from a reset
 * that happens to land on the same value. The rest pin the other halves of the decision: a saved
 * stand-down lens and a drive-time filter both stand, and the admin "unknown" lens — the one thing
 * this change of event turns off that a reader can see — goes.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, within } from '@testing-library/react';

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
  useMap: () => ({ eachLayer: () => {}, getContainer: () => ({ clientHeight: 500, clientWidth: 800 }) }),
}));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
// ⚠️ `useAuroraStatus` and `AuroraStatusContext` are deliberately NOT mocked — see the file header.
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraStatus: vi.fn(),
  getAuroraLocations: vi.fn(),
  getAuroraForecastResults: vi.fn(),
  getAuroraForecastAvailableDates: vi.fn(),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div data-testid="popup-content" /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
// `MapLabels` needs a real Leaflet pane to place a chip (its own test's job); the counts footer, which
// reads the same filtered set, is what this file counts. Stubbed so a heat field can be offered.
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div data-testid="map-labels-stub" /> }));

import MapView from '../components/MapView.jsx';
import { AuroraStatusProvider } from '../context/AuroraStatusContext.jsx';
import {
  getAuroraStatus, getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates,
} from '../api/auroraApi.js';

/**
 * 15:00 UK on the 13th: tonight's alert, with the 13th's sunset (20:30 UK) still to come. An afternoon on
 * purpose. The map lands on THE_NIGHT's sunset whatever the hour, and only before that sunset is it a
 * window still ahead — after it, the overlay shows one already over, a question of its own. Nothing
 * this file pins about the floor depends on the hour.
 */
const AFTERNOON = '2026-08-13T14:00:00Z';
const THE_NIGHT = '2026-08-13';

/** A running alert, as `GET /api/aurora/status` reports one. */
const ALERT = { level: 'MODERATE', active: true, currentNightDate: THE_NIGHT };
/** The same endpoint once the alert is over: the state machine is back to IDLE. */
const ALL_CLEAR = { level: 'QUIET', active: false, currentNightDate: THE_NIGHT };

/** The floor this reader saved on an earlier visit — deliberately not the default (file header). */
const SAVED_FLOOR = '4';

/**
 * THE_NIGHT's sunset, one place at each rating from 2★ to 5★. So the number of places after the change
 * of event says which floor is in force: two at the saved 4★+, three at the 3★+ default, four under
 * no floor at all.
 */
const sunsetOn = (rating) => new Map([[THE_NIGHT, {
  sunset: { rating, solarEventTime: `${THE_NIGHT}T19:30:00`, fierySkyPotential: 60, goldenHourPotential: 55 },
}]]);
const LOCATIONS = [
  { name: 'Kielder', lat: 55.23, lon: -2.58, forecastsByDate: sunsetOn(2), locationType: ['LANDSCAPE'] },
  { name: 'Cheviot', lat: 55.48, lon: -2.15, forecastsByDate: sunsetOn(3), locationType: ['LANDSCAPE'] },
  { name: 'Hexham', lat: 54.97, lon: -2.10, forecastsByDate: sunsetOn(4), locationType: ['LANDSCAPE'] },
  { name: 'Alnmouth', lat: 55.39, lon: -1.61, forecastsByDate: sunsetOn(5), locationType: ['SEASCAPE'] },
];

/**
 * The live alert's scores: Kielder clears the saved 4★+ floor and Cheviot does not. So aurora mode on
 * that floor draws ONE place, a count none of the Sunset floors above produces.
 */
const LIVE = [
  { location: { name: 'Kielder', lat: 55.23, lon: -2.58 }, stars: 4, summary: 'Clear to the north' },
  { location: { name: 'Cheviot', lat: 55.48, lon: -2.15 }, stars: 3, summary: 'Thin cloud' },
];

/** The dates `GET /api/forecast` serves around THE_NIGHT — handed to the tab as the pane hands them. */
const FORECAST_DATES = ['2026-08-13', '2026-08-14', '2026-08-15'];

/** What `GET /api/aurora/status` answers with right now — each test moves it. */
let serverStatus;

/**
 * The provider first, and the map only once the status has landed. A map mounted in the same commit
 * renders with `status: null`, finds aurora unavailable, and leaves aurora mode before the alert is
 * known — so the change of event would happen on mount, not when the alert ends.
 */
async function openMap(mapElement) {
  let result;
  await act(async () => { result = render(<AuroraStatusProvider>{null}</AuroraStatusProvider>); });
  await act(async () => { result.rerender(<AuroraStatusProvider>{mapElement}</AuroraStatusProvider>); });
  return result;
}

/** The alert ends, and the page regains focus: the provider fetches the status and publishes it. */
async function endAlert() {
  serverStatus = ALL_CLEAR;
  await act(async () => { window.dispatchEvent(new Event('focus')); });
}

/** A roster join over the rendered locations, so the heat field is offered and the counts footer mounts. */
function heatFor(locations) {
  const spots = locations.map((l) => ({ id: l.id, name: l.name, lat: l.lat, lng: l.lon, rid: l.regionName ?? 'North East' }));
  return {
    enabled: true,
    hasHome: false,
    spots,
    areaSpots: spots,
    pointsByKey: new Map(),
    windows: [],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
  };
}

/**
 * How many locations the map draws: the N of the counts footer's "N of M shown" (the filtered set).
 */
function visibleCount() {
  const match = /(\d+) of \d+ shown/.exec(screen.getByTestId('wf-map-counts-footer').textContent);
  return match ? Number(match[1]) : NaN;
}

const STAR_NAMES = { 1: '1★+', 2: '2★+', 3: '3★+', 4: '4★+', 5: '5★' };

/**
 * Asserts that `floor` is the one in force in a threshold group, through the two buttons either side
 * of it — the one below not pressed, the floor's own pressed. That holds whichever pressed contract
 * the group settles on (every star at or above the floor, or the floor's own star alone), and fails
 * for a floor with no value under both.
 */
function expectFloorPressed(group, floor) {
  expect(within(group).getByRole('button', { name: STAR_NAMES[floor - 1] }))
    .toHaveAttribute('aria-pressed', 'false');
  expect(within(group).getByRole('button', { name: STAR_NAMES[floor] }))
    .toHaveAttribute('aria-pressed', 'true');
}

beforeEach(() => {
  localStorage.clear();
  mockRole = 'PRO_USER';
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AFTERNOON));
  serverStatus = ALERT;
  getAuroraStatus.mockReset();
  // A new object per call, as a parsed response body is.
  getAuroraStatus.mockImplementation(() => Promise.resolve({ ...serverStatus }));
  getAuroraLocations.mockReset();
  getAuroraLocations.mockResolvedValue(LIVE);
  getAuroraForecastResults.mockReset();
  getAuroraForecastResults.mockResolvedValue([]);
  // No stored aurora night anywhere: once the alert ends, nothing keeps aurora mode available.
  getAuroraForecastAvailableDates.mockReset();
  getAuroraForecastAvailableDates.mockResolvedValue([]);
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('the Map tab, when the alert ends while it is in aurora mode', () => {
  // An aurora handoff into the tab, as the banner overlay's hatch hands one over — not the pane's
  // whole mount. There is no `heat`, and `autoEventType` is null where the pane passes App's
  // auto-selection. That only matters on a tab already mounted: if the reader has picked a window
  // there (the window control, a landing-card row, the callout's strip) or come in through a Plan
  // door, the handoff turns the override off, and the auto-selection replaces AURORA on the next
  // commit. So this models a tab the hatch mounts. The forecast dates are passed so the solar-window
  // gate works as it does in production, where a map-count test copied here would otherwise see an
  // ungated view the tab never draws.
  const tab = (props = {}) => (
    <MapView
      locations={LOCATIONS}
      date={THE_NIGHT}
      forecastDates={FORECAST_DATES}
      autoEventType="AURORA"
      {...props}
    />
  );

  /** Opens the tab in aurora mode and proves it is there, with the chip named `chipName`. */
  async function openInAurora(chipName) {
    await openMap(tab());
    expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');
    expect(screen.getByRole('button', { name: chipName })).toBeInTheDocument();
  }

  /** Opens the popover from `chip`, and proves the map has left aurora mode. */
  function openFilters(chip) {
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('dialog', { name: 'Map filters' });
    // Control: the popover withholds its Sky row only in aurora and astro mode.
    expect(within(panel).getByRole('button', { name: /Dark sky only/ })).toBeInTheDocument();
    return panel;
  }

  describe('on a saved 4★+ floor', () => {
    beforeEach(() => { localStorage.setItem('mapFilterMinStars', SAVED_FLOOR); });

    it('still counts the saved floor as a filter', async () => {
      await openInAurora('Filters (1)');

      await endAlert();

      // Reset to the default instead, the chip would read "Filters" and Clear all would be gone.
      const panel = openFilters(screen.getByRole('button', { name: 'Filters (1)' }));
      expect(within(panel).getByRole('button', { name: 'Clear all' })).toBeInTheDocument();
    });

    it('keeps 4★+ pressed in the popover', async () => {
      await openInAurora('Filters (1)');

      await endAlert();
      // Opened by its test id, not its name: the count is the test above's to pin, so a floor reset to
      // the default fails here at the pressed state rather than at the chip.
      const panel = openFilters(screen.getByTestId('wf-filters-chip'));

      // Broken, `star >= null` pressed all five, so 3★+ read pressed.
      expectFloorPressed(within(panel).getByRole('group', { name: 'Minimum rating' }), 4);
    });

    it('keeps 4★+ saved, so the next map opens on it', async () => {
      const { unmount } = await openMap(tab());
      expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');

      await endAlert();
      expect(localStorage.getItem('mapFilterMinStars')).toBe(SAVED_FLOOR);

      // The next visit: a fresh tab on THE_NIGHT's sunset, with no aurora handoff to leave.
      unmount();
      await openMap(tab({ autoEventType: 'SUNSET' }));

      // Broken, the saved floor was cleared and this map opened on the 3★+ default (chip "Filters").
      const panel = openFilters(screen.getByRole('button', { name: 'Filters (1)' }));
      expectFloorPressed(within(panel).getByRole('group', { name: 'Minimum rating' }), 4);
    });
  });

  describe('with the stand-down lens saved (admin)', () => {
    // The same rule as the floor's, for the other filter a reader saves: the explicit kind changes
    // turn it off and clear it, and this change, which no reader asked for, leaves it alone.
    beforeEach(() => {
      mockRole = 'ADMIN';
      localStorage.setItem('mapFilterShowStandDown', '1');
    });

    it('keeps the stand-down lens on, and saved', async () => {
      await openMap(tab());
      expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');

      await endAlert();

      expect(localStorage.getItem('mapFilterShowStandDown')).toBe('1');
      const panel = openFilters(screen.getByTestId('wf-filters-chip'));
      expect(within(panel).getByTestId('star-filter-standdown')).toHaveClass('on');
    });
  });

  describe('with a drive-time filter chosen in aurora mode', () => {
    it('keeps the drive-time filter', async () => {
      // A filter the reader sets for this visit only, never saved: it stands like the saved ones.
      await openMap(tab());
      expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');
      fireEvent.click(screen.getByTestId('wf-filters-chip'));
      fireEvent.click(screen.getByTestId('drive-time-filter-45'));
      expect(screen.getByTestId('drive-time-filter-45')).toHaveAttribute('aria-pressed', 'true');

      await endAlert();

      // The popover was left open: aurora mode is over (the Sky row is back) and the filter stands.
      expect(screen.getByRole('button', { name: /Dark sky only/ })).toBeInTheDocument();
      // Broken, the filter was reset to Any.
      expect(screen.getByTestId('drive-time-filter-45')).toHaveAttribute('aria-pressed', 'true');
    });
  });

  describe('on no saved floor', () => {
    it('counts no filter — the chip reads "Filters"', async () => {
      await openInAurora('Filters');

      await endAlert();

      // Broken, a null floor is not the default either, so the chip read "Filters (1)". (No separate
      // check that Clear all is absent: it shows exactly when the chip carries a count, so this exact
      // name already fails first.)
      openFilters(screen.getByRole('button', { name: 'Filters' }));
    });
  });

  describe('with the admin "unknown" lens on', () => {
    // The one thing this change of event turns off that a reader can see: an admin's debug lens,
    // never saved. It can only be switched on outside aurora mode, so this tab starts on Sunset and
    // takes the aurora handoff afterwards, as a mounted tab takes the hatch's.
    const ROTHBURY = {
      name: 'Rothbury', lat: 55.31, lon: -1.91, forecastsByDate: new Map(), locationType: ['LANDSCAPE'],
    };
    const WITH_UNSCORED = [...LOCATIONS, ROTHBURY];
    // Only this test needs a roster join: the counts footer's N is what counts the drawn places.
    const HEAT_UNSCORED = heatFor(WITH_UNSCORED);

    beforeEach(() => { mockRole = 'ADMIN'; });

    it('turns the "unknown" lens off', async () => {
      const { rerender } = await openMap(tab({ locations: WITH_UNSCORED, heat: HEAT_UNSCORED, autoEventType: null }));
      fireEvent.click(screen.getByTestId('wf-filters-chip'));
      // Control: Cheviot, Hexham and Alnmouth clear the 3★+ default, and unscored Rothbury joins them
      // once the lens is on.
      expect(visibleCount()).toBe(3);
      fireEvent.click(screen.getByRole('button', { name: '? unknown' }));
      expect(visibleCount()).toBe(4);

      await act(async () => {
        rerender(<AuroraStatusProvider>{tab({ locations: WITH_UNSCORED, heat: HEAT_UNSCORED })}</AuroraStatusProvider>);
      });
      expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');

      await endAlert();

      // Control: aurora mode is over — the Sky row is back.
      expect(within(screen.getByRole('dialog', { name: 'Map filters' }))
        .getByRole('button', { name: /Dark sky only/ })).toBeInTheDocument();
      // The three rated places, without Rothbury. With the lens left on, four.
      expect(visibleCount()).toBe(3);
    });
  });
});
