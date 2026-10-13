import React from 'react';
import PropTypes from 'prop-types';
import {
  describeSolarRule,
  describeSeconds,
  formatCivilDate,
  formatReferencePoint,
  formatUkDateTime,
  formatUkTime,
} from '../utils/schedulerSolar.js';

/**
 * One day's computed fire, as a row of the cycle's table.
 *
 * @param {object} props
 * @param {string} props.label - "Today" or "Tomorrow"
 * @param {object|null} props.day - the preview's DayFire, or null
 * @param {string} props.testId - test id for the row
 */
function DayRow({ label, day, testId }) {
  if (!day) return null;
  return (
    <tr data-testid={testId}>
      <th scope="row" className="text-left font-normal text-plex-text-muted pr-3 py-0.5">
        {label}
        <span className="block text-plex-text-muted">{formatCivilDate(day.date)}</span>
      </th>
      <td className="pr-3 py-0.5">{day.eventInstant ? formatUkTime(day.eventInstant) : 'n/a'}</td>
      <td className="pr-3 py-0.5">
        {day.fallback ? (
          <span className="text-amber-400">
            Stored cron used{day.fallbackReason ? `: ${day.fallbackReason}` : ''}
          </span>
        ) : (
          <>
            {formatUkTime(day.fireInstant)}
            {day.clamped && <span className="text-amber-400"> (clamped to 00:05 UK)</span>}
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
};

/**
 * One forecast cycle's figures.
 *
 * @param {object} props
 * @param {object} props.job - one entry of the preview's {@code jobs}
 * @param {string} props.displayName - the scheduler row's display name
 */
function CyclePreview({ job, displayName }) {
  const rule = describeSolarRule(job.solarEvent, job.offsetMinutes);
  const isProposed = job.ruleSource === 'PROPOSED';
  const finishInstant =
    job.today && !job.today.fallback && job.today.fireInstant && job.medianDurationSeconds != null
      ? new Date(new Date(job.today.fireInstant).getTime() + job.medianDurationSeconds * 1000)
      : null;
  return (
    <section
      className="border border-plex-border rounded p-3 flex flex-col gap-2"
      data-testid={`solar-panel-${job.jobKey}`}
    >
      <h4 className="text-sm font-semibold text-plex-text">
        {displayName} <span className="font-normal text-plex-text-muted">({job.cycleType})</span>
      </h4>
      <p className="text-xs text-plex-text-secondary" data-testid={`solar-rule-${job.jobKey}`}>
        <span className="text-plex-text">{rule}</span>
        {', never before 00:05 UK. '}
        <span
          className={isProposed ? 'text-amber-400' : 'text-green-400'}
          data-testid={`solar-rule-source-${job.jobKey}`}
        >
          {isProposed
            ? `Proposed rule: this job is still on its ${job.scheduleType || 'unknown'} schedule.`
            : 'Current rule: this job is sun-relative now.'}
        </span>
      </p>

      <table className="text-xs text-plex-text-secondary">
        <thead>
          <tr className="text-plex-text-muted">
            <th scope="col" className="text-left font-normal pr-3">
              Day
            </th>
            <th scope="col" className="text-left font-normal pr-3">
              {job.solarEvent === 'SUNRISE' ? 'Sunrise' : 'Sunset'}
            </th>
            <th scope="col" className="text-left font-normal pr-3">
              Fires
            </th>
          </tr>
        </thead>
        <tbody>
          <DayRow label="Today" day={job.today} testId={`solar-today-${job.jobKey}`} />
          <DayRow label="Tomorrow" day={job.tomorrow} testId={`solar-tomorrow-${job.jobKey}`} />
        </tbody>
      </table>

      <p className="text-xs text-plex-text-secondary" data-testid={`solar-finish-${job.jobKey}`}>
        {finishInstant ? (
          <>
            Expected finish today: about{' '}
            <span className="text-plex-text">{formatUkTime(finishInstant.toISOString())}</span>{' '}
            (trigger plus the median of the last completed cycles,{' '}
            {describeSeconds(job.medianDurationSeconds)}).
          </>
        ) : (
          'Expected finish: not available (no completed cycle of this type to take a median over).'
        )}
      </p>

      <p className="text-xs text-plex-text-secondary" data-testid={`solar-year-${job.jobKey}`}>
        Over the next year the trigger ranges from{' '}
        {job.earliest ? (
          <span className="text-plex-text">
            {job.earliest.fireLocal} UK on {formatCivilDate(job.earliest.date)}
          </span>
        ) : (
          'n/a'
        )}{' '}
        (earliest) to{' '}
        {job.latest ? (
          <span className="text-plex-text">
            {job.latest.fireLocal} UK on {formatCivilDate(job.latest.date)}
          </span>
        ) : (
          'n/a'
        )}{' '}
        (latest).
      </p>
    </section>
  );
}

CyclePreview.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    cycleType: PropTypes.string,
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
  displayName: PropTypes.string.isRequired,
};

/**
 * The Scheduler screen's read-only panel on the two sun-relative forecast cycles
 * (solar-relative-refresh-plan §5).
 *
 * <p>Every figure is read from {@code GET /api/admin/scheduler/solar-preview}, which runs the
 * scheduler's own trigger arithmetic, so this panel cannot disagree with what will fire. The notes
 * at the foot are fixed text from the plan (D1, D3, A4); the safety budget in particular is the
 * plan's figure, not a live read of {@code photocast.pipeline.safety-timeout}.
 *
 * @param {object} props
 * @param {object|null} props.preview - the preview response, or null while it is unavailable
 * @param {boolean} props.failed - true when the preview could not be loaded
 * @param {Object<string,string>} props.displayNames - job key to display name
 */
export default function SolarSchedulePanel({ preview = null, failed = false, displayNames = {} }) {
  return (
    <div
      className="border border-plex-border rounded-lg p-4 flex flex-col gap-3"
      data-testid="solar-panel"
    >
      <div>
        <h3 className="text-sm font-semibold text-plex-text">Sun-relative forecast cycles</h3>
        <p className="text-xs text-plex-text-muted mt-0.5">
          Read-only. Figures come from the scheduler&apos;s own calculation; times are UK local.
        </p>
      </div>

      {!preview && (
        <p className="text-xs text-plex-text-muted" data-testid="solar-panel-unavailable">
          {failed
            ? 'Solar preview unavailable: the request failed. The switch control is disabled until it loads.'
            : 'Loading solar preview...'}
        </p>
      )}

      {preview && (
        <>
          <p className="text-xs text-plex-text-secondary" data-testid="solar-reference">
            Reference point:{' '}
            <span className="text-plex-text">{formatReferencePoint(preview.referencePoint)}</span>{' '}
            (Durham unless configured otherwise). Computed {formatUkDateTime(preview.generatedAt)}.
          </p>
          <div className="flex flex-col gap-3">
            {(preview.jobs || []).map((job) => (
              <CyclePreview
                key={job.jobKey}
                job={job}
                displayName={displayNames[job.jobKey] || job.jobKey}
              />
            ))}
          </div>
        </>
      )}

      <ul
        className="list-disc pl-5 text-xs text-plex-text-secondary flex flex-col gap-1"
        data-testid="solar-notes"
      >
        <li>
          Catch-up after a restart: if a trigger passed while the app was down and no cycle has run
          since, it fires once on startup, but only before 05:00 UK for the morning cycle, or at
          least 90 minutes before sunset for the afternoon cycle. Otherwise it waits for the next
          trigger.
        </li>
        <li>
          Safety budget: waiting for batches and retrying failures share 3 h 30 min from the
          cycle&apos;s first batch submit, so two forecast cycles can never be running at once. This
          is the plan&apos;s figure; the live setting is{' '}
          <span className="font-mono">photocast.pipeline.safety-timeout</span> and is not read here.
        </li>
        <li>
          Accepted: in midsummer the morning cycle fires shortly after midnight UK and the afternoon
          refresh around 17:50 instead of 15:00. A summer morning precompute also means Ask&apos;s
          &ldquo;this morning&rdquo; answer is withheld from about 04:30 until the evening
          precompute.
        </li>
        <li>
          A falling-back day (shown above) uses the job&apos;s stored cron, which a sun-relative job
          always keeps. Switching back to a fixed time is the same control on the job&apos;s card.
        </li>
      </ul>
    </div>
  );
}

SolarSchedulePanel.propTypes = {
  preview: PropTypes.shape({
    referencePoint: PropTypes.object,
    generatedAt: PropTypes.string,
    jobs: PropTypes.array,
  }),
  failed: PropTypes.bool,
  displayNames: PropTypes.objectOf(PropTypes.string),
};
