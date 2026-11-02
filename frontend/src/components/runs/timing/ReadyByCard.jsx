import PropTypes from 'prop-types';
import { formatClockUk } from '../../../utils/runsView.js';
import { SCHEDULER_HREF, formatMinutes } from '../../../utils/runsTiming.js';
import { isCycleType } from '../../../utils/runsDay.js';

const CARD = 'rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3';

/** The card's note: small, quiet. */
function Note({ children, testId }) {
  return <p className="mt-3 text-[12px] leading-relaxed text-plex-text-secondary" data-testid={testId}>{children}</p>;
}

Note.propTypes = { children: PropTypes.node.isRequired, testId: PropTypes.string.isRequired };

/** The Sunrise line: the clock and the slack at the 9-in-10 line, "short" when it is negative. */
function sunriseText(timing) {
  const clock = formatClockUk(timing.sunriseToday);
  const slack = timing.slackAtP90Minutes;
  if (slack == null) return `${clock} today`;
  return slack >= 0
    ? `${clock} today · ${formatMinutes(slack)} of slack at the 9-in-10 line`
    : `${clock} today · ${formatMinutes(-slack)} short at the 9-in-10 line`;
}

/**
 * The left-hand card of the Timing view's lower row when there is no sunset planner. Four cases, all from the served
 * response (nothing is recomputed here):
 * <ul>
 *   <li>Overnight and Evening cycles: "When results are ready", the facts (Starts, Typical, 9 in 10 nights, Worst and,
 *       for Overnight, Sunrise today with the slack at the 9-in-10 line) and the fixed-time note. With no fire today
 *       the times are not served, and the card says so.</li>
 *   <li>Afternoon refresh without a sunset trigger (it is on a cron time, or off sunrise): the planner has nothing to
 *       replay, so the card says it runs at a fixed time and links to the Scheduler.</li>
 *   <li>Ask ready answers and Daily briefing: they run after a cycle, so the card points to "Until answers ready".</li>
 *   <li>Sky eval: a test harness, not part of any cycle.</li>
 * </ul>
 *
 * @param {object} props
 * @param {object} props.timing the served {@code RunTimingResponse}
 */
export default function ReadyByCard({ timing }) {
  const { type, typeLabel } = timing;

  if (type === 'AFTERNOON') {
    const sunrise = timing.trigger?.solarEvent === 'SUNRISE';
    return (
      <section className={CARD} data-testid="runs-ready-card">
        <h3 className="text-[13px] font-semibold text-plex-text">Plan around sunset</h3>
        <Note testId="runs-ready-fixed">
          {sunrise
            ? `${typeLabel} starts relative to sunrise, not sunset, so there is no sunset start to plan.`
            : `${typeLabel} runs at a fixed time, so there is no sun-relative trigger to plan around.`}{' '}
          Switch it to a sunset trigger in the Scheduler to use the planner.
        </Note>
        <a href={SCHEDULER_HREF} className="btn-secondary mt-3 inline-flex items-center text-xs max-[639px]:min-h-11" data-testid="runs-ready-scheduler">
          Open in Scheduler
        </a>
      </section>
    );
  }

  if (!isCycleType(type)) {
    const chained = type === 'ASK_READY' || type === 'BRIEFING';
    return (
      <section className={CARD} data-testid="runs-ready-card">
        <h3 className="text-[13px] font-semibold text-plex-text">Where it sits in the chain</h3>
        <p className="mt-3 text-[13px] leading-relaxed text-plex-text-secondary" data-testid="runs-ready-explainer">
          {chained ? (
            <>
              {typeLabel} runs after each cycle finishes, so its finish time depends on the cycle before it. Pick a cycle
              above and use <strong className="font-semibold text-plex-text">Until answers ready</strong> to see the whole chain.
            </>
          ) : (
            `${typeLabel} is the test harness: it runs on its own and is not part of any cycle, so there is no ready-by time to show.`
          )}
        </p>
      </section>
    );
  }

  const fixed = timing.startsAt != null;
  return (
    <section className={CARD} data-testid="runs-ready-card">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h3 className="text-[13px] font-semibold text-plex-text">When results are ready</h3>
        {fixed && <span className="font-mono text-[11px] text-plex-text-secondary" data-testid="runs-ready-trigger">Fixed time {'·'} {formatClockUk(timing.startsAt)}</span>}
      </div>
      {fixed ? (
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-[12.5px]" data-testid="runs-ready-facts">
          <dt className="text-plex-text-secondary">Starts</dt>
          <dd className="font-mono text-plex-text">{formatClockUk(timing.startsAt)}</dd>
          {timing.typicalReadyAt && (
            <>
              <dt className="text-plex-text-secondary">Typical</dt>
              <dd className="font-mono text-plex-text">ready {formatClockUk(timing.typicalReadyAt)}</dd>
            </>
          )}
          {timing.p90ReadyAt && (
            <>
              <dt className="text-plex-text-secondary">9 in 10 nights</dt>
              <dd className="font-mono text-plex-text">ready by {formatClockUk(timing.p90ReadyAt)}</dd>
            </>
          )}
          {timing.worstReadyAt && (
            <>
              <dt className="text-plex-text-secondary">Worst</dt>
              <dd className="font-mono text-plex-text">ready {formatClockUk(timing.worstReadyAt)}</dd>
            </>
          )}
          {timing.sunriseToday && (
            <>
              <dt className="text-plex-text-secondary">Sunrise</dt>
              <dd className="font-mono text-plex-text" data-testid="runs-ready-sunrise">{sunriseText(timing)}</dd>
            </>
          )}
        </dl>
      ) : (
        <Note testId="runs-ready-nofire">No {typeLabel} is scheduled today, so there are no times to show.</Note>
      )}
      {fixed && timing.readyBasis === 'cycle' && (
        <Note testId="runs-ready-basis">
          No run has a linked Ask ready answers run yet, so these times use the cycle{'’'}s own length.
        </Note>
      )}
      <Note testId="runs-ready-note">
        This cycle runs at a fixed time, so there is no sun-relative trigger to tune. The planner appears for Afternoon refresh.
      </Note>
    </section>
  );
}

ReadyByCard.propTypes = {
  timing: PropTypes.shape({
    type: PropTypes.string.isRequired,
    typeLabel: PropTypes.string.isRequired,
    trigger: PropTypes.object,
    startsAt: PropTypes.string,
    typicalReadyAt: PropTypes.string,
    p90ReadyAt: PropTypes.string,
    worstReadyAt: PropTypes.string,
    readyBasis: PropTypes.string,
    sunriseToday: PropTypes.string,
    slackAtP90Minutes: PropTypes.number,
  }).isRequired,
};
