import React, { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import DateStrip from './DateStrip.jsx';
import MapView from './MapView.jsx';

/**
 * The window-first arm's Map tab: the date strip and the full map, in the shell's slotted panel.
 *
 * <h2>The tab is the cheap half; the pane is the work</h2>
 *
 * <p>P15a's slot mechanism already grows a Map tab the moment {@code App} hands the shell a
 * {@code mapPane}, and nothing about the tab bar changes here. What this component exists to settle
 * is the two things a slotted map needs that a slotted {@code ManageView} did not.
 *
 * <h2>It keeps its own date strip, over a different horizon from the rail</h2>
 *
 * <p>The rail's domain is up to six briefing events; this strip's is every date
 * {@code GET /api/forecast} returned. Different endpoints, different horizons, and the map's is the
 * longer one. Dropping the strip and following the rail would have stranded the tab on whichever
 * date the Plan pane happened to be showing — a capability the v1 Map tab has and this arm would
 * have quietly lost, which is the kind of regression a side-by-side pilot exists to surface.
 *
 * <p>The selection itself is <b>not</b> owned here. It is {@code App}'s existing
 * {@code selectedDate}, the same state the v1 Map tab drives, so the two arms cannot disagree about
 * which day the map is showing and there is no second source of truth to keep in step when the flag
 * flips.
 *
 * <h2>Leaflet has to be told the panel came back</h2>
 *
 * <p>The shell hides a deselected panel with {@code display: none} rather than unmounting it — that
 * is deliberate (P15a), because unmounting would refire the pane's fetches on every visit. But
 * Leaflet caches its container's size and recomputes only on {@code invalidateSize}, so a viewport
 * change while the reader is on another tab leaves it holding a size the container no longer has,
 * and the map paints grey on return. <b>A phone rotating is the ordinary case</b>, not a corner one.
 *
 * <p>A {@code ResizeObserver} rather than a reveal callback from the shell: the shell would have to
 * learn that one of its panes cares about being shown, and it would still miss a resize that happens
 * while the panel is visible. Observing the box answers both, and it answers the first paint too —
 * {@code display: none} reports a zero box, so the transition to a real one fires the observer
 * without anyone having to notice the tab changed.
 *
 * @param {object}   props
 * @param {Array}    props.locations       the enabled locations the map draws
 * @param {string[]} props.dates           every date the forecast endpoint returned
 * @param {string}   props.selectedDate    the date the map is showing
 * @param {Function} props.onSelectDate    hands a new date back to {@code App}
 * @param {object}   [props.handoff]       an in-flight map handoff (event type, region, nonce…)
 * @param {Map}      [props.briefingScores]
 * @param {Function} [props.onForecastRun]
 * @param {Array}    [props.seasonalFeatures]
 * @param {object}   [props.homeCoords]
 * @param {number}   [props.homeRadiusMiles]
 * @param {Function} [props.onOpenSettings]
 */
export default function WindowFirstMapPane({
  locations, dates, selectedDate, onSelectDate, handoff = null, briefingScores = new Map(),
  onForecastRun = null, seasonalFeatures = [], homeCoords = null, homeRadiusMiles = null,
  onOpenSettings = null,
}) {
  const wrapRef = useRef(null);
  const [resizeNonce, setResizeNonce] = useState(0);

  useEffect(() => {
    const el = wrapRef.current;
    // Not every environment has one — jsdom does not — and the map is simply left as it was there,
    // which is the same behaviour it had before this pane existed.
    if (!el || typeof ResizeObserver === 'undefined') return undefined;
    // `invalidateSize` does not change the container's own box, so this cannot feed itself.
    const ro = new ResizeObserver(() => setResizeNonce((n) => n + 1));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <div ref={wrapRef} data-testid="window-first-map-pane" className="flex flex-col" style={{ gap: '10px' }}>
      {/* Guarded because `DateStrip` requires a selected date and the shell mounts this pane the
          moment the tab is first opened. `App` already withholds the whole pane when there are no
          dates at all, so this covers only the gap between having dates and having resolved one. */}
      {selectedDate && (
        <DateStrip dates={dates} selectedDate={selectedDate} onSelect={onSelectDate} />
      )}
      <MapView
        locations={locations}
        date={selectedDate}
        autoEventType={null}
        handoffEventType={handoff?.eventType ?? null}
        handoffFilterAction={handoff?.filterAction ?? null}
        handoffLocationName={handoff?.locationName ?? null}
        handoffRegion={handoff?.region ?? null}
        handoffNonce={handoff?.nonce ?? null}
        briefingScores={briefingScores}
        onForecastRun={onForecastRun}
        seasonalFeatures={seasonalFeatures}
        homeCoords={homeCoords}
        homeRadiusMiles={homeRadiusMiles}
        onOpenSettings={onOpenSettings}
        resizeNonce={resizeNonce}
      />
    </div>
  );
}

WindowFirstMapPane.propTypes = {
  locations: PropTypes.array,
  dates: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedDate: PropTypes.string,
  onSelectDate: PropTypes.func.isRequired,
  handoff: PropTypes.object,
  briefingScores: PropTypes.instanceOf(Map),
  onForecastRun: PropTypes.func,
  seasonalFeatures: PropTypes.array,
  homeCoords: PropTypes.object,
  homeRadiusMiles: PropTypes.number,
  onOpenSettings: PropTypes.func,
};
