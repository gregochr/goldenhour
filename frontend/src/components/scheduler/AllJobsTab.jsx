import PropTypes from 'prop-types';
import DataGrid from '../data/DataGrid.jsx';
import StatusDot from '../data/StatusDot.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow } from '../data/Chips.jsx';
import { DataSearch, DataTools } from '../data/DataTools.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import {
  SCHEDULE_CRON,
  SCHEDULE_FIXED_DELAY,
  SCHEDULE_SOLAR,
  STATUS_ACTIVE,
  STATUS_OFF,
  STATUS_PAUSED,
  countByType,
  describeSchedule,
  fireTook,
  fireStatusView,
  formatClock,
  formatNextFire,
  groupJobs,
  jobStatusView,
  relativeTime,
} from '../../utils/schedulerView.js';

/** The type chips after All, in order; every one is always shown (the design lists Fixed delay). */
const TYPE_CHIPS = [
  { value: SCHEDULE_SOLAR, label: 'Sun-relative' },
  { value: SCHEDULE_CRON, label: 'Cron' },
  { value: SCHEDULE_FIXED_DELAY, label: 'Fixed delay' },
];

/** The status chips: each narrows the list to jobs with that status, and pressing it again clears it. */
const STATUS_CHIPS = [
  { value: STATUS_PAUSED, label: 'Paused' },
  { value: STATUS_OFF, label: 'Off in config' },
];

/** The chip counts: the served summary's, or (when it has not loaded) counted from the jobs. */
function chipCounts(allJobs, summary) {
  const byType = (type) => summary?.byType?.[type] ?? countByType(allJobs, type);
  const byStatus = (status) => summary?.byStatus?.[status] ?? allJobs.filter((job) => job.status === status).length;
  return {
    all: summary?.total ?? allJobs.length,
    [SCHEDULE_SOLAR]: byType(SCHEDULE_SOLAR),
    [SCHEDULE_CRON]: byType(SCHEDULE_CRON),
    [SCHEDULE_FIXED_DELAY]: byType(SCHEDULE_FIXED_DELAY),
    [STATUS_PAUSED]: byStatus(STATUS_PAUSED),
    [STATUS_OFF]: byStatus(STATUS_OFF),
    claude: summary?.usesClaude ?? allJobs.filter((job) => job.catalogue?.usesClaude).length,
    failed: summary?.lastFireFailed ?? allJobs.filter((job) => job.lastFire?.status === 'FAILED').length,
  };
}

/** The Status cell: the served status as a dot and its words. */
function StatusCell({ job, testId }) {
  const status = jobStatusView(job.status);
  return <StatusDot tone={status.tone} tintText testId={testId}>{status.label}</StatusDot>;
}

StatusCell.propTypes = { job: PropTypes.object.isRequired, testId: PropTypes.string.isRequired };

/** The Last run cell: the newest fire's status dot, how long ago it started and how long it took; else an em dash. */
function LastRunCell({ job }) {
  const testId = `scheduler-last-${job.jobKey}`;
  const fire = job.lastFire;
  if (!fire) return <span className="text-plex-text-muted" data-testid={testId}>{'—'}</span>;
  const status = fireStatusView(fire.status);
  const took = fireTook(fire);
  return (
    <span className="flex flex-col gap-0.5" data-testid={testId}>
      <StatusDot tone={status.tone} halo={status.halo} tintText>{status.label}</StatusDot>
      <span className="font-mono text-[11.5px] text-plex-text-muted" data-testid={`last-fire-${job.jobKey}`}>
        {[relativeTime(fire.firedAt), took].filter(Boolean).join(' · ')}
      </span>
    </span>
  );
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

/** A small mono tag: the purpose, or "£ Claude". */
function Tag({ children, gold = false, testId = undefined }) {
  const tone = gold ? 'border-skyeval-version/60 text-skyeval-version' : 'border-plex-border-light text-plex-text-secondary';
  return (
    <span
      className={`inline-block rounded border px-1.5 py-px font-mono text-[10px] uppercase leading-4 tracking-[0.06em] ${tone}`}
      data-testid={testId}
    >
      {children}
    </span>
  );
}

Tag.propTypes = { children: PropTypes.node.isRequired, gold: PropTypes.bool, testId: PropTypes.string };

/**
 * A row's accessible name, reading the whole row: "Tide refresh, Saturdays at 06:00, Active, next Sat 7 Nov 06:00",
 * with "uses Claude" and, when it did not succeed, how the last run went ("last run failed").
 */
function jobRowLabel(job) {
  const next = job.status === STATUS_ACTIVE && job.nextFireTime ? `next ${formatNextFire(job.nextFireTime)}` : '';
  const last = job.lastFire && job.lastFire.status !== 'SUCCEEDED'
    ? `last run ${fireStatusView(job.lastFire.status).label.toLowerCase()}` : '';
  return [job.displayName, describeSchedule(job).words, jobStatusView(job.status).label, next, last,
    job.catalogue?.usesClaude ? 'uses Claude' : '']
    .filter(Boolean).join(', ');
}

/**
 * The grid's columns (scheduler-redesign-plan.md D11). Job (name, tags, the full plain-English paragraph, at least
 * 260px), Schedule, Next, then Last run and Status, which drop at 820px (DataGrid's secondary tier). At 540px the row
 * becomes a card (the name and status on line 1, "{schedule} · next {time}" on line 2), so Schedule goes too: the
 * recorded deviation from the design's 760/480.
 */
function columns(preview) {
  return [
    {
      key: 'job',
      header: 'Job',
      width: '2fr',
      minWidth: '260px',
      card: 'title',
      cell: (job) => (
        <>
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-medium" data-testid={`scheduler-name-${job.jobKey}`}>{job.displayName}</span>
            {job.catalogue?.purpose && <Tag testId={`scheduler-purpose-${job.jobKey}`}>{job.catalogue.purpose}</Tag>}
            {job.catalogue?.usesClaude && <Tag gold testId={`scheduler-claude-${job.jobKey}`}>{'£ Claude'}</Tag>}
          </span>
          {job.catalogue?.what && (
            <span className="mt-1 block text-xs leading-relaxed text-plex-text-secondary" data-testid={`scheduler-what-${job.jobKey}`}>
              {job.catalogue.what}
            </span>
          )}
        </>
      ),
    },
    {
      key: 'schedule',
      header: 'Schedule',
      width: '1fr',
      cell: (job) => {
        const schedule = describeSchedule(job);
        const today = job.scheduleType === SCHEDULE_SOLAR
          ? formatClock(preview?.jobs?.find((p) => p.jobKey === job.jobKey)?.today?.fireInstant)
          : '';
        return (
          <>
            <span className="block text-[13px]" data-testid={`scheduler-schedule-${job.jobKey}`}>{schedule.words}</span>
            <span className="block font-mono text-[11px] text-plex-text-muted">
              {schedule.recognised ? schedule.type : `Unrecognised schedule type: ${schedule.type}`}
              {today && ` · today ${today}`}
            </span>
          </>
        );
      },
    },
    { key: 'next', header: 'Next', width: '.8fr', cell: (job) => <NextCell job={job} /> },
    { key: 'last', header: 'Last run', width: '.9fr', secondary: true, cell: (job) => <LastRunCell job={job} /> },
    {
      key: 'status',
      header: 'Status',
      width: '.7fr',
      secondary: true,
      cell: (job) => <StatusCell job={job} testId={`scheduler-status-${job.jobKey}`} />,
    },
    {
      key: 'card-status',
      card: 'status',
      cardOnly: true,
      cell: (job) => <StatusCell job={job} testId={`scheduler-card-status-${job.jobKey}`} />,
    },
    {
      key: 'card-summary',
      card: 'summary',
      cardOnly: true,
      cell: (job) => {
        const active = job.status === STATUS_ACTIVE && job.nextFireTime;
        const next = active
          ? `next ${formatNextFire(job.nextFireTime)} (${relativeTime(job.nextFireTime, { future: true })})`
          : 'next —';
        return (
          <span className="block truncate text-xs text-plex-text-secondary" data-testid={`scheduler-card-line-${job.jobKey}`}>
            {`${describeSchedule(job).words} · ${next}`}
          </span>
        );
      },
    },
  ];
}

/**
 * The All jobs tab (scheduler-redesign-plan.md D11): the search box, the chips (schedule type; paused or off in
 * config; uses Claude; last run failed, with the served counts) and the grid, grouped by schedule type with each
 * group's count and subtitle and sorted by next fire inside it. Rows hold no buttons; a row opens the job's panel.
 * Filters are controlled by the caller. Every clock read is the real clock (R-D7).
 *
 * @param {object} props
 * @param {Array<object>} props.allJobs every served job (for "nothing matches" versus "no jobs")
 * @param {Array<object>} props.jobs the jobs to show, already searched and filtered
 * @param {object|null} props.summary the served summary (chip counts), or null when it has not loaded
 * @param {object|null} props.preview the solar preview (today's sun-relative fire time), or null
 * @param {boolean} props.loading true until the first answer
 * @param {string|null} [props.error] the load error, if any
 * @param {string} props.search the search text
 * @param {(text: string) => void} props.onSearch sets the search text
 * @param {{type: string, status: string|null, claude: boolean, failed: boolean}} props.filters the chips
 * @param {(type: string) => void} props.onType picks a type chip
 * @param {(status: string) => void} props.onStatus toggles a status chip
 * @param {(flag: 'claude'|'failed') => void} props.onFlag toggles the Uses Claude or Last run failed chip
 * @param {() => void} props.onAll clears every chip (type, status, Uses Claude, Last run failed)
 * @param {string|null} [props.selectedKey] the open job's key
 * @param {(job: object) => void} props.onSelect called when a row is activated
 */
export default function AllJobsTab({
  allJobs, jobs, summary = null, preview = null, loading, error = null, search, onSearch, filters, onType, onStatus,
  onFlag, onAll, selectedKey = null, onSelect,
}) {
  const counts = chipCounts(allJobs, summary);
  // All is lit only when no chip at all is on; pressing it clears every chip.
  const noChipOn = filters.type === 'all' && filters.status == null && !filters.claude && !filters.failed;
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
    const rows = groupJobs(jobs).flatMap((group) => [
      { groupHeader: true, id: group.type.toLowerCase(), label: group.label, count: group.jobs.length, subtitle: group.subtitle },
      ...group.jobs,
    ]);
    body = (
      <DataGrid
        columns={columns(preview)}
        rows={rows}
        getRowId={(job) => job.jobKey}
        getRowLabel={jobRowLabel}
        selectedId={selectedKey}
        onSelect={onSelect}
        isOff={(job) => job.status !== STATUS_ACTIVE}
        testIdPrefix="scheduler"
      />
    );
  }

  return (
    <div className="@container flex flex-col gap-4" data-testid="scheduler-all-jobs">
      <ErrorBanner message={error} data-testid="scheduler-error" />

      {!loading && allJobs.length > 0 && (
        <DataTools testId="scheduler-tools">
          <DataSearch
            value={search}
            onChange={onSearch}
            placeholder="Search jobs, e.g. tides, email, Claude"
            testId="scheduler-search"
          />
          <ChipRow testId="scheduler-chips">
            <ChipGroup label="Schedule type" wrap>
              <Chip
                label="All"
                count={counts.all}
                on={noChipOn}
                onClick={onAll}
                testId="scheduler-chip-all"
              />
              {TYPE_CHIPS.map((chip) => (
                <Chip
                  key={chip.value}
                  label={chip.label}
                  count={counts[chip.value]}
                  on={filters.type === chip.value}
                  onClick={() => onType(chip.value)}
                  testId={`scheduler-chip-${chip.value.toLowerCase().replace(/_/g, '-')}`}
                />
              ))}
            </ChipGroup>
            <ChipDivider />
            <ChipGroup label="Status" wrap>
              {STATUS_CHIPS.map((chip) => (
                <Chip
                  key={chip.value}
                  label={chip.label}
                  count={counts[chip.value]}
                  on={filters.status === chip.value}
                  onClick={() => onStatus(chip.value)}
                  testId={`scheduler-chip-${chip.value.toLowerCase().replace(/_/g, '-')}`}
                />
              ))}
              <Chip
                label="Uses Claude"
                count={counts.claude}
                on={filters.claude}
                onClick={() => onFlag('claude')}
                testId="scheduler-chip-claude"
              />
              <Chip
                label="Last run failed"
                count={counts.failed}
                on={filters.failed}
                onClick={() => onFlag('failed')}
                testId="scheduler-chip-failed"
              />
            </ChipGroup>
          </ChipRow>
        </DataTools>
      )}

      {body}
    </div>
  );
}

AllJobsTab.propTypes = {
  allJobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  summary: PropTypes.object,
  preview: PropTypes.object,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.string,
  search: PropTypes.string.isRequired,
  onSearch: PropTypes.func.isRequired,
  filters: PropTypes.shape({
    type: PropTypes.string.isRequired,
    status: PropTypes.string,
    claude: PropTypes.bool.isRequired,
    failed: PropTypes.bool.isRequired,
  }).isRequired,
  onType: PropTypes.func.isRequired,
  onStatus: PropTypes.func.isRequired,
  onFlag: PropTypes.func.isRequired,
  onAll: PropTypes.func.isRequired,
  selectedKey: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};
