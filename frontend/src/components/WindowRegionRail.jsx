import React, { useId } from 'react';
import PropTypes from 'prop-types';

/**
 * The open row's region rail — this window by region, ranked, and the filter that follows from it.
 *
 * <h2>The All cell is a peer, not a reset button</h2>
 *
 * <p>It sits first in the same grid, in the same shape, and carries the same two facts every region
 * cell does — because "everything" is one of the choices, not an escape from having made one. A
 * separate "clear" control beside a rail of regions would state the same thing twice (the band
 * already carries {@code Show all regions ×} for a reader who has scrolled past the rail) and would
 * read as chrome rather than as the top of a list.
 *
 * <h2>Every star here is served</h2>
 *
 * <p>The All cell prints the WINDOW's {@code bestRating} — the same field, from the same payload,
 * that the card header two elements up prints as {@code best spot N★} — and each region cell prints
 * its OWN {@code bestRating}. Those are two different populations (P1: the canopy fallback is per
 * window on one and per region on the other), which is exactly why they are in two labelled cells
 * and never in one number. Neither is computed here; see {@code windowFirstRegions.js}.
 *
 * <h2>What a cell with nothing drawn says</h2>
 *
 * <p>Its distance, and only when reach is what emptied it — {@code buildRegionRows} decides that,
 * because a region emptied by the rating floor is one you can drive to and naming a drive over it
 * would point at the wrong control. A cell that is empty for neither reason says nothing rather
 * than printing a zero: "0 spots" is a fact about two controls the reader set, not about the region.
 *
 * @param {object}   props
 * @param {Array}    props.rows        rail rows from {@code buildRegionRows}, ranked
 * @param {?number}  props.windowBest  the window's served {@code bestRating}, for the All cell
 * @param {number}   props.drawnCount  how many spots the window draws in total
 * @param {string}   props.countNoun   the word the All cell's count may use
 * <h2>Each cell's name is one hidden sentence, not its visible spans</h2>
 *
 * <p>⚠️ Name-from-contents does not survive a browser here, and P2 paid for the lesson on the heat
 * strip: {@code .wf-rr} is a flex container, and CSS Flexbox §4 makes a contiguous child text run of
 * only white space <em>unrendered</em> — so AccName joins the spans with nothing between them and a
 * screen reader announces "Northumberland &amp; TynesideWorth itbest 5★ · 12 in reach". jsdom, with
 * {@code css: false}, leaves every span {@code display: inline} and inserts the spaces, so the test
 * passes and the product does not. The sentence below is real text built from the same fields in
 * the same render, so it cannot drift from the visible words, and WCAG 2.5.3 holds because it
 * contains every one of them verbatim.
 *
 * @param {?string}  props.selected    the selected region, or null for All
 * @param {Function} [props.onSelect]  called with a region name, or null for All
 * @param {object}   [props.allCellRef] attached to the All cell, so a control that clears the
 *        selection and unmounts itself has somewhere to put focus — see {@code WindowRowRegionLayer}
 */
export default function WindowRegionRail({
  rows, windowBest, drawnCount, countNoun, selected, onSelect, allCellRef,
}) {
  // Unique per rail, so the several open rows a reader can have at once cannot collide on an id —
  // `aria-labelledby` resolves document-wide and a duplicate would name one cell for two buttons.
  const nameId = useId();
  // Nothing to choose between — see WindowRowFieldMap's own note. Normally a P7 origin case.
  if (rows.length < 2) return null;

  return (
    <>
      <p data-testid="wf-region-rail-label" className="wf-rlab">
        This window by region · ranked
        {/* Dropped on a phone by media query: the instruction is the widest clause here and the
            cells are self-evidently buttons on a surface where everything is tapped. */}
        <span className="wf-rlab-tail"> · select one to filter the spots below</span>
      </p>
      <div data-testid="wf-region-rail" className="wf-rrail">
        <button
          type="button"
          ref={allCellRef}
          data-testid="wf-region-cell"
          data-region="all"
          data-selected={selected ? undefined : 'true'}
          // `aria-pressed` rather than `aria-current`: this is a filter toggle, and exactly one of
          // the cells is on at a time. A reader arriving on the rail hears which one without having
          // to resolve the gold tint that is the only visual signal.
          aria-pressed={!selected}
          className={`wf-rr wf-rr-all${selected ? '' : ' on'}`}
          aria-labelledby={`${nameId}-all`}
          onClick={() => onSelect?.(null)}
        >
          <span id={`${nameId}-all`} className="sr-only">
            {[`All ${rows.length} regions`, selected ? 'Show everything' : 'Showing everything',
              metaLine(windowBest, drawnCount, countNoun, null)].filter(Boolean).join(', ')}
          </span>
          <span className="wf-rr-n" aria-hidden="true">{`All ${rows.length} regions`}</span>
          <span className="wf-rr-v" aria-hidden="true">
            {selected ? 'Show everything' : 'Showing everything'}
          </span>
          <span className="wf-rr-m" aria-hidden="true">
            {metaLine(windowBest, drawnCount, countNoun, null)}
          </span>
        </button>
        {rows.map((row, index) => {
          const meta = metaLine(row.bestRating, row.drawnCount, row.countNoun, row.awayLabel);
          const cellId = `${nameId}-${index}`;
          return (
            <button
              key={row.name}
              type="button"
              data-testid="wf-region-cell"
              data-region={row.name}
              data-verdict={row.verdict}
              data-selected={row.name === selected ? 'true' : undefined}
              aria-pressed={row.name === selected}
              className={`wf-rr${row.name === selected ? ' on' : ''}`}
              aria-labelledby={cellId}
              onClick={() => onSelect?.(row.name === selected ? null : row.name)}
            >
              <span id={cellId} className="sr-only">
                {[row.name, row.verdictLabel, meta].filter(Boolean).join(', ')}
              </span>
              <span className="wf-rr-n" aria-hidden="true">{row.name}</span>
              <span className="wf-rr-v" aria-hidden="true">{row.verdictLabel}</span>
              <span className="wf-rr-m" aria-hidden="true">{meta}</span>
            </button>
          );
        })}
      </div>
    </>
  );
}

/**
 * A cell's second line: the served best, then either what this window draws there or how far it is.
 *
 * <p>Built as one string rather than as spans so the button's accessible name reads as a sentence —
 * "Northumberland & Tyneside, Worth it, best 5★ · 12 in reach" — rather than as three fragments a
 * screen reader runs together without pause. The rail is the accessible equivalent of the map above
 * it, so its names are the whole of what a non-pointer reader gets.
 *
 * @param {?number} best      the served best rating, or null when nothing there is rated
 * @param {number}  count     how many spots this window draws
 * @param {string}  noun      the word the count may use
 * @param {?string} awayLabel the distance clause, when reach is what emptied the cell
 * @returns {string} the line, which may be empty when there is nothing true to say
 */
function metaLine(best, count, noun, awayLabel) {
  const clauses = [];
  // Omitted rather than shown as a placeholder: a null best means nothing there is rated, which is
  // a different statement from a low one — the window header's own rule.
  if (best != null) clauses.push(`best ${best}★`);
  if (count > 0) clauses.push(`${count} ${noun}`);
  else if (awayLabel) clauses.push(awayLabel);
  return clauses.join(' · ');
}

WindowRegionRail.propTypes = {
  rows: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    verdict: PropTypes.string,
    verdictLabel: PropTypes.string,
    bestRating: PropTypes.number,
    drawnCount: PropTypes.number.isRequired,
    countNoun: PropTypes.string.isRequired,
    awayLabel: PropTypes.string,
  })).isRequired,
  windowBest: PropTypes.number,
  drawnCount: PropTypes.number.isRequired,
  countNoun: PropTypes.string.isRequired,
  selected: PropTypes.string,
  onSelect: PropTypes.func,
  /**
   * Attached to the All cell. A plain prop rather than a forwarded ref, because the caller needs it
   * only to MOVE FOCUS here and a named prop says that at the call site.
   */
  allCellRef: PropTypes.shape({ current: PropTypes.any }),
};
