import React from 'react';
import PropTypes from 'prop-types';
import ComingUpTideSparkline from './chart/ComingUpTideSparkline.jsx';

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
 * <h2>The map channel, and why the click seam dispatches by kind (D8, plan §6b)</h2>
 *
 * <p>{@code coastal-spots} and {@code dark-sky-spots} now dispatch through {@code onShowOnMap} with
 * a {@code kind:'coming-up'} handoff object — a new trigger kind (D8), never the {@code kind:'topic'}
 * branch {@code HotTopicStrip} uses today, because P6 deletes that branch outright and a new caller
 * of it would break the moment P6 lands. Both card kinds are interactive as of this phase
 * ({@code utils/comingUpFeed.js}'s {@code interactive} flag now names all three served kinds); the
 * dispatch stays keyed on {@code entry.action.kind} rather than on {@code interactive} for the same
 * reason the plan-only dispatch always was — a fourth served kind, if one is ever added, must not
 * silently fall through to a wrong destination just because {@code interactive} happened to already
 * be true for it.
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
 * <h2>The coincidence card renders alongside prose, not instead of it — a corrected first attempt
 * (D10, plan §6b)</h2>
 *
 * <p>{@code entry.coincidence} carries only the LOSING topic's own line — {@code ComingUpAssembler}
 * javadoc, and the P4 phase-log row — because the winning entry's own identity is already the
 * card's title/family/facts. The first draft of this component therefore rendered a synthesized
 * "self" line (swatch + {@code entry.title} again) ahead of the served line, matching the design's
 * literal two-line picture — but the design's {@code nm} for a merged entry is a COMBINED name
 * ("Spring tide run, on a supermoon"), where the served {@code entry.title} is not, so that self
 * line read the card's own title back verbatim immediately under the title row that had just shown
 * it — a literal duplicate, not a design choice. Fixed by rendering only the served line(s); the
 * card's own identity is the title row, already there.
 *
 * <p>The first draft also treated {@code coincidence} and {@code prose} as mutually exclusive,
 * reasoning from the design bundle's own {@code EV} fixtures (which never carry both {@code why}
 * and {@code coin}/{@code join} on one entry) — but the fixtures are not a wire contract.
 * {@code ComingUpAssembler.assemble} runs {@code markFirstOfType} AFTER {@code mergeCoincidences},
 * so a merged winner that is also the first-of-its-type in the window legitimately carries BOTH
 * fields, and the exclusive-or silently dropped the prose whenever it did. Fixed by rendering both
 * when both are served, in design order (prose above the coincidence lines) — a frontend-only fix
 * that changes nothing about which entry gets which field, only that neither is thrown away.
 *
 * <h2>What this phase does not draw</h2>
 *
 * <p>{@code entry.scoreNote} is not rendered here. Plan §13 annotates it "since-line +
 * card read it", but §6's own card inventory for this phase never lists it, and the design bundle
 * has no visual slot for a sentence separate from the threshold line — inventing one unreviewed
 * would be exactly the mistake P2 refused to make for the lone-tide-run threshold gap (§11.21).
 * Left for whichever phase builds the since-line (P5) to place, recorded in the phase log rather
 * than silently dropped.
 *
 * @param {object}   props
 * @param {object}   props.entry       a view from {@code buildEntryView}
 * @param {function} props.onGoToPlan  switches to the Plan tab and moves focus there, given a date
 * @param {function} props.onShowOnMap opens the map overlay for a `kind:'coming-up'` handoff object
 *                                     (D8) — the `coastal-spots`/`dark-sky-spots` destinations
 */
export default function WindowComingUpEntry({ entry, onGoToPlan, onShowOnMap }) {
  const { rail } = entry;
  const cardClassName = [
    'wf-cu-card',
    entry.isFeature ? 'wf-cu-card-feat' : null,
    entry.isForecast ? 'wf-cu-card-fc' : null,
    entry.interactive ? null : 'wf-cu-card-inert',
  ].filter(Boolean).join(' ');

  /**
   * The one destination a card's action can carry. Switches on the SERVED kind, not on
   * `interactive` (see the class doc's "click seam" section) — `coastal-spots`/`dark-sky-spots` both
   * route through the same new `kind:'coming-up'` map trigger (D8), differing only in which flag
   * they carry: `filterAction` for a location-type filter (coastal), `darkSky` for the Bortle-class
   * toggle (dark sky) — never both, and never `kind:'topic'`, which P6 deletes.
   */
  const handleClick = () => {
    if (entry.action.kind === 'plan') {
      onGoToPlan(entry.action.date);
    } else if (entry.action.kind === 'coastal-spots') {
      onShowOnMap({
        kind: 'coming-up', filterAction: 'SEASCAPE', label: entry.title, date: entry.action.date,
      });
    } else if (entry.action.kind === 'dark-sky-spots') {
      onShowOnMap({
        kind: 'coming-up', darkSky: true, label: entry.title, date: entry.action.date,
      });
    }
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

      {/* Additive with `prose`, NOT exclusive-or (a corrected first attempt — see the class doc's
          coincidence section for why). Only the MERGED topic's own line is rendered; the winner's
          own identity is not repeated here — see the class doc. */}
      {entry.prose && (
        <>
          <span className="wf-cu-prose" data-testid="coming-up-prose">{entry.prose}</span>
          {' '}
        </>
      )}

      {entry.coincidence && entry.coincidence.length > 0 && (
        <>
          <span className="wf-cu-coin" data-testid="coming-up-coincidence">
            {entry.coincidence.flatMap((line, i) => [
              i > 0 ? ' ' : null,
              <span
                key={`${entry.id}:coin:${i}`}
                className="wf-cu-coin-line"
                data-family={line.family}
                data-testid="coming-up-coincidence-line"
              >
                <span className="wf-cu-coin-swatch" aria-hidden="true" />
                <span className="wf-cu-coin-name">{line.name}</span>
                {' '}
                <span className="wf-cu-coin-facts" data-testid="coming-up-coincidence-facts">
                  {line.factsLabel}
                </span>
              </span>,
            ])}
          </span>
          {entry.joinNote && (
            <>
              {' '}
              <span className="wf-cu-join" data-testid="coming-up-join-note">{entry.joinNote}</span>
            </>
          )}
          {' '}
        </>
      )}

      {(entry.tide || entry.facts.length > 0) && (
        <>
          <div className="wf-facts" data-testid="coming-up-facts">
            {entry.tide && (
              <>
                <ComingUpTideSparkline tide={entry.tide} />
                {entry.facts.length > 0 ? ' ' : null}
              </>
            )}
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
    // `data-entry-id` is the standing-conditions strip's scroll-to-entry anchor (plan §7 P4):
    // `in the list →` on a promoted occurrence queries this attribute directly rather than reaching
    // for a real DOM `id`, so a run of numeric almanac ids can never collide with another element's.
    <div
      className="wf-cu-ent"
      role="listitem"
      data-testid="coming-up-entry"
      data-type={entry.type}
      data-entry-id={entry.id}
    >
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
    tide: PropTypes.shape({
      range: PropTypes.number.isRequired,
      delta: PropTypes.number.isRequired,
      phase: PropTypes.oneOf(['HW', 'LW']).isRequired,
    }),
    coincidence: PropTypes.arrayOf(PropTypes.shape({
      family: PropTypes.string.isRequired,
      name: PropTypes.string.isRequired,
      factsLabel: PropTypes.string.isRequired,
    })),
    joinNote: PropTypes.string,
  }).isRequired,
  onGoToPlan: PropTypes.func.isRequired,
  onShowOnMap: PropTypes.func.isRequired,
};
