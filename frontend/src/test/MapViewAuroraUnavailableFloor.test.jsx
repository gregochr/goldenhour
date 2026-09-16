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
 *   <li>the overlay's context bar drew an EMPTY chip (`STAR_THRESHOLD_LABELS[null]`), keyed
 *       `undefined`, which React reported as a missing key;</li>
 *   <li>every star button in the overlay's drawer and the tab's popover read pressed
 *       (`star >= null`), and the drawer's hint read "showing ★ and above";</li>
 *   <li>the Filters counts took it for a filter, so the overlay offered a Clear and the tab's chip
 *       read "Filters (1)" for a floor the reader never chose;</li>
 *   <li>the pins ignored the floor altogether: `rating >= null` holds for every rating;</li>
 *   <li>and the next visit opened on the 3★+ default, whatever floor the reader had saved.</li>
 * </ul>
 *
 * <p>No reader asks for that change of event, so the owner decided it touches nothing the reader set:
 * the floor keeps its value and its saved copy. (An explicit kind change still resets it —
 * `MapViewStarFilter.test.jsx` and `MapViewOverlayContext.test.jsx` pin those.)
 *
 * <h2>The route</h2>
 *
 * <p>Aurora is available to a PRO or ADMIN reader while the live alert is active, or while this map's
 * list of stored aurora nights is non-empty. An alert that ends reaches the page as
 * `{ level: 'QUIET', active: false }`. The list names every night that currently holds a real
 * (non-simulated) stored result, but a map asks for it once, when it mounts — and the Map tab's map
 * stays mounted once visited — so it is empty when no night held one as that map asked, when that
 * request failed, and until it answers. Three routes lead here: the overlay the aurora banner opens
 * ("Show on map →"); the 🌌 Aurora button in any overlay's Filters drawer, pressed while an alert is
 * live; and the Map tab, through that banner overlay's `Open the full Map tab →` hatch, which hands
 * it the banner's aurora handoff.
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
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
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
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

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
 * THE_NIGHT's sunset, one place at each rating from 2★ to 5★. So the number of pins after the change
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
 * that floor draws ONE pin, a count none of the Sunset floors above produces.
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

/** The overlay's context-bar chips, in order. */
const chips = () => screen.getAllByTestId('map-context-chip').map((c) => c.textContent);

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

describe('the Plan-tab overlay, when the alert ends while it is in aurora mode', () => {
  // An aurora handoff into overlay mode, as the banner route delivers one — not App's whole mount.
  // App also hands it its auto-selection as `autoEventType`; on a fresh overlay that effect runs
  // before the handoff's, which overrides it, so null changes nothing here.
  const overlay = (props = {}) => (
    <MapView
      locations={LOCATIONS}
      date={THE_NIGHT}
      autoEventType={null}
      handoffEventType="AURORA"
      overlayMode
      {...props}
    />
  );

  /** Opens the overlay in aurora mode and proves it is there, with `expectedChips`, before the alert ends. */
  async function openInAurora(expectedChips) {
    const result = await openMap(overlay());
    expect(screen.getByTestId('map-context-event')).toHaveTextContent('Aurora');
    expect(chips()).toEqual(expectedChips);
    return result;
  }

  /** Ends the alert, opens the drawer, and proves the map left aurora mode because aurora is unavailable. */
  async function endAlertAndOpenDrawer() {
    await endAlert();
    const toggle = screen.getByRole('button', { name: 'Filters' });
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('map-context-event')).toHaveTextContent('Sunset');
    expect(screen.getByRole('button', { name: '🌌 Aurora' })).toBeDisabled();
  }

  describe('on a saved 4★+ floor', () => {
    beforeEach(() => { localStorage.setItem('mapFilterMinStars', SAVED_FLOOR); });

    it('keeps 4★+ on the context bar', async () => {
      await openInAurora(['4★+']);

      await endAlertAndOpenDrawer();

      // Broken, the one chip was `STAR_THRESHOLD_LABELS[null]`: empty, and keyed `undefined`.
      expect(chips()).toEqual(['4★+']);
    });

    it('keeps 4★+ pressed in the drawer', async () => {
      await openInAurora(['4★+']);

      await endAlertAndOpenDrawer();

      // Broken, `star >= null` pressed all five, so 3★+ read pressed.
      expectFloorPressed(screen.getByRole('group', { name: 'Minimum quality threshold' }), 4);
    });

    it('keeps the drawer\'s hint on 4★', async () => {
      await openInAurora(['4★+']);

      await endAlertAndOpenDrawer();

      // Broken, "showing ★ and above".
      expect(screen.getByTestId('quality-hint')).toHaveTextContent('showing 4★ and above · saved');
    });

    it('keeps offering Clear for the saved floor', async () => {
      await openInAurora(['4★+']);

      await endAlertAndOpenDrawer();

      // Reset to the default instead, there would be nothing for a Clear to undo.
      expect(screen.getByRole('button', { name: 'Clear' })).toBeInTheDocument();
    });

    it('applies 4★+ to the pins', async () => {
      await openInAurora(['4★+']);
      expect(screen.getAllByTestId('marker')).toHaveLength(1);

      await endAlertAndOpenDrawer();

      // Two pins: Hexham (4★) and Alnmouth (5★). Broken, all four — `rating >= null` let Kielder's
      // 2★ and Cheviot's 3★ through; reset to the default, three.
      expect(screen.getAllByTestId('marker')).toHaveLength(2);
      expect(screen.getByTestId('map-context-count')).toHaveTextContent('2 pins in view');
    });

    it('keeps 4★+ saved, so the next map opens on it', async () => {
      const { unmount } = await openInAurora(['4★+']);

      await endAlertAndOpenDrawer();
      expect(localStorage.getItem('mapFilterMinStars')).toBe(SAVED_FLOOR);

      // The next visit: a fresh overlay on THE_NIGHT's sunset, with no aurora handoff to leave.
      unmount();
      await openMap(overlay({ handoffEventType: null }));

      // Broken, the saved floor was cleared, and this map opened on the 3★+ default.
      expect(chips()).toEqual(['4★+']);
    });
  });

  describe('on no saved floor', () => {
    it('stays on the 3★+ default', async () => {
      await openInAurora(['3★+']);

      await endAlertAndOpenDrawer();

      // Broken, an empty chip.
      expect(chips()).toEqual(['3★+']);
    });

    it('offers no Clear', async () => {
      await openInAurora(['3★+']);

      await endAlertAndOpenDrawer();

      // Broken, a Clear offered for the null floor.
      expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();

      // Control for the negative above: a floor the reader does choose brings the Clear, under that
      // name, so the query cannot pass for a button that was renamed.
      fireEvent.click(within(screen.getByRole('group', { name: 'Minimum quality threshold' }))
        .getByRole('button', { name: '4★+' }));
      expect(screen.getByRole('button', { name: 'Clear' })).toBeInTheDocument();
    });

    it('saves no floor', async () => {
      await openInAurora(['3★+']);

      await endAlertAndOpenDrawer();

      expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
    });
  });

  describe('with the stand-down lens saved', () => {
    // The same rule as the floor's, for the other filter a reader saves: the explicit kind changes
    // turn it off and clear it, and this change, which no reader asked for, leaves it alone.
    beforeEach(() => { localStorage.setItem('mapFilterShowStandDown', '1'); });

    it('keeps the stand-down lens on the context bar', async () => {
      await openInAurora(['3★+', '+ stand-down']);

      await endAlertAndOpenDrawer();

      expect(chips()).toEqual(['3★+', '+ stand-down']);
    });

    it('keeps the stand-down lens saved', async () => {
      await openInAurora(['3★+', '+ stand-down']);

      await endAlertAndOpenDrawer();

      expect(localStorage.getItem('mapFilterShowStandDown')).toBe('1');
    });
  });

  describe('with a drive-time filter chosen in aurora mode', () => {
    it('keeps the drive-time filter on the context bar', async () => {
      // A filter the reader sets for this visit only, never saved: it stands like the saved ones.
      await openInAurora(['3★+']);
      const toggle = screen.getByRole('button', { name: 'Filters' });
      fireEvent.click(toggle);
      fireEvent.change(screen.getByRole('combobox', { name: 'Filter by drive time from home' }),
        { target: { value: '45' } });
      fireEvent.click(toggle);
      expect(chips()).toEqual(['3★+', '≤45m']);

      await endAlertAndOpenDrawer();

      expect(chips()).toEqual(['3★+', '≤45m']);
    });
  });
});

describe('the Map tab, when the alert ends while it is in aurora mode', () => {
  // An aurora handoff into the tab, as the banner overlay's hatch hands one over — not the pane's
  // whole mount. There is no `heat`, and `autoEventType` is null where the pane passes App's
  // auto-selection. That only matters on a tab already mounted: if the reader has picked a window
  // there (the window control, a landing-card row, the callout's strip) or come in through a Plan
  // door, the handoff turns the override off, and the auto-selection replaces AURORA on the next
  // commit. So this models a tab the hatch mounts. The forecast dates are passed so the solar-window
  // gate works as it does in production, where a pins test copied here would otherwise see an
  // ungated view the tab never draws.
  const tab = (props = {}) => (
    <MapView
      locations={LOCATIONS}
      date={THE_NIGHT}
      forecastDates={FORECAST_DATES}
      autoEventType={null}
      handoffEventType="AURORA"
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

    beforeEach(() => { mockRole = 'ADMIN'; });

    it('turns the "unknown" lens off', async () => {
      const { rerender } = await openMap(tab({ locations: WITH_UNSCORED, handoffEventType: null }));
      fireEvent.click(screen.getByTestId('wf-filters-chip'));
      // Control: Cheviot, Hexham and Alnmouth clear the 3★+ default, and unscored Rothbury joins them
      // once the lens is on.
      expect(screen.getAllByTestId('marker')).toHaveLength(3);
      fireEvent.click(screen.getByRole('button', { name: '? unknown' }));
      expect(screen.getAllByTestId('marker')).toHaveLength(4);

      await act(async () => {
        rerender(<AuroraStatusProvider>{tab({ locations: WITH_UNSCORED })}</AuroraStatusProvider>);
      });
      expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');

      await endAlert();

      // Control: aurora mode is over — the Sky row is back.
      expect(within(screen.getByRole('dialog', { name: 'Map filters' }))
        .getByRole('button', { name: /Dark sky only/ })).toBeInTheDocument();
      // The three rated places, without Rothbury. With the lens left on, four.
      expect(screen.getAllByTestId('marker')).toHaveLength(3);
    });
  });
});
