import React from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../utils/scoreRamp.js';
import { movementChip } from '../utils/movement.js';

/**
 * The open row's region drill-down band — what one region says about this window.
 *
 * <h2>The narrative is the payload's own sentence, verbatim</h2>
 *
 * <p>{@code BriefingRegion.summary} is Claude's prose about that region on that night, and it is
 * rendered exactly as served — no truncation, no first-sentence extraction, no re-flow. It is the
 * one thing on this screen that explains <em>why</em>, and every surface that has tried to shorten
 * one of these has ended up asserting something the model did not.
 *
 * <h2>The null path is a named state, never blankness</h2>
 *
 * <p>An AWAITING region can legitimately carry no summary, and the design specifies the line for it
 * rather than leaving the band's second row empty: an empty row reads as a rendering failure, and a
 * reader cannot tell it apart from prose that failed to load. When a <em>different</em> window is
 * that region's own best, the line says so — which turns "nothing to say about tonight" into a
 * route to the night there is something to say about.
 *
 * <h2>The figures state active controls only</h2>
 *
 * <p>{@code best in field} is the served {@code bestRating} and is unconditional. The other two are
 * lens output, and each renders only while its axis actually gates something: "at any rating,
 * 12 of 12" is a figure about a control that filtered nothing, which is the class of number §6's
 * sweep removed from four other surfaces on this arm.
 *
 * <p>The movement figure joins {@code best in field} on the served side and is unconditional in the
 * same way — but only where the payload carries one. It is <b>this</b> region's own delta, which is
 * a different number from the strip's chip for the same window whenever the reader has drilled into
 * a region that is not the window's leader; that is the point of drilling in, and the two are
 * labelled at their own grains so neither reads as the other.
 *
 * @param {object}   props
 * @param {object}   props.row        the selected region's rail row
 * @param {string}   props.windowKey  the open window's key, so the dots can mark "you are here"
 * @param {Array}    props.windows    the strip's thumbnail descriptors, in chronological order
 * @param {Map}      props.series     window key → this region's served mean, from
 *                                    {@code buildRegionSeries}
 * @param {string[]} props.filters    the filters in force, already worded
 * @param {?object}  props.atFloor    {@code {label, count, total}} for the rating figure, or null
 * @param {?object}  props.withinTier {@code {label, count}} for the reach figure, or null
 * @param {Function} [props.onClear]  clears the region selection
 * @param {Function} [props.onJumpWindow] opens and reveals another window's card
 */
export default function WindowRegionBand({
  row, windowKey, windows, series, filters, atFloor, withinTier, onClear, onJumpWindow,
}) {
  // Chronological, as the strip is — the dots are a time axis and the rail is the ranked one.
  const dots = windows.map((window) => ({
    key: window.key,
    dow: window.dow,
    sunrise: window.sunrise,
    // A travel day has no card to open — `buildWindowCards` builds none — so `revealWindow` would
    // find no element, scroll nothing and focus nothing. P2 made the strip's away thumbnail a
    // `<div>` for exactly this reason (§6 bans a control with no visible effect); the dot inherits
    // that treatment rather than being a sixth button that silently does nothing.
    away: Boolean(window.away),
    mean: series?.get?.(window.key) ?? null,
  }));
  // This region's own movement in the open window, from the served delta on its rail row.
  const chip = movementChip(row.meanRatingDelta);
  const bestKey = bestWindowKey(dots);
  const bestWindow = bestKey == null ? null : windows.find((w) => w.key === bestKey);

  return (
    <div data-testid="wf-region-band" data-verdict={row.verdict} className="wf-rband">
      <div className="wf-rband-hdr">
        <span data-testid="wf-region-band-name" className="wf-rband-n">{row.name}</span>
        {filters.length > 0 && (
          <span data-testid="wf-region-band-filters" className="wf-rband-k">
            {`Spots below · ${filters.join(' · ')}`}
          </span>
        )}
        <button
          type="button"
          data-testid="wf-region-band-clear"
          className="wf-rband-x"
          onClick={() => onClear?.()}
        >
          Show all regions
          <span aria-hidden="true"> ×</span>
        </button>
      </div>

      {row.summary ? (
        <p data-testid="wf-region-band-narrative" className="wf-rband-narr">{row.summary}</p>
      ) : (
        <p data-testid="wf-region-band-none" className="wf-rband-none">
          No narrative generated for this window.
          {/* Only when the best window is a DIFFERENT one: on the window the reader already has
              open, "its own best is tonight" would be a sentence pointing at itself. */}
          {bestWindow && bestWindow.key !== windowKey && (
            <>
              {' This region’s own best is '}
              <b>{`${bestWindow.label.toLowerCase()} ${bestWindow.time}`.trim()}</b>
              .
            </>
          )}
        </p>
      )}

      <div data-testid="wf-region-band-figures" className="wf-rband-figs">
        {/* Served, and the only figure here that is not lens output. See windowFirstRegions.js for
            why no surface re-derives it. */}
        {row.bestRating != null && (
          <span data-testid="wf-region-band-fig" data-fig="best" className="wf-rband-fig">
            best in field
            <b>{`${row.bestRating}★`}</b>
          </span>
        )}
        {/* Beside `best in field` because both are served facts about this region rather than lens
            output — and after it, because the star is what the reader came for and the movement
            qualifies it. Rendered only where a delta exists: a null is silence, never a `—`. */}
        {chip && (
          <span data-testid="wf-region-band-fig" data-fig="moved" className="wf-rband-fig">
            {/* "at last run", never "since": the delta is measured from the build BEFORE the last
                one, so "since" names the interval in which almost none of the movement happened —
                the change line's own note carries the full reasoning. */}
            at last run
            {/* The glyph is the whole of the visible value, so it is hidden and the word beside it
                carries the meaning — the same treatment the region dots give their ↑/↓. */}
            <b data-testid="wf-region-band-mark" data-tone={chip.tone} aria-hidden="true">
              {chip.mark}
            </b>
            <span className="sr-only">{chip.shortSpoken}</span>
          </span>
        )}
        {atFloor && (
          <span data-testid="wf-region-band-fig" data-fig="rating" className="wf-rband-fig">
            {`at ${atFloor.label}`}
            <b>{`${atFloor.count} of ${atFloor.total}`}</b>
          </span>
        )}
        {withinTier && (
          <span data-testid="wf-region-band-fig" data-fig="reach" className="wf-rband-fig">
            {`within ${withinTier.label}`}
            <b>{withinTier.count}</b>
          </span>
        )}
      </div>

      <div className="wf-rband-right">
        <span className="wf-rband-lab">Its six windows</span>
        <div data-testid="wf-region-band-dots" className="wf-rdots">
          {dots.map((dot) => {
            const event = dot.sunrise ? 'sunrise' : 'sunset';
            const score = dot.mean == null ? 'not scored' : `${dot.mean.toFixed(1)}★`;
            const body = (
              <>
                <span className="wf-rdot-d" aria-hidden="true">
                  {`${dot.dow}${dot.sunrise ? '↑' : '↓'}`}
                </span>
                {/* An unscored window gets no swatch rather than the ramp's bottom colour: the
                    kernel's "non-finite resolves to the bottom" rule is about a FIELD that must
                    paint something, and here a dark-red bar would say "poor" about a window nobody
                    has looked at. */}
                <span
                  data-testid="wf-region-band-swatch"
                  className="wf-rdot-sw"
                  aria-hidden="true"
                  data-unscored={dot.mean == null ? 'true' : undefined}
                  style={dot.mean == null ? undefined : { background: rampHex(dot.mean) }}
                />
                {dot.key === bestKey && (
                  <span className="wf-rdot-pk" aria-hidden="true">◎</span>
                )}
              </>
            );
            if (dot.away) {
              return (
                <span
                  key={dot.key}
                  data-testid="wf-region-band-dot"
                  data-window={dot.key}
                  data-away="true"
                  className="wf-rdot wf-rdot-away"
                >
                  <span className="sr-only">{`${dot.dow} ${event}, not forecast`}</span>
                  {body}
                </span>
              );
            }
            return (
              <button
                key={dot.key}
                type="button"
                data-testid="wf-region-band-dot"
                data-window={dot.key}
                data-current={dot.key === windowKey ? 'true' : undefined}
                // The visible text is the weekday abbreviation and a direction glyph, so the name
                // opens with that abbreviation verbatim (WCAG 2.5.3) and spells the glyph out. The
                // region is named because six otherwise identical dots are on screen inside a band
                // whose heading is several elements away.
                aria-label={`${dot.dow} ${event}, ${row.name} ${score}`}
                aria-current={dot.key === windowKey ? 'true' : undefined}
                className={`wf-rdot${dot.key === windowKey ? ' on' : ''}`}
                onClick={() => onJumpWindow?.(dot.key)}
              >
                {body}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/**
 * The window this region does best in, or null when it is scored in none of them.
 *
 * <p>Ties go to the EARLIER window, which is the chronological order the dots are already drawn in —
 * so the ◎ never appears to jump backwards past an equal sibling. Null rather than the first window
 * when nothing is scored: a mark on an unscored window would recommend a night nobody has evaluated.
 *
 * @param {Array<{key: string, mean: ?number}>} dots the dots, chronological
 * @returns {?string} the best window's key
 */
function bestWindowKey(dots) {
  let bestKey = null;
  let best = null;
  for (const dot of dots) {
    // An away day is never the peak, and never the window the null-summary line points at: the
    // pipeline skips evaluation there, so a mark on it would recommend a night nobody looked at.
    if (dot.mean == null || dot.away) continue;
    if (best === null || dot.mean > best) {
      best = dot.mean;
      bestKey = dot.key;
    }
  }
  return bestKey;
}

WindowRegionBand.propTypes = {
  row: PropTypes.shape({
    name: PropTypes.string.isRequired,
    verdict: PropTypes.string,
    summary: PropTypes.string,
    bestRating: PropTypes.number,
    meanRatingDelta: PropTypes.number,
  }).isRequired,
  windowKey: PropTypes.string.isRequired,
  windows: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    dow: PropTypes.string,
    sunrise: PropTypes.bool,
    away: PropTypes.bool,
    label: PropTypes.string,
    time: PropTypes.string,
  })).isRequired,
  series: PropTypes.instanceOf(Map),
  filters: PropTypes.arrayOf(PropTypes.string).isRequired,
  atFloor: PropTypes.shape({
    label: PropTypes.string.isRequired,
    count: PropTypes.number.isRequired,
    total: PropTypes.number.isRequired,
  }),
  withinTier: PropTypes.shape({
    label: PropTypes.string.isRequired,
    count: PropTypes.number.isRequired,
  }),
  onClear: PropTypes.func,
  onJumpWindow: PropTypes.func,
};
