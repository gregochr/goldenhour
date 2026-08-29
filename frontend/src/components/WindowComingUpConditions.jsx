import { useState } from 'react';
import PropTypes from 'prop-types';
import { familyOf, occurrenceCountsLine } from '../utils/comingUpConditions.js';

/**
 * The standing-conditions strip (design README §2/§2.1, plan §7 P4) — one row per frequent topic
 * (Coastal tides, Saharan dust, Valley inversions at first ship, D11), each expandable to every
 * occurrence the surprise model scored in the window, with the score it got. Sits between the
 * filter chips and the handoff row — the design of record's own DOM order
 * ({@code Coming Up.html}: header → since-line → chips → conditions → handoff → chronology).
 *
 * <h2>Everything here is presentation over an already-decided payload</h2>
 *
 * <p>{@code ComingUpConditionsBuilder} computed every rate, quant line, peak and occurrence status
 * this component shows. The only client-side work is which colour token a row's swatch uses
 * ({@code familyOf} — a condition has no served {@code family} field, unlike a chronology entry)
 * and the panel's own held-back/in-the-list/inside-Plan counts, both pure arithmetic over served
 * data ({@code utils/comingUpConditions.js}).
 *
 * <h2>Scroll-to-entry does not thread through React state</h2>
 *
 * <p>A promoted occurrence's {@code in the list →} link finds its chronology card by querying
 * {@code [data-entry-id]} directly — the chronology list and this strip are siblings in the same
 * pane, always mounted together, so there is nothing to prop-drill. `scroll-margin-top` on the
 * target (`index.css`) uses `--wf-mast-h`, not `--wf-lens-reserve`: the lens bar is Plan-only and
 * `useLensReserve` removes its variable on this tab, so the lens bar's larger fallback would
 * over-reserve by a bar that is not on screen here.
 *
 * @param {object}   props
 * @param {Array}    props.conditions the served {@code ComingUpCondition[]}, or undefined before
 *                                    the feed has arrived
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there, given a date
 */
export default function WindowComingUpConditions({ conditions, onGoToPlan }) {
  const [openTypes, setOpenTypes] = useState(() => new Set());

  const list = Array.isArray(conditions) ? conditions : [];
  if (list.length === 0) return null;

  const toggle = (type) => {
    setOpenTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type); else next.add(type);
      return next;
    });
  };

  return (
    <div className="wf-cond" data-testid="coming-up-conditions">
      <div className="wf-cond-head">
        <span className="wf-cond-t">Standing conditions</span>
        <span className="wf-cond-d">frequent · never announced · always one click away</span>
      </div>
      {list.map((condition) => {
        const open = openTypes.has(condition.type);
        const accent = `var(--color-topic-${familyOf(condition.type)})`;
        const panelId = `coming-up-condition-panel-${condition.type}`;
        return (
          <div key={condition.type} style={{ '--wf-cond-accent': accent }}>
            <button
              type="button"
              className="wf-cond-row"
              aria-expanded={open}
              aria-controls={panelId}
              onClick={() => toggle(condition.type)}
              data-testid="condition-row"
            >
              <span className="wf-cond-sw" aria-hidden="true" />
              <span className="wf-cond-name" data-testid="condition-name">
                {condition.name}
                {' '}
                <span className="wf-cond-kind" data-testid="condition-cadence">{condition.cadence}</span>
              </span>
              {' '}
              <span className="wf-cond-rate" data-testid="condition-rate">{condition.rateLabel}</span>
              {' '}
              <span className="wf-cond-peak" data-testid="condition-peak">
                {condition.peak ? (
                  <>
                    <span className="wf-cond-peak-label">peak</span>
                    {' '}
                    {condition.peak.dateLabel}
                    {' · '}
                    <b>{condition.peak.valueLabel} · {condition.peak.bits.toFixed(1)} bits</b>
                  </>
                ) : (
                  <span className="wf-cond-peak-label">no gated peak right now</span>
                )}
                <span className="wf-cond-caret" aria-hidden="true">▾</span>
              </span>
              {' '}
              <span className="wf-cond-quant" data-testid="condition-quant">{condition.quantLabel}</span>
            </button>

            {/* BOTH the `hidden` attribute and a display class (index.css): Tailwind v4's
                preflight `[hidden]` rule already carries `!important` in this codebase, so the
                attribute alone hides the panel — the class is defence in depth against a future
                display utility silently re-exposing it, matching WindowFirstShell's own pane
                pattern for the same trap the design bundle documents from the other side. */}
            <div
              id={panelId}
              className={open ? 'wf-cond-occ wf-cond-occ-open' : 'wf-cond-occ'}
              hidden={!open}
              style={{ '--wf-cond-accent': accent }}
              data-testid="condition-panel"
            >
              <span className="wf-cond-oh" data-testid="condition-occurrence-header">
                {occurrenceCountsLine(condition.occurrences)}
              </span>
              {condition.occurrences.map((occurrence, i) => (
                // `occurrence.date` is not guaranteed unique within one condition — two runs (or a
                // historical burst peak and the forward peak) can legitimately land on the same
                // calendar day — so the index breaks the tie. The list is server-ordered and fixed
                // per render, matching this file's existing entries[]/facts key convention.
                <OccurrenceRow
                  key={`${occurrence.date}:${i}`}
                  occurrence={occurrence}
                  onGoToPlan={onGoToPlan}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/**
 * Finds the chronology card matching `entryId` and scrolls it into view with a brief highlight —
 * the invariant this depends on (D11) is that a `promoted` occurrence's `entryId` always resolves
 * to a real, currently-rendered entry.
 */
function scrollToEntry(entryId) {
  const target = document.querySelector(`[data-entry-id="${entryId}"]`);
  if (!target) return;
  target.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
  target.classList.add('wf-cu-ent-highlight');
  window.setTimeout(() => target.classList.remove('wf-cu-ent-highlight'), 1600);
}

/**
 * One occurrence row — date/value/bits/reason/status (design §2.1's exact grid and styling).
 *
 * <p>A {@code promoted}/{@code insidePlan} row renders as a real {@code <button>} wrapping every
 * cell, not just the status text: the 760px breakpoint hides the status/reason TEXT (design §2.1),
 * and if only a bare status span carried the click handler, hiding it would also remove the click
 * target — the exact "stays clickable when its status text is hidden" requirement this avoids
 * breaking. A {@code heldBack} row has nothing to click, so it stays a plain, non-interactive
 * {@code <div>} — the same interactive/inert split {@code WindowComingUpEntry} already uses.
 */
function OccurrenceRow({ occurrence, onGoToPlan }) {
  const statusClass = occurrence.status === 'promoted' ? 'wf-cond-oc-up'
    : occurrence.status === 'insidePlan' ? 'wf-cond-oc-pl' : '';

  // Every cell is separated by a bare `{' '}` text-node sibling — the same fix
  // `WindowFirstComingUpHandoff`/`WindowComingUpEntry` already record: JSX drops whitespace-only
  // text between sibling tags rather than collapsing it to a space, so without this the accessible
  // name of the wrapping button (promoted/insidePlan rows below) would run every cell together
  // with no word boundary.
  const cells = (
    <>
      <span className="wf-cond-od">{occurrence.dateLabel}</span>
      {' '}
      <span className="wf-cond-ov">{occurrence.valueLabel}</span>
      {' '}
      <span className="wf-cond-ob">{occurrence.bits.toFixed(1)} bits</span>
      {' '}
      <span className="wf-cond-om">{occurrence.reason ?? ''}</span>
      {' '}
      {occurrence.status === 'promoted' && (
        <span className="wf-cond-os" data-testid="condition-occurrence-status">in the list →</span>
      )}
      {occurrence.status === 'insidePlan' && (
        <span className="wf-cond-os" data-testid="condition-occurrence-status">
          inside Plan&rsquo;s four days →
        </span>
      )}
      {occurrence.status === 'heldBack' && (
        <span className="wf-cond-os" data-testid="condition-occurrence-status">held back</span>
      )}
    </>
  );

  if (occurrence.status === 'promoted') {
    return (
      <button
        type="button"
        className={`wf-cond-oc ${statusClass}`}
        onClick={() => scrollToEntry(occurrence.entryId)}
        data-testid="condition-occurrence"
        data-status={occurrence.status}
      >
        {cells}
      </button>
    );
  }
  if (occurrence.status === 'insidePlan') {
    return (
      <button
        type="button"
        className={`wf-cond-oc ${statusClass}`}
        onClick={() => onGoToPlan(occurrence.date)}
        data-testid="condition-occurrence"
        data-status={occurrence.status}
      >
        {cells}
      </button>
    );
  }
  return (
    <div className={`wf-cond-oc ${statusClass}`} data-testid="condition-occurrence" data-status={occurrence.status}>
      {cells}
    </div>
  );
}

OccurrenceRow.propTypes = {
  occurrence: PropTypes.shape({
    date: PropTypes.string.isRequired,
    dateLabel: PropTypes.string.isRequired,
    valueLabel: PropTypes.string,
    bits: PropTypes.number.isRequired,
    reason: PropTypes.string,
    status: PropTypes.oneOf(['heldBack', 'promoted', 'insidePlan']).isRequired,
    entryId: PropTypes.string,
  }).isRequired,
  onGoToPlan: PropTypes.func.isRequired,
};

WindowComingUpConditions.propTypes = {
  conditions: PropTypes.arrayOf(PropTypes.shape({
    type: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    cadence: PropTypes.string.isRequired,
    interim: PropTypes.bool.isRequired,
    rateLabel: PropTypes.string.isRequired,
    quantLabel: PropTypes.string.isRequired,
    peak: PropTypes.shape({
      dateLabel: PropTypes.string.isRequired,
      valueLabel: PropTypes.string,
      bits: PropTypes.number.isRequired,
    }),
    occurrences: PropTypes.array.isRequired,
  })),
  onGoToPlan: PropTypes.func.isRequired,
};
