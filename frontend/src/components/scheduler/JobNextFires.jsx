import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import {
  describeSeconds,
  describeSolarRule,
  formatCivilDate,
  formatReferencePoint,
  formatUkDateTime,
  formatUkTime,
} from '../../utils/schedulerSolar.js';
import { STATUS_ACTIVE } from '../../utils/schedulerView.js';

/** An amber note: a fallback or clamped day, a stale preview. */
const AMBER = 'text-verdict-marginal';

/**
 * The reference point a sun-relative time is worked out for: "Durham, unless configured otherwise" and its
 * coordinates, then when the preview was computed, small. (The server serves the coordinates only, so the place is the
 * plan's default, not a lookup.)
 *
 * @param {object} props
 * @param {{referencePoint: object, generatedAt: string}} props.preview the solar preview
 */
export function ReferenceValue({ preview }) {
  return (
    <span data-testid="solar-reference">
      Durham, unless configured otherwise {'·'}{' '}
      <span className="font-mono text-[12.5px]">{formatReferencePoint(preview.referencePoint)}</span>
      <span className="block text-xs text-plex-text-muted">Computed {formatUkDateTime(preview.generatedAt)}</span>
    </span>
  );
}

ReferenceValue.propTypes = {
  preview: PropTypes.shape({ referencePoint: PropTypes.object, generatedAt: PropTypes.string }).isRequired,
};

/**
 * One day's computed fire, as a row of the Today / Tomorrow table. A fire that has already passed says "ran" under it,
 * but only where it is true: a sun-relative job that is active (a proposed rule never fired, and a paused job did not).
 *
 * @param {object} props
 * @param {string} props.label "Today" or "Tomorrow"
 * @param {object|null} props.day the preview's DayFire, or null
 * @param {string} props.testId the row's test id
 * @param {Date} props.now the real clock (R-D7)
 * @param {boolean} props.mayHaveRun whether a past fire means the job ran
 */
function DayRow({ label, day, testId, now, mayHaveRun }) {
  if (!day) return null;
  const ran = mayHaveRun && !day.fallback && day.fireInstant && new Date(day.fireInstant).getTime() <= now.getTime();
  return (
    <tr data-testid={testId} className="border-t border-plex-border/60 first:border-t-0">
      <th scope="row" className="py-1.5 pl-3 pr-3 text-left font-normal text-plex-text">
        {label}
        <span className="block text-xs text-plex-text-muted">{formatCivilDate(day.date)}</span>
      </th>
      <td className="py-1.5 pr-3 font-mono text-[12.5px]">{day.eventInstant ? formatUkTime(day.eventInstant) : 'n/a'}</td>
      <td className="py-1.5 pr-3 font-mono text-[12.5px]">
        {day.fallback ? (
          <span className={`${AMBER} [overflow-wrap:anywhere]`}>
            Stored cron used{day.fallbackReason ? `: ${day.fallbackReason}` : ''}
          </span>
        ) : (
          <>
            {formatUkTime(day.fireInstant)}
            {day.clamped && <span className={AMBER}> (clamped to 00:05 UK)</span>}
            {ran && <span className="block text-xs text-plex-text-muted" data-testid={`${testId}-ran`}>ran</span>}
          </>
        )}
      </td>
    </tr>
  );
}

DayRow.propTypes = {
  label: PropTypes.string.isRequired,
  day: PropTypes.shape({
    date: PropTypes.string,
    eventInstant: PropTypes.string,
    fireInstant: PropTypes.string,
    clamped: PropTypes.bool,
    fallback: PropTypes.bool,
    fallbackReason: PropTypes.string,
  }),
  testId: PropTypes.string.isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  mayHaveRun: PropTypes.bool.isRequired,
};

/**
 * One forecast cycle's figures from the solar preview: for a job still on a fixed time the plan's proposed rule, then
 * the Today / Tomorrow table, the expected finish and the year's range.
 *
 * @param {object} props
 * @param {object} props.preview one entry of the preview's {@code jobs}
 * @param {object|null} props.reference the whole preview, when the reference point is to be named here
 * @param {string} props.status the served job's status
 */
function CyclePreview({ preview: job, reference = null, status }) {
  const now = new Date();
  const rule = describeSolarRule(job.solarEvent, job.offsetMinutes);
  const isProposed = job.ruleSource === 'PROPOSED';
  const finishInstant =
    job.today && !job.today.fallback && job.today.fireInstant && job.medianDurationSeconds != null
      ? new Date(new Date(job.today.fireInstant).getTime() + job.medianDurationSeconds * 1000)
      : null;
  const rows = [];
  if (reference) rows.push({ label: 'Reference', value: <ReferenceValue preview={reference} /> });
  rows.push({
    label: 'Expected finish',
    testId: `solar-finish-${job.jobKey}`,
    value: finishInstant
      ? `about ${formatUkTime(finishInstant.toISOString())} · trigger plus the median of the last completed cycles, ${describeSeconds(job.medianDurationSeconds)}`
      : 'Not available (no completed cycle of this type to take a median over).',
  });
  rows.push({
    label: 'Over the year',
    testId: `solar-year-${job.jobKey}`,
    value: `${job.earliest ? `${job.earliest.fireLocal} UK on ${formatCivilDate(job.earliest.date)}` : 'n/a'} (earliest) to ${
      job.latest ? `${job.latest.fireLocal} UK on ${formatCivilDate(job.latest.date)}` : 'n/a'} (latest)`,
  });
  return (
    <div className="flex flex-col gap-3" data-testid={`solar-panel-${job.jobKey}`}>
      {isProposed && (
        <div className="flex flex-col gap-1">
          <p className="font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted" data-testid={`solar-proposed-label-${job.jobKey}`}>
            Proposed rule
          </p>
          <p className="text-xs text-plex-text-secondary" data-testid={`solar-panel-rule-${job.jobKey}`}>
            <span className="text-plex-text">{rule}</span>
            {', never before 00:05 UK. '}
            <span className={AMBER} data-testid={`solar-rule-source-${job.jobKey}`}>
              {`Proposed rule: this job is still on its ${job.scheduleType || 'unknown'} schedule.`}
            </span>
          </p>
        </div>
      )}

      <div className="overflow-hidden rounded border border-plex-border">
        <table className="w-full text-[13px] text-plex-text-secondary">
          <thead>
            <tr className="border-b border-plex-border font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted">
              <th scope="col" className="py-1.5 pl-3 pr-3 text-left font-normal">
                <span className="sr-only">Day</span>
              </th>
              <th scope="col" className="py-1.5 pr-3 text-left font-normal">
                {job.solarEvent === 'SUNRISE' ? 'Sunrise' : 'Sunset'}
              </th>
              <th scope="col" className="py-1.5 pr-3 text-left font-normal">
                Fires
              </th>
            </tr>
          </thead>
          <tbody>
            <DayRow label="Today" day={job.today} testId={`solar-today-${job.jobKey}`} now={now} mayHaveRun={!isProposed && status === STATUS_ACTIVE} />
            <DayRow label="Tomorrow" day={job.tomorrow} testId={`solar-tomorrow-${job.jobKey}`} now={now} mayHaveRun={!isProposed && status === STATUS_ACTIVE} />
          </tbody>
        </table>
      </div>

      <KvList items={rows} />
    </div>
  );
}

CyclePreview.propTypes = {
  preview: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    scheduleType: PropTypes.string,
    solarEvent: PropTypes.string,
    offsetMinutes: PropTypes.number,
    ruleSource: PropTypes.string,
    today: PropTypes.object,
    tomorrow: PropTypes.object,
    earliest: PropTypes.object,
    latest: PropTypes.object,
    medianDurationSeconds: PropTypes.number,
  }).isRequired,
  reference: PropTypes.object,
  status: PropTypes.string.isRequired,
};

/**
 * The panel's Next fires section for a job that is, or may become, sun-relative (solar-relative-refresh-plan §5).
 *
 * <p>Every time shown is read from {@code GET /api/admin/scheduler/solar-preview}, which runs the scheduler's own
 * trigger arithmetic, so this section cannot disagree with what will fire. The one figure it derives itself is the
 * expected finish: the preview's trigger plus its median cycle duration. When a refresh fails while an older preview is
 * held, the held figures stay on screen under a notice naming their age; with no preview at all the section says it is
 * loading or unavailable. A cycle job still on a fixed time shows the plan's proposed rule here, which is what the
 * admin reads before switching it.
 *
 * @param {object} props
 * @param {string} props.jobKey the job
 * @param {string} props.status the served job's status ("ran" shows only under an active sun-relative job)
 * @param {object|null} props.preview the preview response, or null while it is unavailable
 * @param {boolean} props.failed true when the preview could not be loaded
 * @param {boolean} [props.showReference] whether to name the reference point here. A sun-relative job names it in its
 *   Schedule section; a cycle job still on a fixed time has no such row, and what it would be timed against is read here
 */
export default function JobNextFires({ jobKey, status, preview = null, failed = false, showReference = false }) {
  const entry = preview?.jobs?.find((p) => p.jobKey === jobKey) ?? null;
  return (
    <PanelSection label="Next fires" testId="scheduler-panel-next-fires">
      {preview && failed && (
        <p className={`mb-2 text-xs ${AMBER}`} role="status" data-testid="solar-panel-stale">
          Couldn&apos;t refresh the preview &mdash; figures as of {formatUkDateTime(preview.generatedAt)}.
          Switching to sun-relative is disabled until a refresh succeeds.
        </p>
      )}
      {!preview && (
        <p className="text-xs text-plex-text-secondary" data-testid="solar-panel-unavailable">
          {failed
            ? 'Solar preview unavailable: the request failed. The switch control is disabled until it loads.'
            : 'Loading solar preview…'}
        </p>
      )}
      {preview && !entry && (
        <p className="text-xs text-plex-text-secondary" data-testid="solar-panel-no-entry">
          The preview has no figures for this job.
        </p>
      )}
      {entry && <CyclePreview preview={entry} reference={showReference ? preview : null} status={status} />}
    </PanelSection>
  );
}

JobNextFires.propTypes = {
  jobKey: PropTypes.string.isRequired,
  status: PropTypes.string.isRequired,
  preview: PropTypes.shape({
    referencePoint: PropTypes.object,
    generatedAt: PropTypes.string,
    jobs: PropTypes.array,
  }),
  failed: PropTypes.bool,
  showReference: PropTypes.bool,
};

/**
 * The "How sun-relative timing works" disclosure: the four notes from the old solar panel. The notes are fixed text from
 * the plan (D1, D3, A4); the safety budget in particular is the plan's figure, not a live read of
 * {@code photocast.pipeline.safety-timeout}. The fourth note's last sentence differs from the old panel's: it points at
 * the "Switch back to a fixed time…" link in Schedule above, because there is no job card to refer to any more. The
 * body is always rendered, hidden until opened, so {@code aria-controls} always names an element.
 *
 * @param {object} props
 * @param {string} props.jobKey the open job, so each panel's disclosure has its own ids
 */
export function SunTimingNotes({ jobKey }) {
  const [open, setOpen] = useState(false);
  const bodyId = `solar-notes-body-${jobKey}`;
  return (
    <section className="border-b border-plex-border px-[18px] py-1 max-[639px]:px-4" data-testid="scheduler-panel-timing">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((value) => !value)}
        className="flex min-h-11 w-full cursor-pointer items-center justify-between gap-3 text-left text-[13px] text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
        data-testid="solar-notes-toggle"
      >
        How sun-relative timing works
        <span className="text-plex-text-muted" aria-hidden="true">{open ? '▴' : '▾'}</span>
      </button>
      <div id={bodyId} hidden={!open} data-testid="solar-notes-body">
        <ul
          className="mb-3 flex list-disc flex-col gap-1 pl-5 text-xs text-plex-text-secondary"
          data-testid="solar-notes"
        >
          <li>
            Catch-up after a restart: if a trigger passed while the app was down and no cycle has run
            since, it fires once on startup, but only before 05:00 UK for the morning cycle, or at
            least 90 minutes before sunset for the afternoon cycle. Otherwise it waits for the next
            trigger.
          </li>
          <li>
            Safety budget: waiting for batches and retrying failures share one budget, which runs
            from the cycle&apos;s first batch submit: 3 h 30 min for the overnight and afternoon
            cycles, 2 h for the evening sunrise refresh. From late May to late July the afternoon
            cycle can still be finishing when the evening one starts; their briefing builds then run
            one after the other. These are the plan&apos;s figures; the live settings are{' '}
            <span className="break-all font-mono">photocast.pipeline.safety-timeout</span> and{' '}
            <span className="break-all font-mono">photocast.pipeline.evening-safety-timeout</span>,
            and neither is read here.
          </li>
          <li>
            Accepted: in midsummer the morning cycle fires shortly after midnight UK and the afternoon
            refresh around 17:50 instead of 15:00. A summer morning precompute also means Ask&apos;s
            &ldquo;this morning&rdquo; answer is withheld from about 04:30 until the evening
            precompute.
          </li>
          <li>
            A falling-back day (shown above) uses the job&apos;s stored cron, which a sun-relative job
            always keeps. Switching back to a fixed time is the &ldquo;Switch back to a fixed
            time&hellip;&rdquo; link in Schedule above.
          </li>
        </ul>
      </div>
    </section>
  );
}

SunTimingNotes.propTypes = { jobKey: PropTypes.string.isRequired };
