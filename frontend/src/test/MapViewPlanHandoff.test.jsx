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
  const L = { icon, divIcon, point, Control, DomEvent };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

/** Captures every props object handed to `MapLabels` — the filtered/visible pool, for the region
 *  scope-flip assertion (`heatArea`/`areaSpots` vs `spots`). */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => <div data-testid="pins-layer" /> }));

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

describe('MapView — the crumb never mounts without a live, tab-mode door handoff', () => {
  it('is absent with no handoff at all', async () => {
    await renderTab({ planHandoff: null });
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
    // And the map's own default floor (3★) is left untouched — proof the effect is a true no-op.
    expect(filtersPopoverCalls.at(-1).minStars).toBe(3);
  });

  it('is absent on the overlay, even with a door handoff present', async () => {
    await renderTab({
      overlayMode: true,
      planHandoff: { ...DOOR_BASE, nonce: 1 },
    });
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
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
