import { useEffect, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import ErrorBanner from '../../shared/ErrorBanner.jsx';
import useSchedulerDay from '../../../hooks/useSchedulerDay.js';
import { buildRows, dayWord, needsALook, summaryCounts } from '../../../utils/schedulerDay.js';
import { DayLegend, DayNav, DaySummaryBar } from './DayControls.jsx';
import { NeedsALook, NotOnThisDay } from './DayLists.jsx';
import DayPicture from './DayPicture.jsx';

/**
 * The Day's jobs tab (scheduler-redesign-plan.md S5a): the day navigation, the outcome summary bar and the legend, the
 * day picture of the scheduled jobs, "Needs a look" and "Not on this day". Only the active tab is mounted, so this
 * owns the day's data and its 30-second poll ({@link useSchedulerDay}); the job list ({@code jobs}, which the view
 * already loads) supplies each row's schedule type, paused state and Claude flag.
 *
 * <p>Repeating jobs are not drawn here yet (S5b adds their section, the watchdog windows, dependency arrows and the
 * close-up): their failures reach "Needs a look" and their tally the summary bar.
 *
 * @param {object} props
 * @param {Array<object>} props.jobs the served jobs list
 * @param {(jobKey: string) => void} props.onSelect opens a job's panel
 * @param {(word: string) => void} [props.onDayWord] told the viewed day's word ("Today", "Fri 30 Oct") for the tab's sub-label
 */
export default function DayJobsTab({ jobs, onSelect, onDayWord = undefined }) {
  const rootRef = useRef(null);
  const day = useSchedulerDay(rootRef);
  const { date, today, data } = day;

  const jobsByKey = useMemo(() => Object.fromEntries(jobs.map((job) => [job.jobKey, job])), [jobs]);
  const rows = useMemo(() => buildRows(data, jobs), [data, jobs]);
  const look = useMemo(() => needsALook(data), [data]);
  const counts = useMemo(() => summaryCounts(data), [data]);
  const word = dayWord(date, today);

  useEffect(() => {
    onDayWord?.(word);
  }, [word, onDayWord]);

  const empty = data != null && rows.length === 0;

  return (
    <div ref={rootRef} className="flex min-w-0 flex-col gap-3" data-testid="scheduler-day">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <DayNav
          date={date}
          today={today}
          canPrev={day.canPrev}
          canNext={day.canNext}
          onPrev={day.goPrev}
          onNext={day.goNext}
          onToday={day.goToday}
        />
        <p className="font-mono text-[11px] text-plex-text-muted" data-testid="scheduler-day-zone">
          {data ? `UK time (${data.zoneLabelAtStart === data.zoneLabelAtEnd ? data.zoneLabelAtStart : `${data.zoneLabelAtStart} to ${data.zoneLabelAtEnd}`})` : 'UK time'}
        </p>
      </div>

      <ErrorBanner message={day.error} data-testid="scheduler-day-error" />

      {day.loading && (
        <p className="py-6 text-sm text-plex-text-secondary" role="status" data-testid="scheduler-day-loading">
          Loading the day…
        </p>
      )}

      {data && (
        <>
          <DaySummaryBar counts={counts} tally={data.repeatingTally ?? null} date={date} today={today} />
          <DayLegend />
          {empty ? (
            <p className="py-6 text-sm text-plex-text-secondary" data-testid="scheduler-day-empty">
              No jobs are scheduled on this day.
            </p>
          ) : (
            <DayPicture day={data} rows={rows} nowMs={day.nowMs} onSelect={onSelect} />
          )}
          <NeedsALook look={look} jobsByKey={jobsByKey} date={date} onSelect={onSelect} />
          <NotOnThisDay
            jobs={data.notOnThisDay ?? []}
            date={date}
            today={today}
            onSelect={onSelect}
            now={new Date(day.nowMs)}
          />
        </>
      )}
    </div>
  );
}

DayJobsTab.propTypes = {
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  onSelect: PropTypes.func.isRequired,
  onDayWord: PropTypes.func,
};
