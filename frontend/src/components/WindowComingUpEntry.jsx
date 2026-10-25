import React from 'react';
import PropTypes from 'prop-types';
import ComingUpTideSparkline from './chart/ComingUpTideSparkline.jsx';
import { entryGlyph, coincidenceLineGlyph } from '../utils/comingUpGlyphs.js';
import { rangeBarHeight, BIG_RANGE_PERCENTILE } from '../utils/comingUpFeed.js';

const TIDE_RUN_TYPES = ['spring-tide', 'king-tide'];

/**
 * True for a tide-run card: hosted by the run itself, or by a supermoon that absorbed it (the merged
 * card still carries the run's high-water rows, and its coincidence lines name the run's type).
 */
function isTideRunEntry(entry) {
  return TIDE_RUN_TYPES.includes(entry.type)
    || (entry.coincidence ?? []).some((line) => TIDE_RUN_TYPES.includes(line.type));
}

/**
 * Whether the join space belongs between two segments of one fact. Every card type keeps the space
 * between all segments (it is what an accessible name is built from). A TIDE-RUN row is running
 * text, where a segment that ends with, or whose successor begins with, whitespace or an NBSP
 * (`sunrise . `, ` . Wed 28`) already supplies its own, so the join would double it.
 */
function needsJoinSpace(previous, segment, tideRun) {
  if (!previous) return false;
  return !(tideRun && (/\s$/.test(previous.text) || /^\s/.test(segment.text)));
}

/**
 * The per-day door row of a short multi-day map-door entry (see the class doc). Lead word and
 * caption are plain text beside a group of chip buttons named by the lead word; the caption exists
 * only while a FUTURE day is dimmed (a day that has gone is dimmed too but is not news), and says
 * so differently when every future day is.
 */
function DayChips({ entry, onShowOnMap }) {
  const { dayChips, doorNoun, action, rangeScale, rangeAverage } = entry;
  const labelled = Boolean(rangeScale?.hasPercentiles);
  const averaged = Boolean(rangeScale) && rangeAverage !== null && rangeAverage !== undefined;
  // The kind decides only the subject filter: coastal sends the SEASCAPE type, dark-sky the Bortle
  // toggle, and plan (every spot) neither.
  const coastal = action.kind === 'coastal-spots';
  const darkSky = action.kind === 'dark-sky-spots';
  const noun = doorNoun.toLowerCase();
  // marks is null on every chip of an unmerged entry and an array on every chip of a merged one.
  const marked = dayChips.some((chip) => Boolean(chip.marks));
  const future = dayChips.filter((chip) => !chip.gone);
  const anyDimmed = future.some((chip) => !chip.live);
  const allDimmed = future.every((chip) => !chip.live);
  // One chip is a one-day entry: "by day" would read oddly, so the lead is the bare door name.
  const single = dayChips.length === 1;
  const leadText = single ? doorNoun : `${doorNoun} by day`;
  // Singular when exactly one future day is left and it is dimmed (a one-box row, or the last day
  // of a run whose other days have gone).
  const allDimmedNote = future.length === 1
    ? 'no forecast for this day yet' : 'no forecast for these days yet';
  // A ranged row draws it under the row instead of inside it (see the return).
  const note = (
    <span className="wf-cu-days-note" data-testid="coming-up-day-chips-note">
      {allDimmed ? allDimmedNote : 'dimmed · no forecast yet'}
    </span>
  );
  // A DOM id from an entry id like `spring-tide:2026-10-09:2026-10-14`.
  const leadId = `${entry.id.replace(/[^A-Za-z0-9_-]/g, '-')}-days-lead`;

  const open = (chip) => onShowOnMap({
    kind: 'coming-up',
    filterAction: coastal ? 'SEASCAPE' : null,
    darkSky,
    // Names the day picked, so the crumb's headline says which day's map it is.
    label: `${entry.title} · ${chip.dateLabel}`,
    date: chip.date,
  });

  // Today's chip shows the word Today in place of the weekday, so its name says Today too: the
  // visible text is then contiguous in the name (label in name).
  const nameOf = (chip) => {
    const label = chip.today ? chip.dateLabel.replace(/^\S+/, 'Today') : chip.dateLabel;
    // A merged entry names each served mark in order ("peak" as ", peak day"); an unmerged one
    // (marks null) keeps the served-action-date peak.
    const peak = !chip.marks
      ? (chip.peak ? ', peak day' : '')
      : chip.marks.map((m) => (m.label === 'peak' ? ', peak day' : `, ${m.label}`)).join('');
    const ranked = labelled && chip.rangePercentile !== null && chip.rangePercentile !== undefined
      ? `, top ${chip.rangePercentile} percent of the year` : '';
    const range = rangeScale && chip.rangeMetres !== null && chip.rangeMetres !== undefined
      ? `, ${chip.rangeMetres.toFixed(1)} metre range${ranked}` : '';
    if (chip.gone) return `${label}${peak}${range} — gone`;
    if (!chip.live) return `${label}${peak}${range} — no forecast yet`;
    return `Show ${noun} for ${label}${peak}${range}`;
  };

  return (
    <>
    <div className="wf-cu-days" data-testid="coming-up-day-chips" data-marked={marked ? 'true' : undefined}
      data-ranged={rangeScale ? 'true' : undefined}
      data-labelled={labelled ? 'true' : undefined}
      data-avg={averaged ? 'true' : undefined}
      style={averaged ? { '--wf-cu-avg-h': `${rangeBarHeight(rangeAverage, rangeScale)}px` } : undefined}
    >
      <span className="wf-cu-days-lead" id={leadId}>{leadText}</span>
      {' '}
      <div role="group" aria-labelledby={leadId} className="wf-cu-days-group">
        {rangeScale && (
          <span className="wf-cu-days-axis" aria-hidden="true" data-testid="coming-up-range-axis">
            {averaged && <span className="wf-cu-days-axis-avg">AVG</span>}
            <span className="wf-cu-days-axis-range">range</span>
          </span>
        )}
        {dayChips.map((chip) => {
          // Unmerged: the served action date. Merged: only a served `peak` mark, in that mark's family.
          const peakMark = chip.marks ? chip.marks.find((m) => m.label === 'peak') : null;
          const isPeak = chip.marks ? Boolean(peakMark) : chip.peak;
          const third = !chip.marks
            ? [chip.monthWord, chip.peak ? 'peak' : null].filter(Boolean).join(' · ') : null;
          const hasRange = chip.rangeMetres !== null && chip.rangeMetres !== undefined;
          const button = (
            <button
              key={chip.date}
              type="button"
              className="wf-cu-day"
              disabled={!chip.live}
              aria-label={nameOf(chip)}
              onClick={chip.live ? () => open(chip) : undefined}
              data-testid="coming-up-day-chip"
              data-date={chip.date}
              data-peak={isPeak ? 'true' : 'false'}
              data-peak-family={peakMark ? peakMark.family : undefined}
              data-live={chip.live ? 'true' : 'false'}
            >
              <span className="wf-cu-day-dow">{chip.today ? 'Today' : chip.dow}</span>
              <span className="wf-cu-day-dn">{chip.day}</span>
              {(!chip.marks ? Boolean(third) : Boolean(chip.monthWord) || chip.marks.length > 0) && (
                <span className="wf-cu-day-sub" data-testid="coming-up-day-chip-sub">
                  {!chip.marks ? third : (
                    <>
                      {chip.monthWord && <span className="wf-cu-day-month">{chip.monthWord}</span>}
                      {chip.marks.map((m, i) => (
                        <span
                          key={`${m.label}-${i}`}
                          className="wf-cu-day-mark"
                          data-family={m.family}
                        >
                          {m.label}
                        </span>
                      ))}
                    </>
                  )}
                </span>
              )}
            </button>
          );
          if (!rangeScale) return button;
          const pct = labelled && chip.rangePercentile !== null && chip.rangePercentile !== undefined
            ? chip.rangePercentile : null;
          // Tiers read served facts only: the server's peak flag, then the served percentile.
          let tier = 'rest';
          if (chip.rangePeak) tier = 'peak';
          else if (pct !== null && pct <= BIG_RANGE_PERCENTILE) tier = 'big';
          const multiple = hasRange && averaged && rangeAverage > 0
            ? ` · ${(chip.rangeMetres / rangeAverage).toFixed(1)}× average` : '';
          // The aria-hidden spans are label, track and metres only: the column holds the button and
          // must stay exposed. The served `peak` day and the gold PEAK chip are the same day on a
          // true-peak run; on a partial-coverage run `dayRanges` flags no peak, so no pill.
          return (
            <div
              key={chip.date}
              className="wf-cu-day-col"
              data-testid="coming-up-day-col"
              data-tier={hasRange ? tier : undefined}
              data-live={chip.live ? 'true' : 'false'}
              title={hasRange ? `${chip.rangeMetres.toFixed(1)} m range${multiple}` : undefined}
            >
              {labelled && (
                <span className="wf-cu-day-range-label" aria-hidden="true">
                  {hasRange && pct !== null && <span className="wf-cu-day-range-pill">{`top ${pct}%`}</span>}
                </span>
              )}
              <span className="wf-cu-day-range-track" aria-hidden="true">
                {hasRange && (
                  <span
                    className="wf-cu-day-range-bar"
                    data-testid="coming-up-range-bar"
                    style={{ '--wf-cu-bar-h': `${rangeBarHeight(chip.rangeMetres, rangeScale)}px` }}
                  />
                )}
              </span>
              {button}
              <span className="wf-cu-day-range-metres" aria-hidden="true">
                {hasRange ? `${chip.rangeMetres.toFixed(1)}\u00A0m` : ''}
              </span>
            </div>
          );
        })}
      </div>
      {anyDimmed && !rangeScale && (
        <>
          {' '}
          {note}
        </>
      )}
    </div>
    {/* A ranged row's note sits under the row, outside it, so a wrapped note never inherits the
        row-centring padding. */}
    {anyDimmed && rangeScale && (
      <>
        {' '}
        {note}
      </>
    )}
    </>
  );
}

DayChips.propTypes = {
  entry: PropTypes.shape({
    id: PropTypes.string.isRequired,
    title: PropTypes.string.isRequired,
    doorNoun: PropTypes.string.isRequired,
    action: PropTypes.shape({ kind: PropTypes.string }).isRequired,
    dayChips: PropTypes.arrayOf(PropTypes.shape({
      date: PropTypes.string.isRequired,
      dow: PropTypes.string.isRequired,
      day: PropTypes.string.isRequired,
      monthWord: PropTypes.string,
      dateLabel: PropTypes.string.isRequired,
      today: PropTypes.bool.isRequired,
      gone: PropTypes.bool.isRequired,
      peak: PropTypes.bool.isRequired,
      marks: PropTypes.arrayOf(PropTypes.shape({
        label: PropTypes.string.isRequired,
        family: PropTypes.string.isRequired,
      })),
      rangeMetres: PropTypes.number,
      rangePeak: PropTypes.bool,
      rangePercentile: PropTypes.number,
      live: PropTypes.bool.isRequired,
    })).isRequired,
    rangeScale: PropTypes.shape({
      min: PropTypes.number.isRequired,
      max: PropTypes.number.isRequired,
      lo: PropTypes.number.isRequired,
      hi: PropTypes.number.isRequired,
      hasPercentiles: PropTypes.bool.isRequired,
    }),
    rangeAverage: PropTypes.number,
  }).isRequired,
  onShowOnMap: PropTypes.func.isRequired,
};

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
 * Everything else renders as a plain {@code <div>} — no pointer cursor, no hover tint, no button
 * role. Without a control of its own that is the inert state (honest rather than a promise the tab
 * cannot keep yet); a card carrying {@code dayChips} is the same {@code <div>} but is NOT inert,
 * because its chips are the controls (see the run section below). The click handler dispatches on {@code entry.action.kind} itself, not merely on
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
 * <h2>A map door with no forecast behind it is withheld, and says why (plan §11.24)</h2>
 *
 * <p>The feed covers 90 days but the reader holds colour forecasts for only the next few, so a
 * {@code coastal-spots}/{@code dark-sky-spots} action dated beyond them would open a map of unscored
 * pins. (A {@code plan} action is never withheld: its destination is the tab, not a date.)
 * {@code buildEntryView} decides that ({@code actionWithheld}, with the reason in
 * {@code actionNote}); here it is only drawn: the card takes the inert {@code <div>} form above —
 * never a disabled {@code <button>} — and the action line prints the note INSTEAD of the served
 * label, because the label ("Show coastal spots for 11 Oct →") promises a door and printing it on a
 * card that does nothing would be the dead pointer this section forbids.
 *
 * <h2>A short run is a row of per-day doors, and the card is not the control (plan §11.25)</h2>
 *
 * <p>A dated entry of at most ten days (a tide run, a multi-night supermoon, a solstice, or a
 * single-day meteor shower, supermoon or eclipse, which gets a one-box row for consistency — plan
 * §11.26) carries {@code entry.dayChips}, whatever its door kind: the kind only decides which spots
 * the chip's map shows (coastal, dark-sky, or every spot for a {@code plan} entry, whose
 * {@code onGoToPlan} line survives only for an entry over the cap). Its
 * single door named only the peak day, so most of the run had nowhere to go. The chip row replaces
 * the action line, each chip is its own real {@code <button>} (a live one sends the same
 * {@code kind:'coming-up'} handoff the single door does, with ITS date; a day with no forecast is
 * {@code disabled}), and the card itself is the plain {@code <div>} — never a button, so there are no
 * nested controls — without {@code wf-cu-card-inert}, since the card is not a dead door. This is the
 * stated exception to "a click on the card invokes the entry's single action"; clicking the card body
 * calls nothing. The component keys on {@code dayChips} itself, not on {@code interactive}, so a view
 * that somehow carried both still renders the {@code <div>}. A day that has already gone is dimmed
 * and disabled too, and a short caption says what dimmed means, appearing only while a FUTURE day is
 * dimmed.
 *
 * <p>A chip DOES carry an {@code aria-label}, unlike the card button below, and that is deliberate:
 * a chip's content is a weekday, a number and at most one short word, so the override loses nothing
 * and the label states the whole fact ("Show coastal spots for Fri 9 Oct, peak day", "Mon 12 Oct — no
 * forecast yet", "Fri 9 Oct — gone"; today's chip, which shows the word Today, is named with that
 * word so the visible text is contiguous in its name), where the card button's content is the richest thing on the page and an override
 * would discard it.
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
 * approach the strip's "At a Glance" header button in {@code WindowComingUpFourDays} uses; its chips are built from content too), which is why the
 * title-row spans below are interleaved with bare {@code {' '}} text-node siblings: JSX drops
 * whitespace-only text that contains a line break rather than collapsing it to a space, so they
 * are what keeps the phrases apart in that one computed name.
 *
 * <p><b>⚠️ Every separator this file renders is inert in a browser</b> — this doc used to assert
 * the name "runs every phrase together", and no browser measurement supports that. Nothing this
 * component places side by side is plain inline content. The sections are block-level boxes
 * ({@code .wf-cu-ttl} and {@code .wf-facts} are {@code flex} containers; {@code .wf-cu-prose},
 * {@code .wf-cu-coin} and {@code .wf-cu-threshold} are {@code block}) or an atomic inline
 * ({@code .wf-cu-action} is {@code inline-flex}); inside them the pieces are flex items (the title
 * row's spans, each fact, each fact's segments) or atomic inlines (each {@code inline-flex}
 * {@code .wf-cu-coin-line}). Every engine spaces all of these itself. Measured 2026-09-05, and
 * again 2026-09-11 across every state this component's tests render, by Playwright's
 * accessible-name algorithm over Chromium's, WebKit's and Firefox's layout; Chromium's native
 * accessibility tree agreed on 2026-09-05 and again on 2026-09-14. The rule, the method and why
 * the suite reads it differently are in {@code WindowComingUpFourDays}'s class doc.
 *
 * <p><b>⚠️ One separator inside the card IS load-bearing, and it is not in this file.</b>
 * {@code ComingUpTideSparkline}'s label sets a {@code <b>} and a {@code <span>} side by side as
 * plain inline content, so without its {@code {' '}} the card's name glues them —
 * "…5.2 m+1.9 vs avg…".
 * This doc previously said the card's CSS blockifies everything that carries text — true only of
 * an entry with no tide chart; the measurement behind it used a fixture with {@code tide: null}.
 * Keep every separator: they cost nothing, and the inert ones become load-bearing the moment
 * anything beside them becomes plain inline content.
 *
 * <h2>The coincidence card renders alongside prose, not instead of it — a corrected first attempt
 * (D10, plan §6b)</h2>
 *
 * <p>{@code entry.coincidence} carries ONE LINE PER MERGED TOPIC, host first, each with that
 * topic's own dates (supermoon-tide-run-plan.md R5; it used to carry only the losing topic). The
 * component draws exactly the served lines and synthesizes none: the title row is the combined
 * name ("Spring tide run, on a supermoon"), and a self line built from {@code entry.title} would
 * read it back verbatim, so the lines come from the server alone. (History: an earlier draft built
 * a synthesized "self" line ahead of the served one.)
 *
 * <h2>Day marks (supermoon-tide-run-plan S3)</h2>
 *
 * <p>A merged entry also serves {@code dayMarks}, which {@code buildDayChips} threads onto each chip
 * as {@code marks} (null on every chip of an unmerged entry). A mark is drawn on the chip's third
 * line as its own block span coloured by its FAMILY token, and the chip's name lists the marks in
 * served order. On a merged entry "peak" and ", peak day" come only from a served {@code peak} mark,
 * never from {@code chip.peak} (a partial-coverage run's action date is its figures day); the group
 * carries {@code data-marked}, and the chip holding the {@code peak} mark is {@code data-peak} and
 * coloured by that mark's family ({@code data-peak-family}), never by the host and never gold.
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
 * @param {function} props.onShowOnMap opens the Map tab (the structured door) for a `kind:'coming-up'` handoff object
 *                                     (D8) — the `coastal-spots`/`dark-sky-spots` destinations
 */
export default function WindowComingUpEntry({ entry, onGoToPlan, onShowOnMap }) {
  const { rail } = entry;
  const glyph = entryGlyph(entry);
  // A tide-run card (hosted by the run, or by a supermoon that absorbed it) carries the sunrise and
  // sunset high-water rows: those facts take a line each and read as running text.
  const isTideRun = isTideRunEntry(entry);
  const cardClassName = [
    'wf-cu-card',
    entry.isFeature ? 'wf-cu-card-feat' : null,
    entry.isForecast ? 'wf-cu-card-fc' : null,
    // A chip run is neither a button nor a dead door: its own chips are the controls.
    entry.dayChips ? 'wf-cu-card-chips' : null,
    entry.interactive || entry.dayChips ? null : 'wf-cu-card-inert',
    // The fresh box-shadow (design §4 "Fresh entries…also get box-shadow"; plan P5) — clears
    // together with the badge and the NEW flag below, all three keyed off the same `isNew`.
    entry.isNew ? 'wf-cu-card-fresh' : null,
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

  // Every top-level section is separated by a bare `{' '}` text-node sibling rather than relying
  // on flex `gap` to imply one — JSX drops whitespace-only text that contains a line break, and
  // jsdom (which computes no layout, so every section reads as inline to it) needs a real text
  // node to see a boundary. ⚠️ Defensive, not load-bearing in a browser: every top-level
  // section is a block-level box or an atomic inline, and the engines space both themselves
  // (measured — see the class doc). They earn their keep if one of them ever becomes plain
  // inline content.
  const cardBody = (
    <>
      <div className="wf-cu-ttl">
        {glyph && (
          <>
            <span className="wf-cu-gi" aria-hidden="true" data-testid="coming-up-glyph">{glyph}</span>
            {' '}
          </>
        )}
        <span className="wf-cu-nm" data-testid="coming-up-title">{entry.title}</span>
        {' '}
        {/* The tags wrap in one row under the title on a phone, indented under the title text; the
            wrapper is `display: contents` elsewhere, so desktop and tablet lay out as before. */}
        <span className={glyph ? 'wf-cu-tags wf-cu-tags-g' : 'wf-cu-tags'} data-testid="coming-up-tags">
          {/* Between the name and the kind tag, matching the design's title-row order (plan §6/P5).
              Clears together with the badge and the fresh box-shadow above — all three read the same
              `entry.isNew`, computed once by comingUpArrivals.js's isNewEntry and threaded through
              buildEntryView (plan D3/D12). */}
          {entry.isNew && (
            <>
              <span className="wf-cu-new" data-testid="coming-up-new-flag">New</span>
              {' '}
            </>
          )}
          <span className="wf-cu-kindtag" data-testid="coming-up-kindtag">{entry.kindTag}</span>
          {entry.inNext4Days && (
            <>
              {' '}
              <span className="wf-cu-next4" data-testid="coming-up-tag-next4">In next 4 days</span>
            </>
          )}
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
        </span>
      </div>
      {' '}

      {/* Additive with `prose`, NOT exclusive-or (a corrected first attempt — see the class doc's
          coincidence section for why). Every served line is rendered, host first — one per merged
          topic, each with its own dates; nothing is synthesized — see the class doc. */}
      {entry.prose && (
        <>
          <span className="wf-cu-prose" data-testid="coming-up-prose">{entry.prose}</span>
          {' '}
        </>
      )}

      {entry.coincidence && entry.coincidence.length > 0 && (
        <>
          <span className="wf-cu-coin" data-testid="coming-up-coincidence">
            {entry.coincidence.flatMap((line, i) => {
              const coinGlyph = coincidenceLineGlyph(line);
              return [
                i > 0 ? ' ' : null,
                <span
                  key={`${entry.id}:coin:${i}`}
                  className="wf-cu-coin-line"
                  data-family={line.family}
                  data-testid="coming-up-coincidence-line"
                >
                  <span className="wf-cu-coin-swatch" aria-hidden="true" />
                  {coinGlyph && (
                    <span className="wf-cu-gi wf-cu-gi-sm" aria-hidden="true" data-testid="coming-up-coincidence-glyph">
                      {coinGlyph}
                    </span>
                  )}
                  <span className="wf-cu-coin-name">{line.name}</span>
                  {' '}
                  <span className="wf-cu-coin-facts" data-testid="coming-up-coincidence-facts">
                    {line.factsLabel}
                  </span>
                </span>,
              ];
            })}
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
          <div
            className={isTideRun ? 'wf-facts wf-facts-rows' : 'wf-facts'}
            data-testid="coming-up-facts"
          >
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
              <span key={`${entry.id}:fact:${i}`} className="wf-cu-fact" data-testid="coming-up-fact">
                {fact.segments.flatMap((segment, j) => [
                  needsJoinSpace(fact.segments[j - 1], segment, isTideRun) ? ' ' : null,
                  <span key={`${j}:${segment.text}`} data-tone={segment.tone}>{segment.text}</span>,
                ])}
              </span>,
            ])}
          </div>
          {' '}
        </>
      )}

      {/* The dashed-top aside (design's `why2` slot, plan §2.8) — generic, not a lunar branch: the
          lunar eclipse topic is its first server-side writer (`aside` on `ComingUpEntry`), carrying
          the exposure note ("no filter needed"), but any later type may use it. Placed after the
          facts and before the threshold, exactly as the design orders `why2` before `.foot`. */}
      {entry.aside && (
        <>
          <span className="wf-cu-aside" data-testid="coming-up-aside">
            {entry.aside}
          </span>
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

      {entry.dayChips ? (
        <DayChips entry={entry} onShowOnMap={onShowOnMap} />
      ) : entry.actionWithheld ? (
        <span
          className="wf-cu-action wf-cu-action-withheld"
          data-testid="coming-up-action"
          data-withheld="true"
        >
          {entry.actionNote}
        </span>
      ) : (
        <span className="wf-cu-action" data-testid="coming-up-action">{entry.action.label}</span>
      )}
    </>
  );

  return (
    // `data-entry-id` is the scroll-to-entry anchor:
    // the lanes' jump button queries this attribute directly rather than reaching
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

      {entry.interactive && !entry.dayChips ? (
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
    aside: PropTypes.string,
    threshold: PropTypes.string,
    action: PropTypes.shape({
      label: PropTypes.string.isRequired,
      kind: PropTypes.string,
      date: PropTypes.string,
    }).isRequired,
    interactive: PropTypes.bool.isRequired,
    actionWithheld: PropTypes.bool,
    actionNote: PropTypes.string,
    dayChips: PropTypes.arrayOf(PropTypes.shape({
      date: PropTypes.string.isRequired,
      dow: PropTypes.string.isRequired,
      day: PropTypes.string.isRequired,
      monthWord: PropTypes.string,
      dateLabel: PropTypes.string.isRequired,
      today: PropTypes.bool.isRequired,
      gone: PropTypes.bool.isRequired,
      peak: PropTypes.bool.isRequired,
      marks: PropTypes.arrayOf(PropTypes.shape({
        label: PropTypes.string.isRequired,
        family: PropTypes.string.isRequired,
      })),
      live: PropTypes.bool.isRequired,
    })),
    doorNoun: PropTypes.string,
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
    isNew: PropTypes.bool.isRequired,
    inNext4Days: PropTypes.bool,
  }).isRequired,
  onGoToPlan: PropTypes.func.isRequired,
  onShowOnMap: PropTypes.func.isRequired,
};
