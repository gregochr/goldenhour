import React, { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import WindowComingUpEntry from './WindowComingUpEntry.jsx';
import WindowFirstComingUpHandoff from './WindowFirstComingUpHandoff.jsx';
import { buildChronology, chipCounts, footerCopy, FOOTER_LEAD } from '../utils/comingUpFeed.js';
import { ALMANAC_DAYS } from '../api/almanacApi.js';

/** Served counts default to zero, matching a payload that has not arrived yet or a degraded
 * legacy shape ({@code {entries: []}}, which carries no {@code counts} key at all). */
const EMPTY_COUNTS = { fixed: 0, forecast: 0, byFamily: {} };

/**
 * The "Coming up" pane — the 90-day almanac chronology (plan §6, P3a).
 *
 * <p>Recreates {@code docs/design/coming-up/Coming Up.html} §4/§5 against the P2 payload: month
 * rules, the two-column date-rail/card entry grid, the filter chips, the legend, and the replaced
 * header/footer copy. {@code docs/engineering/coming-up-plan.md} §6 is the brief; §13 is the wire
 * schema this component reads.
 *
 * <h2>Everything here is presentation over an already-decided payload</h2>
 *
 * <p>P2's {@code ComingUpAssembler} computed every fact, tag, threshold and action this pane shows.
 * This component and {@code WindowComingUpEntry} place that payload; {@code utils/comingUpFeed.js}
 * does the one kind of derivation that is still legitimately the client's — presentation arithmetic
 * over served fields (the date rail, month grouping, filter membership), never a new fact.
 *
 * <h2>The vocabulary now lives on the card, not the footer</h2>
 *
 * <p>Before P2, every entry was {@code ALMANAC} and the footer stated that once so a chip did not
 * have to repeat it on every row. P2 gives every entry a real {@code kindTag} ("Almanac" or
 * "Forecast · peak"), so the card now carries its own word and the footer's old job — see plan §6:
 * "the old vocabulary job now lives on the per-card kind tag, so delete it rather than ship both".
 *
 * <h2>Standing conditions are not built here</h2>
 *
 * <p>{@code events.conditions} ships an empty list until P4 builds the strip — this pane renders
 * nothing for it, which is the correct behaviour for an empty array rather than an omission.
 *
 * <h2>Still no count on the tab (D13's other half)</h2>
 *
 * <p>Preserved from before P2, because D13 rewrites it in P5, not here: the tab itself carries no
 * row count, for the same two reasons that predate this rewrite. The count does not exist until
 * the feed has been fetched, and the fetch is not made until the tab is opened — so it could only
 * ever appear <em>after</em> the reader had already looked, unless the fetch were made eager
 * (which D13 says P5 does, for the badge, not for this). And the number it would show is a row
 * count, which changes no decision: eleven dated events and eight are the same answer to "is
 * there anything coming up". {@code useComingUpFeed.js:9-13} carries the other half of this
 * refusal, at the fetch itself.
 *
 * <h2>No role gate</h2>
 *
 * <p>Unchanged from P1: {@code GET /api/almanac} has no role gate and no DTO mapper, so this pane
 * is identical for LITE, PRO and ADMIN.
 *
 * @param {object}   props
 * @param {string}   props.id       the panel's element id, paired with the tab's `aria-controls`
 * @param {boolean}  [props.hidden] true while the other tab is selected
 * @param {string}   props.labelledBy the tab's element id
 * @param {string}   props.status   `idle` | `loading` | `ready` | `error`
 * @param {?object}  props.events   the wrapped wire payload once it has arrived — see
 *                                  {@code ComingUpResponse}
 * @param {?Array}   props.hotTopics the live `briefing.hotTopics`, for the handoff row (D14)
 * @param {string}   props.todayStr the reader's today, `YYYY-MM-DD`
 * @param {function} props.onRetry  re-runs the fetch after a failure
 * @param {function} props.onGoToPlan switches to the Plan tab and moves focus there; takes an
 *                                    optional date — a `plan`-action card passes its own date
 */
export default function WindowFirstComingUp({
  id, labelledBy, hidden, status, events, hotTopics, todayStr, onRetry, onGoToPlan,
}) {
  const [activeFilter, setActiveFilter] = useState('all');

  const counts = events?.counts ?? EMPTY_COUNTS;
  const totalEntries = events?.entries?.length ?? 0;
  const chips = useMemo(() => chipCounts(counts), [counts]);
  const activeChipLabel = chips.find((chip) => chip.id === activeFilter)?.label ?? 'active';
  const monthGroups = useMemo(
    () => buildChronology(events?.entries, todayStr, activeFilter),
    [events, todayStr, activeFilter],
  );

  const panelRef = useRef(null);
  const retryRef = useRef(null);
  const retriedRef = useRef(false);
  /** Marks the press, so the effect below knows the focus move is owed to a reader action. */
  const handleRetry = () => {
    retriedRef.current = true;
    onRetry();
  };
  /**
   * Puts focus somewhere real after a retry settles.
   *
   * <p>Pressing the button unmounts it — the whole error paragraph is replaced by the rows, or by
   * a freshly mounted copy of itself — and focus on a removed element falls to {@code <body>},
   * which drops a keyboard reader at the top of the document with no idea whether anything
   * happened. On success focus goes to the panel, which is the element the tab already hands it
   * to; on a second failure it goes back to the new button, which is where they were.
   */
  useEffect(() => {
    if (!retriedRef.current || status === 'loading') return;
    retriedRef.current = false;
    (retryRef.current || panelRef.current)?.focus();
  }, [status]);

  return (
    <div
      id={id}
      role="tabpanel"
      aria-labelledby={labelledBy}
      // Focusable because the panel's content is not: every row is text, so without this a
      // keyboard user who has just selected the tab has nowhere for focus to go next.
      tabIndex={0}
      // Both the attribute and the class, matching the Plan pane, which carries the full argument.
      // In short: preflight's `[hidden]` rule is author-origin AND `!important`, so either half
      // alone would both hide this and take it out of the accessibility tree. The pairing is
      // defence in depth — the attribute is the semantic statement and the half jsdom can see.
      hidden={hidden}
      // `wf-body` is the Plan pane's inset class, worn here too — the two panes sit in the same
      // slot under the same tab rule, so a different inset would make the frame appear to move on
      // a tab change. Unchanged by this phase: the invariant plan §6 records still holds.
      className={hidden ? 'wf-body wf-cu-panel hidden' : 'wf-body wf-cu-panel'}
      ref={panelRef}
      data-testid="window-first-coming-up"
    >
      <div className="wf-cu">
        <div className="wf-cu-head">
          <span className="wf-cu-h">Coming up</span>
          <span className="wf-cu-d" data-testid="coming-up-subtitle">
            {`· dated events beyond Plan's four days, next ${ALMANAC_DAYS} days`}
          </span>
          {/* Rendered unconditionally (plan §6) — not gated on any entry actually using a dashed
              rule. Without it a reader who happens to filter down to an all-solid subset would see
              the legend appear and disappear as they click chips, which is worse than a legend
              that occasionally explains a distinction not currently on screen. */}
          <span className="wf-cu-legend" data-testid="coming-up-legend">
            <span className="wf-cu-legend-item">
              <span className="wf-cu-legend-swatch wf-cu-legend-swatch-fixed" aria-hidden="true" />
              fixed
            </span>
            <span className="wf-cu-legend-item">
              <span
                className="wf-cu-legend-swatch wf-cu-legend-swatch-forecast"
                aria-hidden="true"
              />
              still firming
            </span>
          </span>
        </div>

        <WindowFirstComingUpHandoff
          todayStr={todayStr}
          hotTopics={hotTopics}
          onGoToPlan={onGoToPlan}
        />

        {/* One always-mounted live region holding whichever of the three notes applies.
            ALWAYS mounted, and that is the load-bearing half: §5f records that a live region
            inserted in the same commit as its content is unreliably announced, which is why
            `WindowSpotSheet` puts `role="status"` on the element that is there whatever happens
            rather than on the conditional paragraph. Selection follows focus on this bar, so a
            reader arriving by arrow key gets no other signal that the pane is still loading or
            that it failed.

            The empty line is gated on `ready`, not on entry count. "Nothing in the next 90 days"
            rendered while the request is still in flight is a false claim about the sky, and it is
            the state a reader sees for the whole of the first round-trip. */}
        <div role="status" data-testid="coming-up-status">
          {status === 'loading' && (
            <p className="wf-cu-note" data-testid="coming-up-loading">Looking ahead…</p>
          )}

          {status === 'error' && (
            <p className="wf-cu-note" data-testid="coming-up-error">
              {'Could not load what is coming up. '}
              <button
                type="button"
                className="wf-cu-retry"
                ref={retryRef}
                onClick={handleRetry}
              >
                Try again
              </button>
            </p>
          )}

          {status === 'ready' && totalEntries === 0 && (
            <p className="wf-cu-note" data-testid="coming-up-empty">
              {`Nothing dated beyond Plan's four days in the next ${ALMANAC_DAYS} days.`}
            </p>
          )}

          {/* Distinct from the empty state above: `totalEntries` is the UNFILTERED count, so a
              chip whose family genuinely has nothing today (dust and air are near-unreachable at
              first ship, D9) would otherwise leave the chips on screen, the empty note absent
              (because the feed itself is not empty) and no rows — a silent blank pane with no clue
              why. Naming the filter, not just "nothing here", is what tells a reader to check the
              chip rather than assume the feed broke. */}
          {status === 'ready' && totalEntries > 0 && monthGroups.length === 0 && (
            <p className="wf-cu-note" data-testid="coming-up-filter-empty">
              {`Nothing dated matches the ${activeChipLabel} filter.`}
            </p>
          )}
        </div>

        {/* Chips and the chronology itself are gated on `ready`, unlike the head/handoff/footer
            above and below: their counts and rows come from data that does not exist yet during
            `idle`/`loading`/`error`, and a row of chips reading zero everywhere would understate
            the sky rather than describe it. */}
        {status === 'ready' && (
          <div className="wf-cu-chips" data-testid="coming-up-chips">
            {chips.map((chip) => (
              <button
                key={chip.id}
                type="button"
                className="wf-cu-chip"
                data-chip={chip.id}
                data-testid="coming-up-chip"
                // `aria-pressed`, not `aria-current`: this is a filter toggle and exactly one chip
                // is on at a time — the same convention `WindowRegionRail` already uses for the
                // same shape of control.
                aria-pressed={activeFilter === chip.id}
                onClick={() => setActiveFilter(chip.id)}
              >
                {chip.id !== 'all' && (
                  <span className="wf-cu-chip-dot" aria-hidden="true" />
                )}
                {chip.label}
                <span className="wf-cu-chip-count">{chip.count}</span>
              </button>
            ))}
          </div>
        )}

        {status === 'ready' && monthGroups.map((group, i) => (
          <div key={group.key}>
            <div
              className="wf-cu-month"
              data-testid="coming-up-month"
              data-first={i === 0 ? 'true' : undefined}
            >
              <span className="wf-cu-month-name">{group.monthLabel}</span>
              <span className="wf-cu-month-year">{group.year}</span>
              <span className="wf-cu-month-rule" aria-hidden="true" />
            </div>
            {/* One list per month, not one list interrupted by month-rule divs: a `role="list"`
                may only own `listitem` children, and a heading between two runs of entries would
                break that contract. Grouped-list-with-a-preceding-heading is the standard pattern
                for exactly this shape (a contacts list grouped by initial letter is the textbook
                example). */}
            <div
              role="list"
              data-testid="coming-up-list"
              aria-label={`${group.monthLabel} ${group.year}`}
            >
              {group.entries.map((entry) => (
                <WindowComingUpEntry key={entry.id} entry={entry} onGoToPlan={onGoToPlan} />
              ))}
            </div>
          </div>
        ))}

        {/* Before `ready` there is no served count to state — {@link FOOTER_LEAD} alone is the
            one sentence true regardless (the general rule, not a claim about how many of what).
            The full paragraph, naming actual counts, renders only once the feed has answered;
            without this split the pane claimed "every date here is fixed in advance" beneath
            "Looking ahead…" and under "Could not load what is coming up." alike. */}
        <p className="wf-cu-foot" data-testid="coming-up-footer">
          {status === 'ready' ? footerCopy(counts) : FOOTER_LEAD}
        </p>
      </div>
    </div>
  );
}

WindowFirstComingUp.propTypes = {
  id: PropTypes.string.isRequired,
  labelledBy: PropTypes.string.isRequired,
  hidden: PropTypes.bool,
  status: PropTypes.oneOf(['idle', 'loading', 'ready', 'error']).isRequired,
  events: PropTypes.shape({
    entries: PropTypes.array,
    counts: PropTypes.shape({
      fixed: PropTypes.number,
      forecast: PropTypes.number,
      byFamily: PropTypes.object,
    }),
  }),
  hotTopics: PropTypes.array,
  todayStr: PropTypes.string,
  onRetry: PropTypes.func.isRequired,
  onGoToPlan: PropTypes.func.isRequired,
};
