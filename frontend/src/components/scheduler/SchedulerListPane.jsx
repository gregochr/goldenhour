import PropTypes from 'prop-types';
import DataGrid from '../data/DataGrid.jsx';
import StatusDot from '../data/StatusDot.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow, SortChip } from '../data/Chips.jsx';
import { DataTools } from '../data/DataTools.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import { runStatusView } from '../../utils/runStatus.js';
import { formatRunStarted } from '../../utils/runsView.js';
import {
  SCHEDULE_CRON,
  SCHEDULE_FIXED_DELAY,
  SCHEDULE_SOLAR,
  STATUS_ACTIVE,
  STATUS_OFF,
  STATUS_PAUSED,
  countByType,
  describeSchedule,
  formatNextFire,
  getUKTimezoneLabel,
  jobStatusView,
  relativeTime,
} from '../../utils/schedulerView.js';

/** The type chips, in order; Fixed delay shows only when some job has that type. */
const TYPE_CHIPS = [
  { value: 'all', label: 'All' },
  { value: SCHEDULE_SOLAR, label: 'Sun-relative' },
  { value: SCHEDULE_CRON, label: 'Cron' },
  { value: SCHEDULE_FIXED_DELAY, label: 'Fixed delay', onlyIfAny: true },
];

/** The status chips: each narrows the list to jobs with that status, and pressing it again clears it. */
const STATUS_CHIPS = [
  { value: STATUS_PAUSED, label: 'Paused' },
  { value: STATUS_OFF, label: 'Off in config' },
];

/** The sortable columns, for the sort chip a card-mode list uses in place of the headers. */
const SORT_OPTIONS = [
  { key: 'next', label: 'Next' },
  { key: 'name', label: 'Job' },
];

/** The Status cell: the served status as a dot and its words. */
function StatusCell({ job }) {
  const status = jobStatusView(job.status);
  return (
    <StatusDot tone={status.tone} tintText testId={`scheduler-status-${job.jobKey}`}>
      {status.label}
    </StatusDot>
  );
}

StatusCell.propTypes = { job: PropTypes.object.isRequired };

/**
 * The Last run cell: the last run's time and status when the server can attribute a run to the job; else the time the
 * scheduler last fired it, marked "fired"; else an em dash. Clock reads are the real clock (R-D7).
 */
function LastRunCell({ job }) {
  const testId = `scheduler-last-${job.jobKey}`;
  if (job.lastRun) {
    const status = runStatusView(job.lastRun.status);
    return (
      <span className="flex flex-col gap-0.5" data-testid={testId}>
        <span className="font-mono text-[12px] text-plex-text-secondary">{formatRunStarted(job.lastRun.startedAt)}</span>
        <StatusDot tone={status.tone} halo={status.halo} tintText>{status.label}</StatusDot>
      </span>
    );
  }
  if (job.lastFireTime) {
    return (
      <span className="flex flex-col gap-0.5" data-testid={testId}>
        <span className="font-mono text-[12px] text-plex-text-secondary">{formatRunStarted(job.lastFireTime)}</span>
        <span className="text-xs text-plex-text-muted" data-testid={`last-fire-${job.jobKey}`}>
          fired {'·'} {relativeTime(job.lastFireTime)}
        </span>
      </span>
    );
  }
  return <span className="text-plex-text-muted" data-testid={testId}>{'—'}</span>;
}

LastRunCell.propTypes = { job: PropTypes.object.isRequired };

/** The Next cell: the UK time, with how far off it is under it; paused and off jobs have none. */
function NextCell({ job }) {
  if (job.status !== STATUS_ACTIVE || !job.nextFireTime) {
    return <span className="text-plex-text-muted" data-testid={`scheduler-next-${job.jobKey}`}>{'—'}</span>;
  }
  return (
    <span className="flex flex-col gap-0.5" data-testid={`scheduler-next-${job.jobKey}`}>
      <span className="font-mono text-[12.5px]">{formatNextFire(job.nextFireTime)}</span>
      <span className="text-xs text-plex-text-muted" data-testid={`next-fire-${job.jobKey}`}>
        {relativeTime(job.nextFireTime, { future: true })}
      </span>
    </span>
  );
}

NextCell.propTypes = { job: PropTypes.object.isRequired };

/**
 * The grid's columns (plan §5.5): Job 1.6fr (name, then the description), Schedule 1fr (words, then the type), Next
 * .8fr (mono time, then how far off), Last run .9fr (secondary), Status .7fr. No cell holds a control; a row opens its
 * panel. In card mode line 1 is the name and the status, line 2 "{schedule} · {next}".
 */
const COLUMNS = [
  {
    key: 'job',
    header: 'Job',
    width: '1.6fr',
    card: 'title',
    sortKey: 'name',
    cell: (job) => (
      <>
        <span className="block truncate font-medium" title={job.displayName} data-testid={`scheduler-name-${job.jobKey}`}>
          {job.displayName}
        </span>
        <span className="block truncate text-xs text-plex-text-secondary" title={job.description}>
          {job.description}
        </span>
      </>
    ),
  },
  {
    key: 'schedule',
    header: 'Schedule',
    width: '1fr',
    cell: (job) => {
      const schedule = describeSchedule(job);
      return (
        <>
          <span className="block text-[13px]" data-testid={`scheduler-schedule-${job.jobKey}`}>{schedule.words}</span>
          <span className="block text-[11.5px] text-plex-text-muted">
            {schedule.recognised ? schedule.type : `Unrecognised schedule type: ${schedule.type}`}
          </span>
        </>
      );
    },
  },
  {
    key: 'next',
    header: 'Next',
    width: '.8fr',
    sortKey: 'next',
    cell: (job) => <NextCell job={job} />,
  },
  {
    key: 'last',
    header: 'Last run',
    width: '.9fr',
    secondary: true,
    cell: (job) => <LastRunCell job={job} />,
  },
  {
    key: 'status',
    header: 'Status',
    width: '.7fr',
    card: 'status',
    cell: (job) => <StatusCell job={job} />,
  },
  {
    key: 'card-summary',
    card: 'summary',
    cardOnly: true,
    cell: (job) => {
      const active = job.status === STATUS_ACTIVE && job.nextFireTime;
      const next = active
        ? `next ${formatNextFire(job.nextFireTime)} (${relativeTime(job.nextFireTime, { future: true })})`
        : 'next \u2014';
      return (
        <span className="block truncate text-xs text-plex-text-secondary" data-testid={`scheduler-card-line-${job.jobKey}`}>
          {`${describeSchedule(job).words} \u00b7 ${next}`}
        </span>
      );
    },
  },
];

/** A row's accessible name, reading the whole row: "Tide refresh, Saturdays at 06:00, Active, next Sat 7 Nov 06:00". */
function jobRowLabel(job) {
  const next = job.status === STATUS_ACTIVE && job.nextFireTime ? `next ${formatNextFire(job.nextFireTime)}` : '';
  return [job.displayName, describeSchedule(job).words, jobStatusView(job.status).label, next]
    .filter(Boolean).join(', ');
}

/**
 * The Scheduler list pane (plan §5.5): the header ("Scheduler", "{n} jobs · UK time (BST)"), the chips (schedule type;
 * paused or off in config) and the grid. Rows hold no buttons, and a paused or off row is dimmed; a row opens its
 * panel. Filters and sort are controlled by the caller.
 *
 * @param {object} props
 * @param {Array<object>} props.allJobs every served job, for the header and the chip counts
 * @param {Array<object>} props.jobs the jobs to show, already filtered and sorted
 * @param {boolean} props.loading true until the first answer
 * @param {string|null} props.error the load error, if any
 * @param {{type: string, status: string|null}} props.filters the chips
 * @param {(type: string) => void} props.onType picks a type chip
 * @param {(status: string) => void} props.onStatus toggles a status chip
 * @param {{key: string, dir: 'asc'|'desc'}} props.sort the sort
 * @param {(sortKey: string) => void} props.onSort sorts by a header
 * @param {(key: string, dir: 'asc'|'desc') => void} props.onSortChip picks a sort from the sort chip
 * @param {string|null} props.selectedKey the open job's key
 * @param {(job: object) => void} props.onSelect called when a row is activated
 */
export default function SchedulerListPane({
  allJobs, jobs, loading, error = null, filters, onType, onStatus, sort, onSort, onSortChip,
  selectedKey = null, onSelect,
}) {
  let body;
  if (loading) {
    body = (
      <p className="animate-pulse text-sm text-plex-text-muted motion-reduce:animate-none" data-testid="scheduler-loading">
        Loading scheduler jobs…
      </p>
    );
  } else if (jobs.length === 0 && error && allJobs.length === 0) {
    // A failed first load says so in the banner; "No scheduled jobs." would be a claim the server never made.
    body = null;
  } else if (jobs.length === 0) {
    body = (
      <p className="py-6 text-center text-sm text-plex-text-secondary" data-testid="scheduler-empty">
        {allJobs.length === 0 ? 'No scheduled jobs.' : 'No jobs match these filters.'}
      </p>
    );
  } else {
    body = (
      <DataGrid
        columns={COLUMNS}
        rows={jobs}
        getRowId={(job) => job.jobKey}
        getRowLabel={jobRowLabel}
        selectedId={selectedKey}
        onSelect={onSelect}
        isOff={(job) => job.status !== STATUS_ACTIVE}
        sort={sort}
        onSort={onSort}
        testIdPrefix="scheduler"
      />
    );
  }

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="scheduler-list-pane">
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2
          className="text-sm font-semibold text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="scheduler-heading"
        >
          Scheduler
        </h2>
        {!loading && (
          <span className="font-mono text-[11px] text-plex-text-muted" data-testid="scheduler-meta">
            {allJobs.length} {allJobs.length === 1 ? 'job' : 'jobs'} {'·'} UK time ({getUKTimezoneLabel()})
          </span>
        )}
      </div>

      <ErrorBanner message={error} data-testid="scheduler-error" />

      {!loading && allJobs.length > 0 && (
        <DataTools testId="scheduler-tools">
          <ChipRow testId="scheduler-chips">
            <ChipGroup label="Schedule type">
              {TYPE_CHIPS.filter((chip) => !chip.onlyIfAny || countByType(allJobs, chip.value) > 0).map((chip) => (
                <Chip
                  key={chip.value}
                  label={chip.label}
                  count={countByType(allJobs, chip.value)}
                  on={filters.type === chip.value}
                  onClick={() => onType(chip.value)}
                  testId={`scheduler-chip-${chip.value.toLowerCase().replace(/_/g, '-')}`}
                />
              ))}
            </ChipGroup>
            <ChipDivider />
            <ChipGroup label="Status">
              {STATUS_CHIPS.map((chip) => (
                <Chip
                  key={chip.value}
                  label={chip.label}
                  count={allJobs.filter((job) => job.status === chip.value).length}
                  on={filters.status === chip.value}
                  onClick={() => onStatus(chip.value)}
                  testId={`scheduler-chip-${chip.value.toLowerCase().replace(/_/g, '-')}`}
                />
              ))}
            </ChipGroup>
            <SortChip
              options={SORT_OPTIONS}
              sortKey={sort.key}
              sortDir={sort.dir}
              onChange={onSortChip}
              testId="scheduler-sort-chip"
            />
          </ChipRow>
        </DataTools>
      )}

      {body}
    </div>
  );
}

SchedulerListPane.propTypes = {
  allJobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
  filters: PropTypes.shape({ type: PropTypes.string.isRequired, status: PropTypes.string }).isRequired,
  onType: PropTypes.func.isRequired,
  onStatus: PropTypes.func.isRequired,
  sort: PropTypes.shape({ key: PropTypes.string, dir: PropTypes.oneOf(['asc', 'desc']) }).isRequired,
  onSort: PropTypes.func.isRequired,
  onSortChip: PropTypes.func.isRequired,
  selectedKey: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};
