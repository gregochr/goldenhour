/*
 * The label-obstacle measurement page (docs/engineering/map-landing-plan.md §4b.1) — NOT SHIPPED,
 * not referenced by the app, not built by `npm run build`.
 *
 * It mounts the REAL map-frame surfaces inside the REAL ancestor chain
 * (`.wf-shell` > `.wf-body.wf-body--map` > `.wf-map-tab` > the relative frame) so a headless
 * browser can read their true rects, and renders every label kind off-flow so `measure.mjs` can
 * read a true `offsetWidth`/`offsetHeight` for each — the same properties `MapLabels`' own measure
 * pass reads.
 *
 * `measure.mjs` injects `window.__R5_ROSTER__` and `window.__R5_SURFACE__` before this module runs.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// ⚠️ BOTH, and `fonts.js` is not optional. `index.css` declares the font FAMILIES but carries no
// `@font-face` at all — the self-hosted faces are registered by `fonts.js`, which the app's real
// entry point imports (`frontend/src/main.jsx`). Without it Chromium measures fallback system
// fonts, `document.fonts.ready` cannot correct it because the real faces were never requested, and
// every box in this measurement is a metric of the wrong typeface.
import '../../../../frontend/src/fonts.js';
// ⚠️ BOTH, and in this order — `MapView.jsx` imports Leaflet's stylesheet, and `index.css`'s own
// comment records that it must load second. The Leaflet corner below is positioned by it.
import 'leaflet/dist/leaflet.css';
import '../../../../frontend/src/index.css';
import MapLandingCard from '../../../../frontend/src/components/map/MapLandingCard.jsx';
import MapWindowPanel from '../../../../frontend/src/components/map/MapWindowPanel.jsx';
import MapRegionPanel from '../../../../frontend/src/components/map/MapRegionPanel.jsx';
import WindowControl from '../../../../frontend/src/components/map/WindowControl.jsx';
import TideWave from '../../../../frontend/src/components/map/TideWave.jsx';
import RegionsJump from '../../../../frontend/src/components/map/RegionsJump.jsx';
import FiltersPopover from '../../../../frontend/src/components/map/FiltersPopover.jsx';
import MapLegendPanel from '../../../../frontend/src/components/map/MapLegendPanel.jsx';
// `MAP_FILTER_CHIPS` is derived inside `MapView.jsx` and not exported; rebuilt here from the same
// two constants it uses, so the Filters chip's own width is the app's rather than a stand-in.
import { LOCATION_TYPE_META, DISPLAY_TYPES } from '../../../../frontend/src/utils/locationTypes.js';
import { rampGradientCss } from '../../../../frontend/src/utils/scoreRamp.js';

const MAP_FILTER_CHIPS = DISPLAY_TYPES.map((type) => [type, LOCATION_TYPE_META[type]]);
import { rampHex } from '../../../../frontend/src/utils/scoreRamp.js';

const roster = window.__R5_ROSTER__ || [];
const spots = window.__R5_SPOTS__ || [];

const EV_ROWS = [
  {
    id: 'ev-1', kind: 'solar', eventType: 'SUNSET', label: 'Tonight’s sunset',
    time: '20:14', confidence: 'MEDIUM', pickKind: 'best', pickRegion: 'Northumberland & Tyneside',
    served: true, bestRating: 4, dayWord: 'Today', date: '2026-09-06', dayLabel: 'Tonight', scored: true, badges: [],
  },
  {
    id: 'ev-2', kind: 'solar', eventType: 'SUNRISE', label: 'Tomorrow’s sunrise',
    time: '06:22', confidence: 'MEDIUM', pickKind: 'also', served: true, bestRating: 3,
    dayWord: 'Tomorrow', date: '2026-09-07', dayLabel: 'Tomorrow', scored: true, badges: [],
  },
  {
    id: 'ev-3', kind: 'solar', eventType: 'SUNSET', label: 'Tomorrow’s sunset',
    time: '20:11', confidence: 'LOW', served: true, bestRating: 3, dayWord: 'Tomorrow',
    date: '2026-09-07', dayLabel: 'Tomorrow', scored: true, badges: [],
  },
];

const REGION_NAMES = [...new Set(roster.map((r) => r.region))];

/**
 * ⚠️ `MapWindowPanel` renders ONE ROW PER IN-SCOPE REGION, uncapped, so its height is a function of
 * the roster — and the dev seed has only four regions. Measuring at four measures a shorter
 * obstacle than a nine-region roster draws; whether a real roster costs more OVERALL is a different
 * question this harness does not answer (its chip density pulls the other way). `__R5_REGIONS__` lets `measure.mjs` measure the panel at a
 * production-shaped count as well; the extra rows are synthetic names of a representative length.
 */
const PANEL_REGION_COUNT = window.__R5_REGIONS__ || REGION_NAMES.length;
const PANEL_REGION_NAMES = Array.from({ length: PANEL_REGION_COUNT }, (_, i) => (
  REGION_NAMES[i] ?? `Region ${i + 1} of the North`
));

const PANEL_ROWS = PANEL_REGION_NAMES.map((name, i) => ({
  name,
  tier: ['GO', 'MARGINAL', 'STANDDOWN'][i % 3],
  verdictLabel: ['Worth it', 'Maybe', 'Not tonight'][i % 3],
  meanRating: 3.4 - (i * 0.1),
  bestRating: 5 - (i % 4),
  driveMinutes: 25 + i * 14,
  driveLabel: `${25 + i * 14}m`,
  placeCount: Math.max(3, roster.filter((r) => r.region === name).length),
  atFourPlus: Math.max(1, Math.round((roster.filter((r) => r.region === name).length || 9) / 3)),
}));

const LOCATIONS = roster.slice(0, 4).map((r, i) => ({
  id: i + 1,
  name: r.name,
  rating: 5 - i,
  driveLabel: `${30 + i * 12}m`,
  leaveTime: `1${7 + i}:4${i}`,
  leaveDayWord: i > 2 ? 'tomorrow' : null,
  tideOnLight: i === 0,
}));

const LANDING_MODEL = {
  header: 'Tonight and tomorrow morning',
  allPoor: false,
  lead: null,
  nextUp: null,
  rows: [
    {
      row: EV_ROWS[0],
      verdict: { tier: 'GO', regionName: 'Northumberland & Tyneside', sharingCount: 2 },
    },
    {
      row: EV_ROWS[1],
      verdict: { tier: 'MARGINAL', regionName: 'Yorkshire Dales', sharingCount: 0 },
    },
  ],
  picks: [{ row: EV_ROWS[2], kind: 'also' }],
};

const noop = () => {};

function Chips() {
  return (
    <div id="r5-chips">
      {/* ⚠️ **This markup must stay byte-identical to `MapLabels.jsx`'s own chip.** It shipped
          missing two children — the `TideWave` glyph and the `N★` badge — and every measured box
          came out 26-45px (17-28%) too narrow. That is not cosmetic: the whole question is whether
          a box still fits beside a widened obstacle, and under-measuring every box biases every
          answer toward "it fits". It also mis-sizes `mapDxOffsets`, which is derived from the box's
          own width. Two independent review lenses caught it, and correcting it changed the
          headline result. If you touch the real chip, touch this. */}
      {spots.map((s) => {
        const hasRating = Number.isFinite(s.rating);
        return (
          <button
            key={s.name}
            type="button"
            className="wf-maplab-chip"
            data-r5-chip={s.name}
            data-tide={s.onTheLight ? 'true' : undefined}
          >
            <i
              className="wf-maplab-chip-m"
              style={{ background: hasRating ? rampHex(s.rating) : 'var(--color-plex-border-light)' }}
            />
            <b className="wf-maplab-chip-n">{s.name}</b>
            {s.onTheLight && <TideWave className="wf-maplab-chip-tw" />}
            {hasRating && <em className="wf-maplab-chip-r">{s.rating}★</em>}
          </button>
        );
      })}
      {REGION_NAMES.map((rid) => (
        <span key={rid} className="wf-maplab-region" data-r5-region={rid}>{rid}</span>
      ))}
      {REGION_NAMES.map((rid) => (
        <span key={`t-${rid}`} className="wf-maplab-region" data-tiny="true" data-r5-region-tiny={rid}>{rid}</span>
      ))}
      <span className="wf-hm" data-r5-home="1"><i className="wf-hm-mk" /><span className="wf-hm-lb">HOME</span></span>
      {['25 mi', '50 mi', '1h 30m', '45m'].map((t) => (
        <span key={t} className="wf-ringlb" data-r5-ring={t}>{t}</span>
      ))}
    </div>
  );
}

/**
 * The chrome that is on screen in every ordinary map state — mounted from the REAL components, not
 * approximated as rectangles.
 *
 * ⚠️ The first cut of this measurement modelled `.wf-map-chrome-tr` as a single invented
 * `224.45 x 36` box. The 224.45 is a real recorded width; the 36 was a guess, and the cluster is a
 * flex COLUMN of up to three chips. A review lens showed the panels' collateral count going 0 → 1
 * at a plausible taller value, i.e. the guess was load-bearing. Mounting the real thing is the
 * only way to stop that being a free parameter.
 */
function AlwaysOnChrome() {
  const jumpRows = REGION_NAMES.map((name, i) => ({
    name, driveMinutes: 25 + i * 14, beyondArea: i > 2, bestRating: 5 - (i % 4),
  }));
  return (
    <>
      <div className="wf-map-chrome-tr" data-testid="wf-map-chrome-tr">
        <RegionsJump
          open={false}
          onOpenChange={noop}
          rows={jumpRows}
          onSelectRegion={noop}
          activeRegion={null}
          resetLabel={null}
          onReset={noop}
        />
        <div data-testid="wf-map-toolbar" className="wf-map-toolbar-cluster">
          <div className="wf-map-toolbar-row">
            <div className="wf-seg" role="group" aria-label="Map view">
              <button type="button" aria-pressed className="wf-seg-btn on">Heat</button>
              <button type="button" aria-pressed={false} className="wf-seg-btn">
                <span aria-hidden="true">◍ </span>
                Pins
              </button>
            </div>
          </div>
          <div className="wf-map-key" role="img" aria-label="Colour key: the field runs from Poor to Worth it">
            <span aria-hidden="true">Poor</span>
            <span aria-hidden="true" className="wf-map-key-ramp" style={{ background: rampGradientCss() }} />
            <span aria-hidden="true">Worth it</span>
          </div>
        </div>
        <FiltersPopover
          open={false}
          onOpenChange={noop}
          minStars={0}
          onSelectMinStars={noop}
          activeTypeFilters={new Set()}
          onToggleType={noop}
          subjectChips={MAP_FILTER_CHIPS}
          seasonalFeatures={[]}
          role="PRO_USER"
          driveTimeFilter={0}
          onSelectDriveTime={noop}
          darkSkyFilter={false}
          onToggleDarkSky={noop}
          darkSkyThreshold={4}
          hasHome
          heatArea
          onSelectScope={noop}
          areaLabel="My area"
          activeCount={0}
          filteredCount={spots.length}
          scopeCount={spots.length}
          onClearAll={noop}
        />
      </div>

      {/* ⚠️ `!isMobile` in `MapView.jsx` — the Legend panel is withheld on a phone, where the
          bottom bar occupies that corner. Without this gate the harness drew a 98x36 chip at 390px
          that the app never renders. */}
      <div className="wf-map-chrome-bl" data-testid="wf-map-chrome-bl">
        {window.innerWidth >= 640 && <MapLegendPanel
          open={false}
          onOpenChange={noop}
          handoverFraction={0.5}
          ringsEnabled={false}
          onToggleRings={noop}
          hasHome
          reachMeasured
        />}
      </div>

      <div
        data-testid="photocast-scored-legend"
        className="absolute bottom-2 right-[54px] z-[1100] bg-plex-surface/80 backdrop-blur-sm
          text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 wf-map-scored-legend"
        style={{ fontSize: '11px' }}
      >
        ★ PhotoCast-scored locations shown
      </div>

      {/* ⚠️ Leaflet's OWN bottom-right corner — the second obstacle root. `MapLabels` seeds this
          separately (`LEAFLET_CORNER_SELECTOR`, queried from the map container rather than its
          parent), and the harness omitted it entirely until a review pointed it out: every
          comparison was running with less competition than production.

          Markup copied from what actually renders: Leaflet's `Control.Zoom._createButton` emits
          `<a class="leaflet-control-zoom-in" href="#" title="Zoom in"><span aria-hidden>+</span></a>`
          inside `.leaflet-control-zoom.leaflet-bar.leaflet-control`, and `CentreOnHomeControl`
          builds `div.leaflet-bar.map-home-control` (MapView.jsx) into which it portals its button.
          Both are sized by this app's own rules in index.css, which is why they must be real class
          names rather than a stand-in box.

          ⚠️ Visible on the three larger frames only: `@media (max-width: 639px)` sets
          `display: none` on both controls, so on the phone this corner holds the attribution
          alone. That is a real difference, not a harness simplification — the measurement reads
          whatever the CSS produces at each width. */}
      <div className="leaflet-control-container">
        <div className="leaflet-bottom leaflet-right" data-testid="r5-leaflet-corner">
          <div className="leaflet-control-zoom leaflet-bar leaflet-control">
            <a className="leaflet-control-zoom-in" href="#" title="Zoom in" role="button">
              <span aria-hidden="true">+</span>
            </a>
            <a className="leaflet-control-zoom-out" href="#" title="Zoom out" role="button">
              <span aria-hidden="true">&#x2212;</span>
            </a>
          </div>
          <div className="leaflet-bar map-home-control leaflet-control">
            <button type="button" title="Centre on home">&#8962;</button>
          </div>
          {/* ⚠️ The attribution's CONTENT, not a stand-in — it is the widest thing in this corner
              and `index.css` records its rect spanning the full frame width on a phone, so a short
              placeholder undersizes the obstacle badly. Leaflet's default `prefix` plus the base
              `TileLayer`'s own `attribution` string (MapView.jsx), joined by Leaflet's ` | `. The
              reference layer carries no attribution of its own. An earlier cut stopped after
              "© OpenStreetMap" and produced a 128px corner where production is far wider. */}
          <div className="leaflet-control-attribution leaflet-control">
            <a href="https://leafletjs.com" title="A JavaScript library for interactive maps">Leaflet</a>
            {' | Tiles © Esri — Esri, HERE, Garmin, © OpenStreetMap contributors, GIS User Community'}
          </div>
        </div>
      </div>

      <div data-testid="wf-map-counts-footer" className="wf-map-counts-footer">
        <span>
          <b>{spots.length}</b>
          {` of ${spots.length} shown`}
        </span>
      </div>
    </>
  );
}

function Harness() {
  const which = window.__R5_SURFACE__;
  return (
    // ⚠️ The map frame is NOT the viewport. `App.jsx` pads `<main>` by `sm:px-4` (32px total, at
    // >=640px only) and `WindowFirstShell` wraps the pane in a block capped at `WRAP_MAX_WIDTH`
    // (1080px). So a 1280px window yields a 1080px frame, and 1024/820 yield 992/788 — different
    // projections and different chrome coordinates from the ones a viewport-wide frame produces.
    <main className="wf-r5-main">
      <div className="wf-r5-wrap">
        <div className="wf-shell">
          <div className="wf-body wf-body--map">
            <div className="flex flex-col flex-1 min-h-0 wf-map-tab">
          <div
            id="r5-frame"
            style={{ position: 'relative', flex: 1, minHeight: 0, overflow: 'hidden' }}
          >
            <div className="wf-map-chrome-tl" data-testid="wf-map-chrome-tl">
              <WindowControl
                events={EV_ROWS}
                activeIndex={0}
                onSelect={noop}
                onOpenChange={noop}
                open={false}
                verdicts={new Map([['ev-1', { tier: 'GO' }], ['ev-2', { tier: 'MARGINAL' }], ['ev-3', { tier: 'MARGINAL' }]])}
                scopeIsArea
                landingLabel="Tonight · sunset"
                onReopenLanding={noop}
                onOpenWindowPanel={noop}
              />
            </div>
            {which === 'land' && (
              <MapLandingCard
                model={LANDING_MODEL}
                scopeLabel="My area"
                scopeIsArea
                activeId="ev-1"
                onSelect={noop}
                onDismiss={noop}
              />
            )}
            {which === 'win' && (
              <MapWindowPanel
                row={EV_ROWS[0]}
                verdict={{ tier: 'GO' }}
                note="Nine regions in your area have a sunset answer for this window."
                rows={PANEL_ROWS}
                scopeIsArea
                onClose={noop}
                onSelectRegion={noop}
              />
            )}
            {which === 'reg' && (
              <MapRegionPanel
                row={EV_ROWS[0]}
                region={PANEL_ROWS[0]}
                locations={LOCATIONS}
                gloss={{
                  headline: 'A clean western horizon',
                  detail: 'Low cloud clears through the afternoon, leaving mid-level canvas for the last hour.',
                }}
                onBack={noop}
                onClose={noop}
                onZoomToRegion={noop}
                onOpenLocationSheet={noop}
              />
            )}
            <AlwaysOnChrome />
            <Chips />
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}

createRoot(document.getElementById('root')).render(<StrictMode><Harness /></StrictMode>);
