import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import ErrorBanner from '../../shared/ErrorBanner.jsx';
import Segmented from '../../data/Segmented.jsx';
import useSchedulerDay from '../../../hooks/useSchedulerDay.js';
import {
  ZOOM_CLOSE_UP,
  ZOOM_DAY,
  buildRows,
  dayWord,
  needsALook,
  readZoom,
  summaryCounts,
  writeZoom,
} from '../../../utils/schedulerDay.js';
import { buildLinks, buildRepeatingRows } from '../../../utils/schedulerDayOverlays.js';
import { DayLegend, DayNav, DaySummaryBar } from './DayControls.jsx';
import { NeedsALook, NotOnThisDay } from './DayLists.jsx';
import DayPicture from './DayPicture.jsx';

/** The zoom control's two buttons. */
const ZOOM_OPTIONS = [
  { value: ZOOM_DAY, label: 'Whole day' },
  { value: ZOOM_CLOSE_UP, label: '02:00–05:00 close-up' },
];

/**
 * The Day's jobs tab (scheduler-redesign-plan.md S5a, S5b): the day navigation, the outcome summary bar and the legend, the
 * day picture of the scheduled jobs, "Needs a look" and "Not on this day". Only the active tab is mounted, so this
 * owns the day's data and its 30-second poll ({@link useSchedulerDay}); the job list ({@code jobs}, which the view
 * already loads) supplies each row's schedule type, paused state and Claude flag. Until that list has loaded the rows
 * are neutral (no colour, no £), and a failed load shows the list's error banner here too.
 *
 * <p>Focus: the panel's close returns focus to the control that opened it (a row label, a Needs a look row, a Not on
 * this day item), or to the page heading when that control is gone.
 *
 * <p>S5b: the repeating jobs have their own section under the scheduled rows, the forecast cycles their watchdog
 * windows, and the links the catalogue declares are drawn as arrows (a chip toggles them, on by default). "Whole day" or
 * the "02:00-05:00 close-up" is chosen with a segmented control and remembered in {@code localStorage} (never in the
 * address). The selected job's arrows turn gold and the rows unrelated to it dim.
 *
 * @param {object} props
 * @param {Array<object>} props.jobs the served jobs list (empty until it has loaded)
 * @param {string|null} [props.jobsError] the jobs list's error, shown as a banner
 * @param {string|null} [props.selectedKey] the job whose panel is open, for returning focus when it closes
 * @param {(jobKey: string) => void} props.onSelect opens a job's panel
 * @param {(word: string) => void} [props.onDayWord] told the viewed day's word ("Today", "Fri 30 Oct") for the tab's sub-label
 * @param {(reload: (() => Promise<void>)|null) => void} [props.registerReload] given this tab's day reload, so the view
 *   can re-read the day after a pause, resume, run or schedule save
 */
export default function DayJobsTab({
  jobs, jobsError = null, selectedKey = null, onSelect, onDayWord = undefined, registerReload = undefined,
}) {
  const rootRef = useRef(null);
  const opener = useRef(null);
  const wasOpen = useRef(false);
  const day = useSchedulerDay(rootRef);
  const { date, today, data } = day;

  const jobsByKey = useMemo(() => Object.fromEntries(jobs.map((job) => [job.jobKey, job])), [jobs]);
  const rows = useMemo(() => buildRows(data, jobs), [data, jobs]);
  const repeatingRows = useMemo(() => buildRepeatingRows(data, jobs), [data, jobs]);
  const links = useMemo(
    () => buildLinks(jobs, [...rows, ...repeatingRows].map((row) => row.jobKey)),
    [jobs, rows, repeatingRows],
  );
  const [zoom, setZoom] = useState(readZoom);
  const [arrows, setArrows] = useState(true);
  const chooseZoom = useCallback((value) => {
    setZoom(value);
    writeZoom(value);
  }, []);
  const look = useMemo(() => needsALook(data), [data]);
  const counts = useMemo(() => summaryCounts(data), [data]);
  const word = dayWord(date, today);

  useEffect(() => {
    onDayWord?.(word);
  }, [word, onDayWord]);

  useEffect(() => {
    registerReload?.(day.reload);
    return () => registerReload?.(null);
  });

  const handleSelect = useCallback((jobKey, control) => {
    opener.current = control ?? rootRef.current?.querySelector(`[data-testid="scheduler-row-${jobKey}"]`) ?? null;
    onSelect(jobKey);
  }, [onSelect]);

  // When the panel closes, focus goes back to what opened it; if that is gone, to the heading.
  useEffect(() => {
    const open = selectedKey != null;
    if (wasOpen.current && !open && opener.current) {
      const control = opener.current;
      if (control.isConnected) {
        control.focus({ preventScroll: true });
      } else {
        const heading = document.querySelector('[data-testid="scheduler-heading"]');
        heading?.focus({ preventScroll: true });
      }
      opener.current = null;
    }
    wasOpen.current = open;
  }, [selectedKey]);

  const empty = data != null && rows.length === 0 && repeatingRows.length === 0;

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
        <Segmented label="Zoom" options={ZOOM_OPTIONS} value={zoom} onChange={chooseZoom} testId="scheduler-day-zoom" />
        <p className="font-mono text-[11px] text-plex-text-muted" data-testid="scheduler-day-zone">
          {data ? `UK time (${data.zoneLabelAtStart === data.zoneLabelAtEnd ? data.zoneLabelAtStart : `${data.zoneLabelAtStart} to ${data.zoneLabelAtEnd}`})` : 'UK time'}
        </p>
      </div>

      {day.notice && (
        <p className="text-xs text-plex-text-secondary" role="status" data-testid="scheduler-day-notice">{day.notice}</p>
      )}
      <ErrorBanner message={jobsError} data-testid="scheduler-error" />
      <ErrorBanner message={day.error} data-testid="scheduler-day-error" />

      {day.loading && (
        <p className="py-6 text-sm text-plex-text-secondary" role="status" data-testid="scheduler-day-loading">
          Loading the day…
        </p>
      )}

      {data && (
        <>
          <DaySummaryBar counts={counts} tally={data.repeatingTally ?? null} date={date} today={today} />
          <DayLegend arrows={arrows} onToggleArrows={() => setArrows((on) => !on)} />
          {empty ? (
            <p className="py-6 text-sm text-plex-text-secondary" data-testid="scheduler-day-empty">
              No jobs are scheduled on this day.
            </p>
          ) : (
            <DayPicture
              day={data}
              rows={rows}
              repeatingRows={repeatingRows}
              links={links}
              showArrows={arrows}
              selectedKey={selectedKey}
              zoom={zoom}
              nowMs={day.nowMs}
              onSelect={handleSelect}
            />
          )}
          <NeedsALook look={look} jobsByKey={jobsByKey} date={date} onSelect={handleSelect} />
          <NotOnThisDay
            jobs={data.notOnThisDay ?? []}
            date={date}
            today={today}
            onSelect={handleSelect}
            now={new Date(day.nowMs)}
          />
        </>
      )}
    </div>
  );
}

DayJobsTab.propTypes = {
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  jobsError: PropTypes.string,
  selectedKey: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
  onDayWord: PropTypes.func,
  registerReload: PropTypes.func,
};
