import React, { useEffect, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import WindowComingUpRow from './WindowComingUpRow.jsx';
import { buildComingUpRows } from '../utils/comingUpFeed.js';
import { ALMANAC_DAYS } from '../api/almanacApi.js';

/**
 * The "Coming up" pane — the 90-day almanac feed.
 *
 * <p>Mock {@code .cu} (`Plan Window First.html` :158-176, rendered at :300-320); the v2 mock stubs
 * this pane and points at v1 for it, which its own README confirms ("Retained for the Coming up tab
 * in full, which v2 stubs"). Plan §3 is the specification.
 *
 * <h2>It answers the one question a window cannot</h2>
 *
 * <p>The Plan tab is six solar windows over four days. This is "when is the next one" over ninety —
 * and plan §3's rule for keeping Plan small depends on it: seasons live here permanently, and Plan
 * only ever carries tonight's delta.
 *
 * <h2>Spans here, one card per day on Plan — a difference, not a contradiction</h2>
 *
 * <p>CLAUDE.md records that a multi-row card for a four-day tide run was tried on the Plan tab and
 * <b>reverted</b>, because "a single multi-row card for a four-day event sits at one point in a
 * chronological list and breaks the flow for every topic around it". This feed does the opposite
 * and both are right, because the two lists are indexed differently. <b>The Plan tab's list is
 * indexed by day</b> — every day has a row whether or not anything is happening on it — so a run
 * collapsed to one entry disappears from the Thursday a reader planning Thursday is looking at.
 * <b>This list is indexed by event</b>: a day with nothing has no row at all, and there are
 * seventy-nine such days in a ninety-day feed. Expanding an eleven-week NLC season into
 * seventy-seven rows would bury every other event under one of them.
 *
 * <p>What the difference costs is that a span's extent is no longer visible from the list's own
 * shape, so it has to be stated: the date column carries the whole span, and a span whose edges are
 * not real says so.
 *
 * <h2>No count on the tab, against the design README</h2>
 *
 * <p>The README specifies "Coming up (with count)" and the mock draws a `3`. It is dropped for two
 * reasons that compound. The count does not exist until the feed has been fetched, and the feed is
 * not fetched until the tab is opened — so it could only ever appear <em>after</em> the reader had
 * already looked, unless the fetch were made eager, which would spend a request on every Plan-tab
 * reader to decorate a tab. And the number it would show is the row count, which changes no
 * decision: eleven dated events and eight are the same answer to "is there anything coming up".
 * Plan §7 is where this deviation is recorded.
 *
 * <h2>The vocabulary is stated once, at the foot</h2>
 *
 * <p>Plan §3 gives the tab a two-word tag vocabulary, Almanac and Forecast, and the mock puts a
 * chip on every row. Every entry the five sources emit is {@code ALMANAC} — {@code AlmanacKind}'s
 * own Javadoc says FORECAST is reserved and unused — so a chip on every row would be a word that
 * never varies. It is stated once here instead, and only a row that departs from it is marked:
 * the same treatment the confidence channel already takes on the Plan screen, where HIGH goes
 * unmarked and only LOW carries a marker.
 *
 * <h2>No role gate, and no {@code contentDisabled} greying</h2>
 *
 * <p>CLAUDE.md asks that every new UI feature be assessed for role gating, so: <b>this pane is
 * identical for LITE, PRO and ADMIN, deliberately.</b> {@code GET /api/almanac} has no role gate
 * and no DTO mapper — {@code AlmanacControllerTest} pins that a LITE user gets the full payload —
 * so there is no backend 403 for a frontend treatment to reflect, and greying it would be a
 * paywall over data the endpoint hands out freely. It is also the right answer on the merits: this
 * is ephemeris, the same class of thing as {@code GET /api/tides}, which is Bearer for exactly the
 * reason CLAUDE.md records. The freemium split is about forecast detail, and there is none here.
 *
 * <p>It also does <b>not</b> take the {@code contentDisabled} greying the Plan pane and the day
 * rail take. That treatment marks forecast data that may be stale because the backend is DOWN, and
 * two things make it wrong here: an almanac does not go stale, and a DOWN backend means this pane's
 * own fetch failed, so it is already showing its error state — which says more than a grey wash
 * would.
 *
 * @param {object}   props
 * @param {string}   props.id       the panel's element id, paired with the tab's `aria-controls`
 * @param {boolean}  [props.hidden] true while the other tab is selected
 * @param {string}   props.labelledBy the tab's element id
 * @param {string}   props.status   `idle` | `loading` | `ready` | `error`
 * @param {?Array}   props.events   the wire payload once it has arrived
 * @param {string}   props.todayStr the reader's today, `YYYY-MM-DD`
 * @param {function} props.onRetry  re-runs the fetch after a failure
 */
export default function WindowFirstComingUp({
  id, labelledBy, hidden, status, events, todayStr, onRetry,
}) {
  const rows = useMemo(() => buildComingUpRows(events, todayStr), [events, todayStr]);
  // Nothing today can make this true — see the class comment — so the clause it controls is a
  // guard on a wire enum this arm does not own, not a feature waiting for a caller.
  const hasForecastRow = rows.some((row) => row.kindLabel);

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
      className={hidden ? 'wf-cu-panel hidden' : 'wf-cu-panel'}
      ref={panelRef}
      data-testid="window-first-coming-up"
      // The Plan pane's own padding, matched exactly: the two panes sit in the same slot under the
      // same tab rule, and a different inset would make the frame appear to move on a tab change.
      style={{ padding: '14px 18px 20px' }}
    >
      <div className="wf-cu">
        <div className="wf-cu-head">
          <span className="wf-cu-h">Coming up</span>
          {/* The separator is written here rather than as the mock's `.cuh .d::before`, which is
              the house pattern: a separator in CSS outlives the half it separates. Both halves are
              constants today, but the rule is cheap to keep and the exception is not worth
              explaining twice. */}
          <span className="wf-cu-d" data-testid="coming-up-subtitle">
            {`· dated events worth planning a trip around · next ${ALMANAC_DAYS} days`}
          </span>
        </div>

        {/* One always-mounted live region holding whichever of the three notes applies.
            ALWAYS mounted, and that is the load-bearing half: §5f records that a live region
            inserted in the same commit as its content is unreliably announced, which is why
            `WindowSpotSheet` puts `role="status"` on the element that is there whatever happens
            rather than on the conditional paragraph. Selection follows focus on this bar, so a
            reader arriving by arrow key gets no other signal that the pane is still loading or
            that it failed.

            The empty line is gated on `ready`, not on `rows.length`. "Nothing in the next 90 days"
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

          {status === 'ready' && rows.length === 0 && (
            <p className="wf-cu-note" data-testid="coming-up-empty">
              {`Nothing dated in the next ${ALMANAC_DAYS} days.`}
            </p>
          )}
        </div>

        {/* A list, because it is one: eleven sibling divs give a screen-reader user no boundary
            between one event and the next and no count to navigate by. */}
        {status === 'ready' && rows.length > 0 && (
          <div role="list" data-testid="coming-up-list">
            {rows.map((row) => <WindowComingUpRow key={row.key} row={row} />)}
          </div>
        )}

        {/* The vocabulary, stated once. The first sentence asks the RENDERED SET rather than
            asserting what the plan expects: "every date here is fixed" is a claim about what is on
            screen, and it stops being true the moment a Forecast row appears. Two mutually
            exclusive sentences, each true of the rows above it, beat one sentence with a clause
            bolted on — that shape leaves the false half still printed. */}
        {/* ⚠️ "Fixed in advance", NOT "fixed by orbital mechanics", and the difference is a claim
            this feed cannot support. Two of the five sources do not compute anything orbital:
            `NlcSeasonAlmanacSource` reads a hard-coded 25 May – 10 Aug calendar window, and
            `SolarAlignmentAlmanacSource` uses fixed MonthDay anchors (20 Mar, 21 Jun, 22 Sep,
            21 Dec) rather than a solved equinox instant — §5g records that those anchors are
            approximate and that their accuracy is unverified. What IS true of all seven types is
            that the date was settled before today and nothing about the weather can move it, which
            is also the only part a reader needs. */}
        <p className="wf-cu-foot" data-testid="coming-up-footer">
          {hasForecastRow
            ? 'Dates marked Forecast are weather-driven and firm up about three days ahead; every '
              + 'other date here is fixed in advance and cannot move. '
            : 'Every date here is fixed in advance — as certain three months out as it is tonight, '
              + 'because none of it depends on the weather. '}
          {'What the weather will do on the day is the Plan tab’s question.'}
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
  events: PropTypes.array,
  todayStr: PropTypes.string,
  onRetry: PropTypes.func.isRequired,
};
