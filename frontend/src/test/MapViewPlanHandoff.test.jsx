/**
 * `MapView`'s door-landing effect (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 2) — ONE
 * nonce-keyed `useEffect` applying a WHOLE `planHandoff` payload atomically, never four more fields
 * folded onto the pre-existing per-field handoff effects (those effects' own doc comments record
 * the staleness defects of applying fields independently — see `MapView.jsx:1362+`).
 *
 * The harness mirrors `MapViewDriveOverride.test.jsx`'s own — a minimal `L.Control` so
 * `CentreOnHomeControl`'s portal lands somewhere `screen` can find it, `MapLabels`/`MapCallout`/
 * `FiltersPopover` mocked to probes exposing exactly the state this file asserts on, since the real
 * children add nothing this file needs and a lot jsdom cannot lay out.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';

let cornerEl;
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });

  class Control {
    constructor(options = {}) { this.options = options; }

    addTo(map) {
      this._container = this.onAdd(map);
      this._container.classList.add('leaflet-control');
      map._corner.appendChild(this._container);
      return this;
    }

    remove() {
      this._container?.remove();
      return this;
    }
  }

  const DomEvent = {
    disableClickPropagation: () => {},
    disableScrollPropagation: () => {},
  };
  // `latLngBounds` for `FitBoundsController` (the `focusPoints` door fits through it).
  const latLngBounds = (points) => ({ points });
  const L = { icon, divIcon, point, Control, DomEvent, latLngBounds };
  return { default: L, ...L };
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
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: (...args) => { flyToCalls.push(args); },
    fitBounds: (bounds) => { fitBoundsCalls.push(bounds); },
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

/** Every `map.flyTo` call - the camera a LOCATION door flies, read off the map. */
const flyToCalls = [];

/** Every `map.fitBounds` call - what the `focusPoints` door's camera does, read off the map. */
const fitBoundsCalls = [];

/** The field, recording the points it is handed - the lens is asserted on what the kernel gets. */
const heatLayerCalls = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { heatLayerCalls.push(props); return <div data-testid="map-heat-layer" />; },
}));

/** Captures every props object handed to `MapLabels` — the filtered/visible pool, for the region
 *  scope-flip assertion (`heatArea`/`areaSpots` vs `spots`). */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));

/** The selection callout, mocked to a probe — proves a location the carried floor filters OUT of
 *  the visible pool still gets its callout, because the effect is a direct state write. */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
    </div>
  ),
}));

/** Every filter/scope figure the effect writes, read straight off what `MapView` hands the REAL
 *  filter popover — never re-derived, so this file cannot disagree with the production wiring
 *  about which prop carries which state. `DRIVE_TIME_TIERS` re-exported because `MapBreadcrumb`
 *  imports it from this same module. */
const filtersPopoverCalls = [];
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: (props) => { filtersPopoverCalls.push(props); return null; },
  DRIVE_TIME_TIERS: [[0, 'Any'], [45, '45 min'], [90, '1h 30'], [150, '2h 30']],
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve({})) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAstroAvailableDates } from '../api/astroApi.js';
import { getAuroraForecastAvailableDates } from '../api/auroraApi.js';
import { deferred } from './askFixtures.js';

const TODAY = '2026-01-15';

// One rated 5★, in "North East" (the area region); one rated 1★ — below the door's 4★ floor, so it
// is dropped from the visible/labelled pool at that floor but is still the door's OWN carried
// location — also in "North East"; one in "Lake District", outside the area, to prove a jump there
// flips scope. All three carry a SUNSET forecast for TODAY so `MapCallout` (gated on
// `selectedLoc && activeMapEvent`) can render regardless of which one is selected.
const NEAR = {
  id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 5, solarEventTime: `${TODAY}T08:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
};
const FILTERED_OUT = {
  id: 2, name: 'Filtered Out', lat: 55.62, lon: -1.72, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 1, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 10, goldenHourPotential: 10 },
  }]]),
};
const FAR = {
  id: 3, name: 'Far', lat: 54.45, lon: -3.0, regionName: 'Lake District', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
};
// A SECOND 1★ location, never selected — proves the 4★ floor really filters at all. `labelSpots`
// (`MapView.jsx`'s own doc block) deliberately re-appends the SELECTED location even when the
// floor would otherwise drop it, so `Filtered Out` (selected, 1★) surviving into `MapLabels`' pool
// is that rule working as designed, not evidence the floor did nothing — this location is the one
// that actually demonstrates the floor is active.
const EXCLUDED_UNSELECTED = {
  id: 4, name: 'Excluded Unselected', lat: 55.63, lon: -1.73, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 1, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 10, goldenHourPotential: 10 },
  }]]),
};

function spotOf(loc, rating = 5) {
  return {
    id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, regionName: loc.regionName,
    rid: loc.regionName, bortleClass: 4, r: [rating],
  };
}

const ALL_LOCATIONS = [NEAR, FILTERED_OUT, FAR, EXCLUDED_UNSELECTED];
const AREA_SPOTS = [spotOf(NEAR, 5), spotOf(FILTERED_OUT, 1), spotOf(EXCLUDED_UNSELECTED, 1)]; // "My area" = North East only
const ALL_SPOTS = [...AREA_SPOTS, spotOf(FAR, 5)];

const HEAT = {
  enabled: true,
  hasHome: false,
  spots: ALL_SPOTS,
  areaSpots: AREA_SPOTS,
  pointsByKey: new Map([[`${TODAY}:SUNSET`, []], [`${TODAY}:SUNRISE`, []]]),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
    { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'Tonight sunrise', time: '08:12', bestRating: 5, conf: 1 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
};

const DOOR_BASE = {
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null,
  minRating: null, limitMinutes: null, locationName: null,
};

async function renderTab(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={ALL_LOCATIONS}
        date={TODAY}
        autoEventType="SUNSET"
        forecastDates={[TODAY]}
        heat={HEAT}
        {...props}
      />,
    );
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
  filtersPopoverCalls.length = 0;
  heatLayerCalls.length = 0;
  fitBoundsCalls.length = 0;
  flyToCalls.length = 0;
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

describe('MapView — the door-landing effect applies the WHOLE payload atomically', () => {
  it('event: sets eventType — the crumb\'s window clause names the carried kind, not the initial one', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 } });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('Tonight sunrise');
  });

  it('event: marks the door\'s window as the reader\'s OWN choice (userHasOverriddenEvent=true), '
      + 'so it survives for a reader who had ALREADY used the window control — the exact case a '
      + 'copied `false` silently clobbers (2026-09-04 review, plan §4 #12)', async () => {
    // Mount on SUNRISE via auto-follow (flag starts false).
    const { rerender } = await renderTab({ autoEventType: 'SUNRISE', planHandoff: null });
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();

    // The reader manually steps the window control — `selectEvRow` sets the flag true, exactly
    // like a click on any row. This is the "has used the window control before" precondition.
    await act(async () => {
      fireEvent.click(screen.getByTestId('wf-win-next'));
    });

    // Now a door lands, naming SUNRISE explicitly.
    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNRISE"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 }}
        />,
      );
    });
    // If the door had copied the hatch's `setUserHasOverriddenEvent(false)`, the flag's
    // true→false transition would be a genuine dependency change on the auto-event effect a few
    // screens up, re-running it on the NEXT commit and silently pulling the event back onto
    // `autoEventType` — this assertion is what catches that, not the shallower "did SUNRISE ever
    // appear" check the first test above makes.
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('sunrise');

    // A LATER autoEventType prop change must not move it either — the door's choice is sticky,
    // the same way a manual window-control press already is.
    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 }}
        />,
      );
    });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('sunrise');
  });

  it('floor: minRating null lands as minStars=1 — NEVER null — and never touches showUnrated', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, minRating: null, nonce: 1 } });
    const last = filtersPopoverCalls.at(-1);
    expect(last.minStars).toBe(1);
    expect(last.showUnrated).toBe(false);
  });

  it('floor: a real minRating lands as that exact minStars value', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, minRating: 4, nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
  });

  it('tier: limitMinutes null lands as driveTimeFilter=0 (Any)', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, limitMinutes: null, nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(0);
  });

  it('tier: a real limitMinutes lands as that exact driveTimeFilter value', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, limitMinutes: 150, nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);
  });

  it('region: a region OUTSIDE "My area" jumps AND flips scope to the whole catalogue', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, region: 'Lake District', nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('Lake District');
    // Far's own spot is only in the whole-catalogue pool, never in AREA_SPOTS — its presence in
    // what MapLabels was handed proves the scope really flipped, not just the crumb's own claim.
    expect(mapLabelsCalls.at(-1).spots.some((s) => s.name === 'Far')).toBe(true);
  });

  it('region: a region ALREADY inside "My area" jumps WITHOUT flipping scope', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, region: 'North East', nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('North East');
  });

  it('no region: resets to My area (resetToMyArea, never FitBoundsController) — proven by first '
      + 'jumping AWAY on an earlier door, so the reset has something to actually undo', async () => {
    // A bare "heatArea is true, no carrying clause" assertion against the DEFAULT render cannot
    // tell "resetToMyArea() ran" apart from "nothing happened at all" — heatArea already defaults
    // to true and jumpFitOverride to null. Jumping away first, on an earlier nonce, makes the
    // reset's own effect observable: if the `region ? jumpToRegion(region) : resetToMyArea()`
    // branch were ever dropped or its condition inverted, this is the version of the test that
    // would actually fail.
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Lake District', nonce: 1 },
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(mapLabelsCalls.at(-1).spots.some((s) => s.name === 'Far')).toBe(true);

    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...DOOR_BASE, region: null, nonce: 2 }}
        />,
      );
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
    // The scope really did narrow back — Far (Lake District) is out of the pool again.
    expect(mapLabelsCalls.at(-1).spots.some((s) => s.name === 'Far')).toBe(false);
  });

  it('location: resolves off the FULL roster — the door\'s own carried location gets its callout '
      + 'even though the carried floor filters it out of the visible pool', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, minRating: 4, locationName: 'Filtered Out', nonce: 1 },
    });
    // The floor really is active — a DIFFERENT, unselected 1★ location is dropped from the
    // labelled pool. (`Filtered Out` itself, being SELECTED, is re-appended into that same pool
    // by `labelSpots`' own "the selected location always gets its chip" rule — its presence there
    // would prove nothing about the floor one way or the other, which is why this checks the
    // OTHER 1★ location instead.)
    expect(mapLabelsCalls.at(-1).spots.some((s) => s.name === 'Excluded Unselected')).toBe(false);
    // Yet the callout — a direct state write off the FULL roster, never a filtered lookup — still
    // names the carried location.
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Filtered Out');
  });
});

describe('MapView — the door-landing effect is nonce-guarded, not payload-guarded', () => {
  it('a REPEAT of the same nonce is ignored even when the payload itself changed', async () => {
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', minRating: null, nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('sunrise');
    expect(filtersPopoverCalls.at(-1).minStars).toBe(1);

    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          // Same nonce, DIFFERENT values — must be ignored.
          planHandoff={{ ...DOOR_BASE, eventType: 'SUNSET', minRating: 4, nonce: 1 }}
        />,
      );
    });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('sunrise');
    expect(filtersPopoverCalls.at(-1).minStars).toBe(1);
  });

  it('a NEW nonce with the SAME payload shape applies again', async () => {
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', minRating: null, nonce: 1 },
    });
    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...DOOR_BASE, eventType: 'SUNSET', minRating: 4, nonce: 2 }}
        />,
      );
    });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('sunset');
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
  });
});

describe('MapView — a `source: \'map\'` handoff moves the window and the selection, and NOTHING else', () => {
  // ⚠️ The four-day sheet's own footer, pressed while that sheet is already OVER the map (the
  // callout's `Four days here ›` peek). The reader never left, so nothing was carried: applying the
  // door block would overwrite the floor and the tier they set on this very map, snap scope back to
  // My area and refit the camera — from a press that asked only to change the window.
  const IN_PLACE = { source: 'map', eventType: 'SUNRISE', date: TODAY, locationName: null };

  it('applies NO lens, NO tier and NO scope reset — the map keeps what the reader set on it', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    // The reader's own state on this map — driven through the SAME callbacks `MapView` hands the
    // real popover, so this cannot set state by a route production does not have.
    await act(async () => { filtersPopoverCalls.at(-1).onSelectMinStars(4); });
    await act(async () => { filtersPopoverCalls.at(-1).onSelectDriveTime(150); });
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);

    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...IN_PLACE, nonce: 1 }}
        />,
      );
    });

    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);
  });

  it('mounts NO landing strip — "Where you came from · ← Plan" would be false on this route', async () => {
    await renderTab({ planHandoff: { ...IN_PLACE, nonce: 1 } });
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
  });

  it('⚠️ keeps the window it names, against a DIFFERENT autoEventType — the P1 the hatch route lost', async () => {
    // The whole reason this rides the structured channel. The hatch's own event arm sets
    // `userHasOverriddenEvent` FALSE, which is a genuine dependency change on the auto-selection
    // effect: it re-runs and replaces the named window with `autoEventType` a tick later. Codex
    // caught it on the first cut — `Show on map → Monday sunrise` landed on tonight's sunset.
    const { rerender } = await renderTab({ autoEventType: 'SUNSET', planHandoff: null });
    await act(async () => {
      rerender(
        <MapView
          locations={ALL_LOCATIONS}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={{ ...IN_PLACE, eventType: 'SUNRISE', nonce: 1 }}
        />,
      );
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });

  it('still selects the location it names — the half the route DOES keep', async () => {
    await renderTab({
      planHandoff: { ...IN_PLACE, locationName: ALL_LOCATIONS[0].name, nonce: 1 },
    });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent(ALL_LOCATIONS[0].name);
  });
});

describe('MapView — the crumb never mounts without a live, tab-mode door handoff', () => {
  it('is absent with no handoff at all', async () => {
    await renderTab({ planHandoff: null });
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
    // And the map's own default floor (3★) is left untouched — proof the effect is a true no-op.
    expect(filtersPopoverCalls.at(-1).minStars).toBe(3);
  });
});

describe('MapView — the crumb\'s clear button really resets the map (integration, not just the callback wiring)', () => {
  it('resets the floor to the map\'s own default, the tier to Any, and scope to My area', async () => {
    const onClearOrigin = vi.fn();
    await renderTab({
      origin: { id: 'lakes', name: 'Lake District', baseName: 'Keswick' },
      onClearOrigin,
      planHandoff: {
        ...DOOR_BASE, minRating: 4, limitMinutes: 150, region: 'Lake District', nonce: 1,
      },
    });
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'clear' }));
    });

    expect(filtersPopoverCalls.at(-1).minStars).toBe(3); // DEFAULT_MIN_STARS
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(0);
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
    // `onClearOrigin` is called — asserted directly, rather than through the `origin` prop
    // changing, because THIS component receives `origin` as a plain prop from its caller (the
    // context live-value plumbing is `WindowFirstMapPane`'s job, not `MapView`'s own); a real
    // click would flow `setOrigin(null)` back through the provider and re-render this component
    // with `origin=null` on the next tick, which is exactly what this callback stands in for.
    expect(onClearOrigin).toHaveBeenCalledTimes(1);
    // The crumb itself survives clear — only the RATING/REACH/REGION clauses empty (plan §5 rule
    // 4); the origin clause stays because `origin` itself is unchanged in this isolated render.
    expect(screen.getByTestId('wf-map-breadcrumb')).toBeInTheDocument();
    const carrying = screen.getByTestId('wf-map-breadcrumb-carrying');
    expect(carrying).not.toHaveTextContent('★+');
    expect(carrying).not.toHaveTextContent('within');
    expect(carrying).not.toHaveTextContent('Lake District');
  });
});

describe('MapView — the crumb\'s clear keeps a keyboard reader inside the pane', () => {
  // A door carrying a 4★ floor and a location: `clear` resets the floor to 3★, which ends the only
  // clause and takes `clear` with it, while `Near` (5★) stays selected — a selection the pane's own
  // Escape rule is there to clear, and so the proof that a key pressed after the press reaches it.
  const DOOR = { ...DOOR_BASE, minRating: 4, locationName: 'Near', nonce: 1 };

  async function pressClear() {
    const clear = screen.getByRole('button', { name: 'clear' });
    // `fireEvent.click` does not move focus, so a keyboard reader's focus is put there first.
    clear.focus();
    expect(clear).toHaveFocus();
    await act(async () => {
      fireEvent.click(clear);
    });
  }

  it('lands the reader on the crumb when the press takes clear away', async () => {
    await renderTab({ planHandoff: DOOR });

    await pressClear();

    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('navigation', { name: 'Where you came from' }));
  });

  it('lets an Escape pressed straight after it reach the pane, which clears the selection', async () => {
    await renderTab({ planHandoff: DOOR });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');

    await pressClear();
    // The premise, asserted: an Escape pressed while `clear` itself still held focus would reach the
    // pane too, so this test proves nothing unless the press really took `clear` away.
    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('navigation', { name: 'Where you came from' }));
    // At the FOCUSED node, as a keyboard sends it — from `<body>` the pane's `onKeyDown` never runs.
    await act(async () => {
      fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    });

    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });

  it('lands the reader on the crumb with a dialog from outside the pane open over it — no foreign-modal '
      + 'guard, by decision', async () => {
    // The four-day sheet is exactly this to the pane: an `aria-modal` dialog outside it, holding no
    // trap, so a keyboard reader can Tab back onto `clear` and press it. `MapBreadcrumb`'s class
    // comment records why the handoff does not stand down for it, as `useRowFocusRescue` would.
    await renderTab({ planHandoff: DOOR });
    const sheet = document.createElement('div');
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    document.body.appendChild(sheet);
    try {
      await pressClear();

      expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
      expect(document.activeElement).toBe(screen.getByRole('navigation', { name: 'Where you came from' }));
    } finally {
      sheet.remove();
    }
  });

  it('leaves clear standing, with the reader on it, when the carried floor is the map\'s own 3★ default', async () => {
    // The premise is asserted, not assumed: if the map's default floor ever moves, `clear` no longer
    // survives this press and the test must say so rather than pass on the other branch.
    await renderTab({ planHandoff: { ...DOOR_BASE, minRating: 3, nonce: 1 } });
    const clear = screen.getByRole('button', { name: 'clear' });

    await pressClear();

    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('carrying 3★+ clear');
    expect(screen.getByRole('button', { name: 'clear' })).toBe(clear);
    expect(document.activeElement).toBe(clear);
  });
});

// ── Overlay convergence C1: the door carries every overlay trigger ───────────────────────────────
// Each new field is ABSENT unless the door asked, so each is proven applied AND proven ignored
// when absent. The harness's heat layer / camera probes read the field and the map's fitBounds.

const pointOf = (loc, rating = 5) => ({
  id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, rid: loc.regionName, r: [rating],
});
const SUNSET_POINTS = [pointOf(NEAR), pointOf(FILTERED_OUT, 1), pointOf(FAR), pointOf(EXCLUDED_UNSELECTED, 1)];
const HEAT_WITH_POINTS = {
  ...HEAT,
  pointsByKey: new Map([[`${TODAY}:SUNSET`, SUNSET_POINTS], [`${TODAY}:SUNRISE`, []]]),
};
const fieldNames = () => heatLayerCalls.at(-1).points.map((p) => p.name).sort();

/** Rerenders with the same props `renderTab` uses, plus whatever the step varies. */
async function rerenderWith(rerender, props = {}) {
  await act(async () => {
    rerender(
      <MapView
        locations={ALL_LOCATIONS}
        date={TODAY}
        autoEventType="SUNSET"
        forecastDates={[TODAY]}
        heat={HEAT_WITH_POINTS}
        {...props}
      />,
    );
  });
}

describe('MapView — the door\'s headline (title · caption)', () => {
  it('prints title and caption in the crumb when the door carries them', async () => {
    await renderTab({
      planHandoff: {
        ...DOOR_BASE, title: 'Tyne and Wear', caption: 'Tomorrow sunrise · 06:12', nonce: 1,
      },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-title')).toHaveTextContent(
      'Tyne and Wear · Tomorrow sunrise · 06:12',
    );
  });

  it('prints no headline when the door carries neither', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, nonce: 1 } });
    expect(screen.queryByTestId('wf-map-breadcrumb-title')).toBeNull();
  });
});

describe('MapView — focusNames: a TEMPORARY, NAMED spot-set lens, restored whole by clear', () => {
  it('narrows the field, the pool and the count to the named spots, and the crumb says so', async () => {
    const { rerender } = await renderTab({ heat: HEAT_WITH_POINTS, planHandoff: null });
    const wholeField = fieldNames();
    expect(wholeField).toEqual(['Excluded Unselected', 'Far', 'Filtered Out', 'Near']);

    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusNames: ['Near', 'Far'], nonce: 1 },
    });

    // The field paints ONLY the named spots - proven on the points the kernel is handed.
    expect(fieldNames()).toEqual(['Far', 'Near']);
    // The floor the door lands on is 1★ (no minRating), so both named spots are in the pool.
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 2 spots');
  });

  it('narrows the "not rated yet" hatch by the same lens as the colour (thin-sample F4)', async () => {
    const unrated = SUNSET_POINTS.map((p) => ({ ...p, r: [0] }));
    const heat = {
      ...HEAT_WITH_POINTS,
      windowStates: new Map([[`${TODAY}:SUNSET`, { unrated, states: new Map() }]]),
    };
    const { rerender } = await renderTab({ heat, planHandoff: null });
    expect(heatLayerCalls.at(-1).unrated.map((p) => p.name).sort())
      .toEqual(['Excluded Unselected', 'Far', 'Filtered Out', 'Near']);

    await rerenderWith(rerender, {
      heat,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near', 'Far'], nonce: 1 },
    });

    expect(heatLayerCalls.at(-1).unrated.map((p) => p.name).sort()).toEqual(['Far', 'Near']);
  });

  it('narrows the stand-down hatch by the same lens as the colour (big-seas F3)', async () => {
    const stood = SUNSET_POINTS.map((p) => ({
      lat: p.lat, lng: p.lng, reason: 'HIGH_CLOUD', rid: p.rid, key: `#${p.id}`,
    }));
    const heat = {
      ...HEAT_WITH_POINTS,
      pointsByKey: new Map([[`${TODAY}:SUNSET`, []], [`${TODAY}:SUNRISE`, []]]),
      windowStates: new Map([[`${TODAY}:SUNSET`, {
        unrated: [],
        stood,
        states: new Map(stood.map((p) => [p.key, 'stood'])),
      }]]),
    };
    const { rerender } = await renderTab({ heat, planHandoff: null });
    expect(heatLayerCalls.at(-1).stood.map((p) => p.key).sort()).toEqual(['#1', '#2', '#3', '#4']);

    await rerenderWith(rerender, {
      heat,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near', 'Far'], nonce: 1 },
    });

    expect(heatLayerCalls.at(-1).stood.map((p) => p.key).sort()).toEqual(['#1', '#3']);
  });

  it('clear restores the field whole: the points handed to the kernel are the count they were before', async () => {
    const { rerender } = await renderTab({ heat: HEAT_WITH_POINTS, planHandoff: null });
    const before = heatLayerCalls.at(-1).points.length;
    expect(before).toBe(4);
    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusNames: ['Near', 'Far'], nonce: 1 },
    });
    expect(heatLayerCalls.at(-1).points).toHaveLength(2);

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(heatLayerCalls.at(-1).points).toHaveLength(before);
    expect(fieldNames()).toEqual(['Excluded Unselected', 'Far', 'Filtered Out', 'Near']);
    expect(screen.queryByText(/showing \d+ spot/)).toBeNull();
  });

  it('is an INTERSECTION with the reader\'s filters, not an override: a named spot below the floor stays out', async () => {
    await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: {
        ...DOOR_BASE, minRating: 4, focusNames: ['Near', 'Excluded Unselected'], nonce: 1,
      },
    });
    // The 1★ spot is named by the door but the 4★ floor still decides the pool; the crumb counts
    // the pool actually drawn, so "showing 1 spot" can never outrun the filters.
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 1 spot');
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).not.toHaveTextContent('showing 1 spots');
  });

  it('⚠️ clears a selection OUTSIDE the incoming lens: no callout, no label for it, and the count matches the drawn pool', async () => {
    // `selectedLoc` resolves off the full roster and `labelSpots` re-appends the selection, so
    // without the clear the old selection outlives a lens that describes only the focused set.
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, locationName: 'Far', minRating: 1, nonce: 1 },
    });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Far');

    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusNames: ['Near'], nonce: 2 },
    });

    expect(screen.queryByTestId('probe-callout')).toBeNull();
    expect(mapLabelsCalls.at(-1).spots.map((s) => s.name)).not.toContain('Far');
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 1 spot');
  });

  it('...but a selection INSIDE the lens survives it', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, locationName: 'Near', nonce: 1 },
    });
    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusNames: ['Near', 'Far'], nonce: 2 },
    });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');
  });

  it('a focusPoints fit clears a selection that is not one of its pins, and keeps one that is', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, locationName: 'Far', nonce: 1 },
    });
    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusPoints: [[55.61, -1.71], [55.62, -1.72]], nonce: 2 },
    });
    expect(screen.queryByTestId('probe-callout')).toBeNull();

    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, locationName: 'Near', nonce: 3 },
    });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');
    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, focusPoints: [[55.61, -1.71], [55.62, -1.72]], nonce: 4 },
    });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');
  });

  it('is ignored when absent: no narrowing, no clause', async () => {
    await renderTab({ heat: HEAT_WITH_POINTS, planHandoff: { ...DOOR_BASE, nonce: 1 } });
    expect(heatLayerCalls.at(-1).points).toHaveLength(4);
    expect(screen.queryByText(/showing/)).toBeNull();
  });

  it('a LATER door without the field ends the lens - it never outlives the door that set it', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near'], nonce: 1 },
    });
    expect(heatLayerCalls.at(-1).points).toHaveLength(1);

    await rerenderWith(rerender, { planHandoff: { ...DOOR_BASE, nonce: 2 } });

    expect(heatLayerCalls.at(-1).points).toHaveLength(4);
    expect(screen.queryByText(/showing/)).toBeNull();
  });

  it('⚠️ a map-sourced handoff (the banner or the sheet footer, over the map) is a window change only: the lens and its breadcrumb STAND (C4, S1)', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near'], nonce: 1 },
    });
    expect(heatLayerCalls.at(-1).points).toHaveLength(1);

    await rerenderWith(rerender, {
      planHandoff: { source: 'map', eventType: 'SUNSET', date: TODAY, locationName: null, nonce: 2 },
    });

    // The strip is still mounted, still naming the lens, and the field is still the named spot's.
    expect(screen.getByTestId('wf-map-breadcrumb')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 1 spot');
    expect(heatLayerCalls.at(-1).points).toHaveLength(1);
  });

  it('a NEW plan door after a map-sourced one replaces the lens and the strip\'s door', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near'], title: 'First', nonce: 1 },
    });
    await rerenderWith(rerender, {
      planHandoff: { source: 'map', eventType: 'SUNSET', date: TODAY, locationName: null, nonce: 2 },
    });
    await rerenderWith(rerender, {
      planHandoff: { ...DOOR_BASE, title: 'Second', nonce: 3 },
    });

    expect(screen.getByTestId('wf-map-breadcrumb-title')).toHaveTextContent('Second');
    expect(heatLayerCalls.at(-1).points).toHaveLength(4);
  });

  it('...and so does the handoff going away altogether (a source-less handoff reaches the map as null)', async () => {
    const { rerender } = await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, focusNames: ['Near'], nonce: 1 },
    });
    await rerenderWith(rerender, { planHandoff: null });
    expect(heatLayerCalls.at(-1).points).toHaveLength(4);
  });

  it('a map-sourced handoff leaves the door\'s subject filter standing; the handoff going away hands the reader\'s filters back', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['SEASCAPE']);

    await rerenderWith(rerender, {
      heat: HEAT,
      planHandoff: { source: 'map', eventType: 'SUNSET', date: TODAY, locationName: null, nonce: 2 },
    });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['SEASCAPE']);

    await rerenderWith(rerender, { heat: HEAT, planHandoff: null });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });
});

describe('MapView — a REGION door frames the region AND selects its top spot (C4)', () => {
  it('an AWAY region with a spot: scope flips to the catalogue, the crumb names the region, the callout opens on the spot', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Lake District', locationName: 'Far', nonce: 1 },
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('Lake District');
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Far');
    expect(mapLabelsCalls.at(-1).spots.some((sp) => sp.name === 'Far')).toBe(true);
  });

  it('...and without the region the same door would strand it out of scope (the defect the region carry fixes)', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, region: null, locationName: 'Far', nonce: 1 } });
    // resetToMyArea: scope back to the area, where `Far`'s region is not.
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
  });

  it('the camera is the REGION\'s fit, not a fly to the one spot (the two would race)', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Lake District', locationName: 'Far', nonce: 1 },
    });
    expect(flyToCalls).toHaveLength(0);
  });

  it('a plain location door (no region) still flies to the spot', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, locationName: 'Near', nonce: 1 } });
    // (The mock's `useMap` is a fresh object each render, so the controller's effect may re-fire.)
    expect(flyToCalls.length).toBeGreaterThan(0);
    expect(flyToCalls.every(([target]) => target[0] === NEAR.lat && target[1] === NEAR.lon)).toBe(true);
  });

  it('a region the catalogue holds no spots for falls back to flying to the spot', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Nowhere', locationName: 'Near', nonce: 1 },
    });
    expect(flyToCalls.length).toBeGreaterThan(0);
  });

  it('a region already in My area keeps scope and still selects the spot', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'North East', locationName: 'Near', nonce: 1 },
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');
  });
});

describe('MapView — a NAMED spot is exempt from the unrated withholding, not from the reader\'s filters (C4)', () => {
  const UNRATED_A = {
    id: 5, name: 'Unrated A', lat: 55.64, lon: -1.74, regionName: 'North East', locationType: ['LANDSCAPE'],
    forecastsByDate: new Map(),
  };
  const UNRATED_B = {
    id: 6, name: 'Unrated B', lat: 55.65, lon: -1.75, regionName: 'North East', locationType: ['LANDSCAPE'],
    forecastsByDate: new Map(),
  };
  const WITH_UNRATED = [...ALL_LOCATIONS, UNRATED_A, UNRATED_B];

  it('an unrated named spot is DRAWN (not withheld), so the card does not open on an empty map', async () => {
    await renderTab({
      locations: WITH_UNRATED,
      planHandoff: { ...DOOR_BASE, focusNames: ['Unrated A', 'Unrated B'], nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 2 spots');
  });

  it('a STOOD-DOWN named spot is drawn too, and an unnamed stood-down one is not', async () => {
    const stood = (id, name) => ({
      id, name, lat: 55.7, lon: -1.8, regionName: 'North East', locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TODAY, { sunset: { rating: null, triageReason: 'HIGH_CLOUD' } }]]),
    });
    await renderTab({
      locations: [...ALL_LOCATIONS, stood(7, 'Stood A'), stood(8, 'Stood B')],
      planHandoff: { ...DOOR_BASE, focusNames: ['Stood A', 'Near'], nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 2 spots');
    expect(mapLabelsCalls.at(-1).spots.map((sp) => sp.name)).not.toContain('Stood B');
  });

  it('an unrated spot that is NOT named is still withheld (the exemption is the named set\'s alone)', async () => {
    await renderTab({
      locations: WITH_UNRATED,
      planHandoff: { ...DOOR_BASE, focusNames: ['Unrated A', 'Near'], nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('showing 2 spots');
    expect(mapLabelsCalls.at(-1).spots.map((sp) => sp.name)).not.toContain('Unrated B');
  });

  it('the reader\'s own filters still apply to it: a reach tier with no drive time drops it, and the crumb says WHY, never "showing 0"', async () => {
    await renderTab({
      locations: WITH_UNRATED,
      planHandoff: {
        ...DOOR_BASE, limitMinutes: 45, focusNames: ['Unrated A', 'Unrated B'], nonce: 1,
      },
    });
    const carrying = screen.getByTestId('wf-map-breadcrumb-carrying');
    expect(carrying).toHaveTextContent('none of the 2 named spots is rated for this day');
    expect(carrying).not.toHaveTextContent(/showing 0/);
  });

  it('...singular for one named spot', async () => {
    await renderTab({
      locations: WITH_UNRATED,
      planHandoff: { ...DOOR_BASE, limitMinutes: 45, focusNames: ['Unrated A'], nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying'))
      .toHaveTextContent('the named spot is not rated for this day');
  });

  it('when the named spots ARE rated but the reader\'s filters hold them back, it says so instead', async () => {
    await renderTab({
      heat: HEAT_WITH_POINTS,
      planHandoff: { ...DOOR_BASE, minRating: 4, focusNames: ['Excluded Unselected', 'Filtered Out'], nonce: 1 },
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying'))
      .toHaveTextContent('none of the 2 named spots passes your filters');
  });
});

describe('MapView — the breadcrumb\'s return names the tab the door came from (C4, S2)', () => {
  it.each([
    ['coming-up', 'Coming up'],
    ['operations', 'Operations'],
    [undefined, /^At a Glance/],
  ])('a door from %s returns there', async (from, label) => {
    const onReturnToPlan = vi.fn();
    await renderTab({
      onReturnToPlan,
      planHandoff: { ...DOOR_BASE, ...(from ? { from } : {}), nonce: 1 },
    });
    fireEvent.click(screen.getByRole('button', { name: label }));
    expect(onReturnToPlan).toHaveBeenCalledExactlyOnceWith(from ?? 'plan');
  });

  it('keeps naming the door\'s tab after a map-sourced handoff arrives (the return is the last PLAN door\'s)', async () => {
    const onReturnToPlan = vi.fn();
    const { rerender } = await renderTab({
      onReturnToPlan, planHandoff: { ...DOOR_BASE, from: 'coming-up', nonce: 1 },
    });
    await rerenderWith(rerender, {
      onReturnToPlan,
      planHandoff: { source: 'map', eventType: 'SUNSET', date: TODAY, locationName: null, nonce: 2 },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Coming up' }));
    expect(onReturnToPlan).toHaveBeenCalledExactlyOnceWith('coming-up');
  });
});

describe('MapView — focusPoints: the camera fits the named pins', () => {
  const PINS = [[55.61, -1.71], [54.45, -3.0]];

  it('fits the map to the points through the map\'s own fitBounds', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 1 } });
    expect(fitBoundsCalls.some((b) => JSON.stringify(b.points) === JSON.stringify(PINS))).toBe(true);
  });

  it('does not reset scope to My area (the scope reset refits and would race the fit)', async () => {
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Lake District', nonce: 1 },
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    await rerenderWith(rerender, {
      heat: HEAT,
      planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 2 },
    });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
  });

  it('is ignored when absent: no fit, and the scope still resets as it always did', async () => {
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, region: 'Lake District', nonce: 1 },
    });
    const fitsBefore = fitBoundsCalls.filter((b) => b.points).length;
    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });
    // `FitBoundsController` is the only caller handing `fitBounds` a `{points}` bounds object.
    expect(fitBoundsCalls.filter((b) => b.points)).toHaveLength(fitsBefore);
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
  });
});

describe('MapView — filterAction / darkSky: the door\'s subject and dark-sky lens', () => {
  it('filterAction switches the subject filter on and the crumb names it; clear takes it off again', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 } });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['SEASCAPE']);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('coastal spots');

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
  });

  it('darkSky switches the toggle on and the crumb names it; clear switches it off', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, darkSky: true, nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('dark-sky spots');

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('is ignored when absent: a plain door touches neither filter and names neither', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('a later plain door undoes the earlier door\'s lens...', async () => {
    const { rerender } = await renderTab({
      planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', darkSky: true, nonce: 1 },
    });
    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });
    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('...but never a filter the READER set themselves', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);

    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 1 } });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });

  it('⚠️ clear hands back the filters the READER set - dark sky, and SEASCAPE + LANDSCAPE as they left them', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters].sort()).toEqual(['LANDSCAPE', 'SEASCAPE']);

    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['SEASCAPE']);
    // A chip the reader adds AFTER the door is theirs too.
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters].sort()).toEqual(['LANDSCAPE', 'SEASCAPE']);
  });

  it('a dark-sky door puts dark sky back to what the reader had: on stays on, off goes off', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, darkSky: true, nonce: 1 },
    });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
  });

  it('the NEXT door restores the reader\'s filters too, not an empty set', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });

  it('⚠️ Clear all ends the door\'s filter lens: the breadcrumb\'s clear does not resurrect a filter the reader cleared', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', focusNames: ['Near'], nonce: 1 },
    });
    await act(async () => { filtersPopoverCalls.at(-1).onClearAll(); });
    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('...and the NEXT door starts from an empty base, not the pre-door snapshot', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    await act(async () => { filtersPopoverCalls.at(-1).onClearAll(); });

    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });

    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
  });

  it('⚠️ SEASCAPE on before a coastal door, switched off under it: the breadcrumb\'s clear leaves it off', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', darkSky: true, nonce: 1 },
    });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    expect(filtersPopoverCalls.at(-1).activeTypeFilters.has('SEASCAPE')).toBe(false);

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).activeTypeFilters.has('SEASCAPE')).toBe(false);
  });

  it('⚠️ dark sky on before a dark-sky door, switched off under it: the next door leaves it off', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, darkSky: true, nonce: 1 },
    });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);

    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('⚠️ LANDSCAPE + SEASCAPE on, coastal door, SEASCAPE switched off: the breadcrumb\'s clear brings LANDSCAPE back', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', focusNames: ['Near'], nonce: 1 },
    });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['SEASCAPE']);
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });

  it('...and a second plain door starts from {LANDSCAPE} too', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });

    await rerenderWith(rerender, { heat: HEAT, planHandoff: { ...DOOR_BASE, nonce: 2 } });

    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });

  it('a dark-only door with LANDSCAPE visible: the reader removes LANDSCAPE, clear leaves it off and dark back to before', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, darkSky: true, nonce: 1 },
    });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });

    expect(filtersPopoverCalls.at(-1).activeTypeFilters.size).toBe(0);
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
  });

  it('control: LANDSCAPE on before a coastal door, untouched under it: the breadcrumb\'s clear restores it', async () => {
    const { rerender } = await renderTab({ planHandoff: null });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('LANDSCAPE'); });
    await rerenderWith(rerender, {
      heat: HEAT, planHandoff: { ...DOOR_BASE, filterAction: 'SEASCAPE', nonce: 1 },
    });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'clear' })); });
    expect([...filtersPopoverCalls.at(-1).activeTypeFilters]).toEqual(['LANDSCAPE']);
  });

  it('the clause goes when the reader turns the carried filter off themselves (a live claim)', async () => {
    await renderTab({ planHandoff: { ...DOOR_BASE, darkSky: true, nonce: 1 } });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
    expect(screen.queryByText(/dark-sky spots/)).toBeNull();
  });
});

describe('MapView — a door locks the window it NAMES; one that names none hands the choice back to auto-selection', () => {
  const NO_EVENT = { ...DOOR_BASE, eventType: null, nonce: 1 };

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
    getAstroAvailableDates.mockResolvedValue([TODAY]);
  });

  afterEach(() => {
    vi.useRealTimers();
    getAstroAvailableDates.mockReset();
    getAstroAvailableDates.mockResolvedValue([]);
  });

  it('⚠️ a door naming no event does not leave the map on a stale night: it lands on the date\'s own solar event', async () => {
    const { rerender } = await renderTab({ autoEventType: 'SUNSET', planHandoff: null });
    // Put the map on the astro night by the reader's own choice (the window control's next/prev).
    for (let i = 0; i < 4 && !/astro/i.test(screen.getByTestId('wf-win-pill').textContent); i += 1) {
      await act(async () => { fireEvent.click(screen.getByTestId('wf-win-next')); });
    }
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);

    await rerenderWith(rerender, { autoEventType: 'SUNSET', planHandoff: NO_EVENT });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent(/astro/i);
  });

  it('control: a door NAMING sunrise lands on sunrise and keeps it when auto-selection would prefer sunset', async () => {
    const { rerender } = await renderTab({ autoEventType: 'SUNSET', planHandoff: null });
    await rerenderWith(rerender, {
      autoEventType: 'SUNSET', planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 },
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);

    await rerenderWith(rerender, {
      autoEventType: 'SUNRISE', planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 },
    });
    await rerenderWith(rerender, {
      autoEventType: 'SUNSET', planHandoff: { ...DOOR_BASE, eventType: 'SUNRISE', nonce: 1 },
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });

  it('a door naming no event leaves the window unlocked: a later autoEventType change moves it', async () => {
    const { rerender } = await renderTab({ autoEventType: 'SUNSET', planHandoff: NO_EVENT });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await rerenderWith(rerender, { autoEventType: 'SUNRISE', planHandoff: NO_EVENT });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });
});

describe('MapView — night: select the night\'s row, but not until the row exists', () => {
  const NIGHT_DOOR = {
    ...DOOR_BASE, eventType: 'ASTRO', night: { kind: 'astro', date: TODAY }, nonce: 1,
  };

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
  });

  afterEach(() => {
    vi.useRealTimers();
    getAstroAvailableDates.mockReset();
    getAstroAvailableDates.mockResolvedValue([]);
    getAuroraForecastAvailableDates.mockReset();
    getAuroraForecastAvailableDates.mockResolvedValue([]);
  });

  it('⚠️ waits for a night row that has not loaded yet, and selects it the moment it does', async () => {
    const gate = deferred();
    getAstroAvailableDates.mockReturnValue(gate.promise);
    const onSelectDate = vi.fn();

    // A freshly mounted Map: the door fires with NO astro row in the list yet.
    await renderTab({ onSelectDate, planHandoff: NIGHT_DOOR });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    expect(onSelectDate).not.toHaveBeenCalled();

    await act(async () => { gate.resolve([TODAY]); await gate.promise; });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
    expect(onSelectDate).toHaveBeenCalledTimes(1);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: true });
  });

  it('a pending night door does not mark the window the reader\'s own: auto-selection still corrects the interim window', async () => {
    const gate = deferred();
    getAstroAvailableDates.mockReturnValue(gate.promise);
    const { rerender } = await renderTab({ planHandoff: NIGHT_DOOR });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    // The auto-selection moves on while the night's rows are still loading; a door that had
    // already claimed the window as the reader's own would hold the stale sunset.
    await rerenderWith(rerender, { autoEventType: 'SUNRISE', planHandoff: NIGHT_DOOR });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });

  it('⚠️ a pending night door is superseded by a window Ask chose: the row arriving later does not yank the map back', async () => {
    const gate = deferred();
    getAuroraForecastAvailableDates.mockReturnValue(gate.promise);
    const AURORA_DOOR = {
      ...DOOR_BASE, eventType: 'AURORA', night: { kind: 'aurora', date: TODAY }, nonce: 1,
    };
    const { rerender } = await renderTab({ planHandoff: AURORA_DOOR });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    // Ask follows a SOLAR window while the aurora row is still on its way.
    await rerenderWith(rerender, {
      planHandoff: AURORA_DOOR,
      askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 },
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);

    await act(async () => { gate.resolve([TODAY]); await gate.promise; });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent(/aurora/i);
  });

  it('⚠️ an AURORA night waits for the AURORA row (the aurora fetch, not the astro one)', async () => {
    // `doorFromTrigger` sends kind:'aurora'; its rows come from the aurora availability fetch. A
    // kind that was always looked up as ASTRO would find no row here and never follow it.
    const gate = deferred();
    getAuroraForecastAvailableDates.mockReturnValue(gate.promise);
    getAstroAvailableDates.mockResolvedValue([]);
    const onSelectDate = vi.fn();

    await renderTab({
      onSelectDate,
      planHandoff: {
        ...DOOR_BASE, eventType: 'AURORA', night: { kind: 'aurora', date: TODAY }, nonce: 1,
      },
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    expect(onSelectDate).not.toHaveBeenCalled();

    await act(async () => { gate.resolve([TODAY]); await gate.promise; });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/aurora/i);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: true });
  });

  it('follows a night row that is already there at once, with the row\'s own provenance', async () => {
    getAstroAvailableDates.mockResolvedValue([TODAY]);
    const onSelectDate = vi.fn();
    const { rerender } = await renderTab({ onSelectDate, planHandoff: null });
    onSelectDate.mockClear();

    await rerenderWith(rerender, { onSelectDate, planHandoff: NIGHT_DOOR });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: true });
  });

  it('applies the late row ONCE: a re-render of the same door changes nothing', async () => {
    const gate = deferred();
    getAstroAvailableDates.mockReturnValue(gate.promise);
    const onSelectDate = vi.fn();
    const { rerender } = await renderTab({ onSelectDate, planHandoff: NIGHT_DOOR });
    await act(async () => { gate.resolve([TODAY]); await gate.promise; });
    onSelectDate.mockClear();

    await rerenderWith(rerender, { onSelectDate, planHandoff: NIGHT_DOOR });

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('a reader who chooses their own window before the row lands is not yanked onto it', async () => {
    const gate = deferred();
    getAstroAvailableDates.mockReturnValue(gate.promise);
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate, planHandoff: NIGHT_DOOR });
    // The reader steps the window control themselves while the night row is still on its way.
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-prev')); });
    const chosen = screen.getByTestId('wf-win-pill').textContent;
    // The premise, asserted: the press really moved the window (off the initial sunset).
    expect(chosen).toMatch(/sunrise/i);

    await act(async () => { gate.resolve([TODAY]); await gate.promise; });

    expect(screen.getByTestId('wf-win-pill').textContent).toBe(chosen);
    expect(onSelectDate).not.toHaveBeenCalledWith(TODAY, { isNight: true });
  });

  it('is ignored when absent: a solar door never selects a night', async () => {
    getAstroAvailableDates.mockResolvedValue([TODAY]);
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate, planHandoff: { ...DOOR_BASE, nonce: 1 } });
    expect(onSelectDate).not.toHaveBeenCalledWith(TODAY, { isNight: true });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
  });

  it('⚠️ a map-sourced handoff follows the night it names (the aurora banner pressed ON the Map tab, C4) and imports no lens', async () => {
    getAstroAvailableDates.mockResolvedValue([TODAY]);
    const onSelectDate = vi.fn();
    await renderTab({
      onSelectDate,
      planHandoff: { ...NIGHT_DOOR, source: 'map' },
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: true });
    // A map-sourced handoff is a window change and nothing more: no breadcrumb, no lens.
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
  });
});
