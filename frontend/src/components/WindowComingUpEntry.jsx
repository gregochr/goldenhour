import React from 'react';
import PropTypes from 'prop-types';

/**
 * One chronology entry: the date rail beside its card (design README §4, plan §6). Everything the
 * card shows is decided in {@code utils/comingUpFeed.js}'s {@code buildEntryView}; this component
 * only places it.
 *
 * <h2>The card must not be a dead pointer (plan §11.5)</h2>
 *
 * <p>The design gives every card {@code cursor:pointer} and a hover tint, promising a click does
 * something. Only a {@code plan} action is wired to a real destination in this phase — the
 * {@code coastal-spots}/{@code dark-sky-spots} map channel is P3b's (D8), not built yet. So a
 * {@code plan}-action entry renders as a real {@code <button>}: native keyboard operation.
 * Everything else renders as a plain, non-interactive {@code <div>} — no pointer cursor, no hover
 * tint, no button role — so the inert state is honest rather than a promise the tab cannot keep
 * yet. The click handler dispatches on {@code entry.action.kind} itself, not merely on
 * {@code interactive}: when P3b widens the map channel it adds a branch here, and a
 * {@code coastal-spots} entry can never silently fall through to {@code onGoToPlan} because
 * {@code interactive} happened to be widened first (P3a phase-log row records this seam).
 *
 * <h2>No {@code aria-label} on the button — a corrected first attempt</h2>
 *
 * <p>An earlier draft set {@code aria-label={entry.action.label}} so a screen-reader user got the
 * concise destination rather than the whole card read back as one run. That was wrong: `button` is
 * an ARIA role with {@code childrenPresentational: true}, so once ANY accessible name is computed
 * for it — whether from content or from an explicit override — every descendant's own text is
 * folded into that one name and nothing else is exposed. An explicit {@code aria-label} does not
 * just set the name; on this role it also throws away the title, the facts, the prose and the
 * threshold line, which is why a screen-reader user got a one-line reading of "See the plan for 12
 * Sept →" for a full feature card and nothing else — the richer content ended up reachable only on
 * the entries that are NOT clickable. Leaving the name to compute from content is the fix (the same
 * approach {@code WindowFirstComingUpHandoff} already uses for its own button), which is why the
 * title-row spans below are interleaved with bare {@code {' '}} text-node siblings: JSX drops
 * whitespace-only text between tags rather than collapsing it to a space, so without an explicit
 * one the computed name runs every phrase together with no word boundary — the exact defect the
 * handoff row's own fix already records.
 *
 * <h2>What this phase does not draw</h2>
 *
 * <p>{@code entry.coincidence}/{@code entry.joinNote} (the two-topic card, D10) and
 * {@code entry.tide} (the sparkline, design README's "tide sparkline" section) are both on the wire
 * today but neither has a renderer here — both are named P3b work in plan §6b. A merged entry still
 * renders correctly as a plain card on its WINNING topic's own facts; it just does not yet show the
 * second topic's line or the joining sentence. Recorded in the P3a phase-log row so P3b knows this
 * gap is expected, not a regression.
 *
 * <p>{@code entry.scoreNote} is likewise not rendered here. Plan §13 annotates it "since-line +
 * card read it", but §6's own card inventory for this phase never lists it, and the design bundle
 * has no visual slot for a sentence separate from the threshold line — inventing one unreviewed
 * would be exactly the mistake P2 refused to make for the lone-tide-run threshold gap (§11.21).
 * Left for whichever phase builds the since-line (P5) to place, recorded in the phase log rather
 * than silently dropped.
 *
 * @param {object}   props
 * @param {object}   props.entry      a view from {@code buildEntryView}
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there, given a date
 */
export default function WindowComingUpEntry({ entry, onGoToPlan }) {
  const { rail } = entry;
  const cardClassName = [
    'wf-cu-card',
    entry.isFeature ? 'wf-cu-card-feat' : null,
    entry.isForecast ? 'wf-cu-card-fc' : null,
    entry.interactive ? null : 'wf-cu-card-inert',
  ].filter(Boolean).join(' ');

  /**
   * The one destination a card's action can carry. Switches on the SERVED kind, not on
   * `interactive` — P3b adds a `coastal-spots`/`dark-sky-spots` branch here when the map channel
   * exists, rather than only widening `interactive` and leaving the dispatch to fall through to
   * `onGoToPlan` by default.
   */
  const handleClick = () => {
    if (entry.action.kind === 'plan') onGoToPlan(entry.action.date);
  };

  // Every top-level section is separated by a bare `{' '}` text-node sibling, not by relying on
  // `display: block`/flex `gap` to imply one — the accessible-name algorithm reads the DOM, not
  // rendered layout, and JSX drops whitespace-only text between tags rather than collapsing it to
  // a space. Two adjacent sections with nothing rendered between them (e.g. the kind tag directly
  // followed by the action link, on an entry with no superlative, metric, prose or facts) would
  // otherwise glue into one word in the computed name — the same defect the handoff row's own fix
  // already records, here at the scale of a whole card rather than one row.
  const cardBody = (
    <>
      <div className="wf-cu-ttl">
        <span className="wf-cu-nm" data-testid="coming-up-title">{entry.title}</span>
        {' '}
        {/* NEW-flag slot reserved for P5 (plan §6) — goes here, between the name and the kind
            tag, matching the design's title-row order. Not built in this phase. */}
        <span className="wf-cu-kindtag" data-testid="coming-up-kindtag">{entry.kindTag}</span>
        {entry.superlative && (
          <>
            {' '}
            <span className="wf-cu-superlative" data-testid="coming-up-superlative">
              {entry.superlative}
            </span>
          </>
        )}
        {entry.metric && (
          <>
            {' '}
            <span className="wf-cu-metric" data-testid="coming-up-metric">{entry.metric}</span>
          </>
        )}
      </div>
      {' '}

      {entry.prose && (
        <>
          <span className="wf-cu-prose" data-testid="coming-up-prose">{entry.prose}</span>
          {' '}
        </>
      )}

      {entry.facts.length > 0 && (
        <>
          <div className="wf-facts" data-testid="coming-up-facts">
            {entry.facts.flatMap((fact, i) => [
              i > 0 ? ' ' : null,
              // Index-keyed: the list is server-ordered and fixed per render, so position is the
              // stable identity — the same reasoning WindowComingUpRow used before it.
              <span key={`${entry.id}:fact:${i}`} data-testid="coming-up-fact">
                {fact.segments.flatMap((segment, j) => [
                  j > 0 ? ' ' : null,
                  <span key={`${j}:${segment.text}`} data-tone={segment.tone}>{segment.text}</span>,
                ])}
              </span>,
            ])}
          </div>
          {' '}
        </>
      )}

      {entry.threshold && (
        <>
          <span className="wf-cu-threshold" data-testid="coming-up-threshold">
            {entry.threshold}
          </span>
          {' '}
        </>
      )}

      <span className="wf-cu-action" data-testid="coming-up-action">{entry.action.label}</span>
    </>
  );

  return (
    <div className="wf-cu-ent" role="listitem" data-testid="coming-up-entry" data-type={entry.type}>
      <div className="wf-cu-rail" data-testid="coming-up-rail">
        <span className="wf-cu-dbox">
          {rail.dow && <span className="wf-cu-dow">{rail.dow}</span>}
          <span className={rail.isRange ? 'wf-cu-dn wf-cu-dn-run' : 'wf-cu-dn'}>{rail.day}</span>
          <span className="wf-cu-mo">{rail.month}</span>
        </span>
        {rail.countdown && (
          <span className="wf-cu-cd" data-testid="coming-up-countdown">{rail.countdown}</span>
        )}
      </div>

      {entry.interactive ? (
        <button
          type="button"
          className={cardClassName}
          data-family={entry.family}
          onClick={handleClick}
          data-testid="coming-up-card"
        >
          {cardBody}
        </button>
      ) : (
        <div className={cardClassName} data-family={entry.family} data-testid="coming-up-card">
          {cardBody}
        </div>
      )}
    </div>
  );
}

WindowComingUpEntry.propTypes = {
  entry: PropTypes.shape({
    id: PropTypes.string.isRequired,
    type: PropTypes.string,
    family: PropTypes.string.isRequired,
    isForecast: PropTypes.bool.isRequired,
    rail: PropTypes.shape({
      dow: PropTypes.string,
      day: PropTypes.string.isRequired,
      month: PropTypes.string.isRequired,
      isRange: PropTypes.bool.isRequired,
      countdown: PropTypes.string,
    }).isRequired,
    title: PropTypes.string.isRequired,
    kindTag: PropTypes.string.isRequired,
    superlative: PropTypes.string,
    metric: PropTypes.string,
    prose: PropTypes.string,
    isFeature: PropTypes.bool.isRequired,
    facts: PropTypes.arrayOf(PropTypes.shape({
      segments: PropTypes.arrayOf(PropTypes.shape({
        text: PropTypes.string.isRequired,
        tone: PropTypes.oneOf(['base', 'strong', 'accent']).isRequired,
      })).isRequired,
    })).isRequired,
    threshold: PropTypes.string,
    action: PropTypes.shape({
      label: PropTypes.string.isRequired,
      kind: PropTypes.string,
      date: PropTypes.string,
    }).isRequired,
    interactive: PropTypes.bool.isRequired,
  }).isRequired,
  onGoToPlan: PropTypes.func.isRequired,
};
