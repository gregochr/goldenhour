import React, { useCallback, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import WindowRowFieldMap from './WindowRowFieldMap.jsx';
import WindowRegionRail from './WindowRegionRail.jsx';
import WindowRegionBand from './WindowRegionBand.jsx';

/**
 * The open row's region layer — field map, region rail, region band — behind one lazy boundary.
 *
 * <h2>It exists to be a chunk, and that is a scope-guard decision rather than an optimisation</h2>
 *
 * <p>{@code WindowFirstWindowCard} is imported statically by {@code WindowFirstShell}, which
 * {@code App} imports statically while {@code usePlanLayout} still defaults to <b>v1</b>. So every
 * module this layer reaches lands in the entry chunk for every reader of an arm they never see —
 * measured at <b>+21.4 KB raw / +7.2 KB gzip</b> with the kernel included, and still +12.1 KB raw /
 * +3.3 KB gzip with only the rail and the band static. P2 put the heat strip behind exactly this
 * boundary for exactly this reason, and the plan's scope guard is explicit that v2 must not change
 * what v1 costs.
 *
 * <p><b>One boundary rather than three</b>, because the three always mount together: three
 * {@code lazy()} calls would be three round trips for one disclosure, and Rollup is under no
 * obligation to merge them into one chunk. A single wrapper makes the chunk a fact rather than a
 * hope.
 *
 * <p>It holds no logic of its own — the rows, the filters and the selection all arrive derived, from
 * the card that owns the state. A grouping component that started deriving would be a fourth place
 * region facts are computed.
 *
 * @param {object}   props
 * @param {object}   props.field       the card's region-layer inputs
 * @param {object}   props.card        the window card descriptor
 * @param {Array}    props.regionRows  ranked rail rows from {@code buildRegionRows}
 * @param {?object}  props.selectedRow the selected region's row, or null
 * @param {string[]} props.filters     the filters in force, already worded
 * @param {string}   props.todayStr    today's ISO date in Europe/London
 */
export default function WindowRowRegionLayer({
  field, card, regionRows, selectedRow, filters, todayStr,
}) {
  // Memoised because it is a dependency of the map's `paint` callback, which is a dependency of the
  // paint effect: an inline `.map()` here makes a fresh array on every render and repaints the
  // canvas for nothing. `regionRows` is itself memoised in the card, so this is stable whenever the
  // payload and the lens are.
  const regionNames = useMemo(() => regionRows.map((row) => row.name), [regionRows]);

  /**
   * Clearing the selection destroys the control that did it, so focus has to be put somewhere.
   *
   * <p>{@code Show all regions ×} lives inside the band, and clearing unmounts the band in the same
   * commit — focus falls to {@code <body>} and the next Tab restarts at the top of the document,
   * past the masthead, the lens bar, the six thumbnails and every card above this one. WCAG 2.4.3,
   * and the classic self-destroying-control shape: a screen-reader user's only evidence the button
   * worked is silence.
   *
   * <p>Focus goes to the rail's All cell, which is the labelled peer that now reads
   * {@code aria-pressed="true"} — so the announcement ("All 5 regions, Showing everything…, pressed")
   * both confirms the action and says what state the row is in. Moved after the state change, in the
   * same handler, because the All cell is already mounted and does not move.
   */
  const allCellRef = useRef(null);

  /**
   * The regions the map may offer, which is one of them under an away origin.
   *
   * <p>Memoised because {@code WindowRowFieldMap}'s paint effect depends on it — a fresh array per
   * render would repaint a coastline, a kernel field, a blur and a stroke on every shell render
   * (§5 invariant 4).
   */
  const scopedRegionNames = useMemo(
    () => (field.singleRegionScope && field.origin
      ? regionNames.filter((name) => name === field.origin.name)
      : regionNames),
    [regionNames, field.singleRegionScope, field.origin],
  );
  const clearRegion = useCallback(() => {
    field.onSelectRegion?.(null);
    allCellRef.current?.focus?.();
  }, [field]);

  return (
    <>
      <WindowRowFieldMap
        windowKey={card.key}
        date={card.date}
        confidence={card.confidence}
        spots={field.spots}
        points={field.points}
        // The same field the rail below takes as `windowBest`, so the map's unscored mark and the
        // All cell's star can never disagree about whether this window was rated.
        bestRating={card.bestRating}
        // Rank order, so the labels and the rail name one set — a region on the map that is not on
        // the rail could be selected and then not be clearable from the rail.
        // ⚠️ Scoped to the origin's own region when the page is. The canvas is `aria-hidden` and
        // its `selectable` flag is `regionNames.length > 1`, so leaving the full roster here would
        // keep click-to-select live on a surface whose ONLY accessible equivalent — the rail — this
        // scope has just withdrawn. One name makes it unselectable, which restores the condition
        // this component's own comment sets for an `aria-hidden` surface: never the sole path to
        // anything. The labels still draw, so the region is still named.
        regionNames={scopedRegionNames}
        selectedRegion={field.selectedRegion || null}
        reachById={field.reachById}
        origin={field.origin || null}
        todayStr={todayStr}
        onSelectRegion={field.onSelectRegion}
      />

      {/* ⚠️ The rail drops away when the origin is a single region (plan §4.8): there is nothing
          left to choose. It is not merely redundant — the rail's own "All N regions" peer cell and
          its per-region counts would describe a one-region set as though it were a choice, and its
          selection would then filter a scope that is already exactly that region. The map above
          keeps its labels, so the region is still named on screen. */}
      {!field.singleRegionScope && (
      <WindowRegionRail
        allCellRef={allCellRef}
        rows={regionRows}
        // The WINDOW's served best, which is the same field the header prints as `best spot N★` —
        // see `windowFirstRegions.js` on why the two levels stay in two labelled cells and never
        // collapse into one number.
        windowBest={card.bestRating}
        drawnCount={card.spots.length}
        countNoun={card.withinReachCount != null ? 'in reach' : 'spots'}
        selected={field.selectedRegion || null}
        onSelect={field.onSelectRegion}
      />
      )}

      {selectedRow && (
        <WindowRegionBand
          row={selectedRow}
          windowKey={card.key}
          windows={field.windows}
          series={field.series?.get?.(selectedRow.name)}
          filters={filters}
          atFloor={field.lens?.minRating != null && field.lens?.ratingLabel
            ? {
              label: field.lens.ratingLabel,
              count: selectedRow.atFloorCount,
              total: selectedRow.totalCount,
            }
            : null}
          // Gated on `reachFigureHolds`, not merely on the tier being set — the tier gates nothing a
          // reader can trust when no drive was measured, and the figure would then assert a distance
          // for spots nobody has one for. Same rule as the rail cell's count noun, from the same
          // flag, so the two cannot disagree.
          withinTier={selectedRow.reachFigureHolds && field.lens?.tierLabel
            ? { label: field.lens.tierLabel.toLowerCase(), count: selectedRow.drawnCount }
            : null}
          onClear={clearRegion}
          onJumpWindow={field.onJumpWindow}
        />
      )}
    </>
  );
}

WindowRowRegionLayer.propTypes = {
  field: PropTypes.shape({
    spots: PropTypes.array.isRequired,
    points: PropTypes.array.isRequired,
    windows: PropTypes.array.isRequired,
    series: PropTypes.instanceOf(Map),
    reachById: PropTypes.instanceOf(Map),
    /** True when the origin has already narrowed the page to one region — the rail is withheld. */
    singleRegionScope: PropTypes.bool,
    /** The away origin, or null for home. Framing only — it never reaches the kernel's points. */
    origin: PropTypes.shape({ name: PropTypes.string.isRequired }),
    selectedRegion: PropTypes.string,
    lens: PropTypes.object,
    onSelectRegion: PropTypes.func,
    onJumpWindow: PropTypes.func,
  }).isRequired,
  card: PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    confidence: PropTypes.string,
    bestRating: PropTypes.number,
    spots: PropTypes.array.isRequired,
    withinReachCount: PropTypes.number,
  }).isRequired,
  regionRows: PropTypes.array.isRequired,
  selectedRow: PropTypes.object,
  filters: PropTypes.arrayOf(PropTypes.string).isRequired,
  todayStr: PropTypes.string.isRequired,
};
