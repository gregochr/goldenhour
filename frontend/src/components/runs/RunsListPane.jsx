import PropTypes from 'prop-types';
import DataGrid from '../data/DataGrid.jsx';
import Segmented from '../data/Segmented.jsx';
import StatusDot from '../data/StatusDot.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow } from '../data/Chips.jsx';
import { DataTools } from '../data/DataTools.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import RunsSummary from './RunsSummary.jsx';
import { textButtonClass } from '../users/userFormClasses.js';
import { noteToneClass, runStatusView } from '../../utils/runStatus.js';
import {
  formatClockUk, formatRunStarted, runCostText, runDurationText, runResultText, runRowId, runRowLabel, runScopeText,
} from '../../utils/runsView.js';

/** The range control's options: the wire value is upper case, the label is the words. */
const RANGE_OPTIONS = [
  { value: 'TODAY', label: 'Today' },
  { value: 'WEEK', label: '7 days' },
  { value: 'MONTH', label: '30 days' },
];

/** The kind chips, in order; the wire value is the chip's key. */
const KIND_CHIPS = [
  { value: 'ALL', label: 'All' },
  { value: 'CYCLES', label: 'Cycles' },
  { value: 'BATCHES', label: 'Batches' },
  { value: 'TIDE', label: 'Tide' },
  { value: 'BRIEFING', label: 'Briefing' },
  { value: 'AURORA', label: 'Aurora' },
  { value: 'OTHER', label: 'Other' },
];

/** The Status cell: the served status as a dot (Running carries the halo) and its words. */
function StatusCell({ item }) {
  const status = runStatusView(item.status);
  return (
    <StatusDot tone={status.tone} halo={status.halo} tintText testId={`runs-status-${runRowId(item)}`}>
      {status.label}
    </StatusDot>
  );
}

StatusCell.propTypes = { item: PropTypes.object.isRequired };

/**
 * The grid's columns (plan §5.2): Started 118px (mono), Run 1.9fr (the CYCLE tag and name, the scope, the note in its
 * tone), Status .8fr, then Result, Duration and Cost (secondary, so they drop at 820px). In card mode line 1 is the
 * name, the status and the note, line 2 "{started} · {duration} · {cost}". No cell holds a control. Clock reads use
 * {@code new Date()} (R-D7), never the Rewind clock.
 */
const COLUMNS = [
  {
    key: 'started',
    header: 'Started',
    width: '118px',
    cell: (item) => (
      <span className="font-mono text-[12px] text-plex-text-secondary" data-testid={`runs-started-${runRowId(item)}`}>
        {formatRunStarted(item.startedAt)}
      </span>
    ),
  },
  {
    key: 'run',
    header: 'Run',
    width: '1.9fr',
    card: 'title',
    cell: (item) => (
      <>
        <span className="flex min-w-0 items-center gap-2">
          {item.kind === 'CYCLE' && (
            <span
              className="shrink-0 rounded-sm border border-plex-border px-1.5 py-px font-mono text-[9.5px] tracking-wide text-plex-text-secondary"
              data-testid={`runs-cycle-tag-${runRowId(item)}`}
            >
              CYCLE
            </span>
          )}
          <span className="truncate font-medium" title={item.name} data-testid={`runs-name-${runRowId(item)}`}>
            {item.name}
          </span>
        </span>
        <span
          className="block truncate text-xs text-plex-text-secondary"
          data-testid={`runs-scope-${runRowId(item)}`}
        >
          {runScopeText(item)}
        </span>
        {item.note && (
          <span
            className={`block min-w-0 line-clamp-2 break-words [overflow-wrap:anywhere] text-xs ${noteToneClass(item.noteTone)}`}
            title={item.note}
            data-testid={`runs-note-${runRowId(item)}`}
          >
            {item.note}
          </span>
        )}
      </>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    width: '.8fr',
    card: 'status',
    cell: (item) => <StatusCell item={item} />,
  },
  {
    key: 'result',
    header: 'Result',
    width: '.8fr',
    secondary: true,
    cell: (item) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`runs-result-${runRowId(item)}`}>
        {runResultText(item)}
      </span>
    ),
  },
  {
    key: 'duration',
    header: 'Duration',
    width: '.8fr',
    secondary: true,
    cell: (item) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`runs-duration-${runRowId(item)}`}>
        {runDurationText(item)}
      </span>
    ),
  },
  {
    key: 'cost',
    header: 'Cost',
    width: '.5fr',
    secondary: true,
    cell: (item) => (
      <span className="font-mono text-[12.5px] text-plex-text-secondary" data-testid={`runs-cost-${runRowId(item)}`}>
        {runCostText(item)}
      </span>
    ),
  },
  {
    key: 'card-summary',
    card: 'summary',
    cardOnly: true,
    cell: (item) => (
      <span className="block truncate text-xs text-plex-text-secondary">
        {[formatRunStarted(item.startedAt), runDurationText(item), runCostText(item)]
          .filter((part) => part !== '' && part !== '—').join(' · ')}
      </span>
    ),
  },
];

/** The empty line: "No runs in this range." and, when the server names it, when the next overnight cycle starts. */
function emptyText(nextOvernightAt) {
  const clock = nextOvernightAt ? formatClockUk(nextOvernightAt) : '';
  return clock ? `No runs in this range. The next overnight cycle starts ${clock}.` : 'No runs in this range.';
}

/**
 * The Runs list pane (plan §5.2): the header (title, "Updated {HH:MM} · refreshes every minute", Refresh, Start a
 * run), the live run's progress, the summary, the tools (range, kind chips, Need attention), the grid and "Showing
 * {n} of {total}" with Load more. Filters are controlled by the caller. Rows hold no buttons; a row opens its panel.
 *
 * @param {object} props
 * @param {{current: HTMLElement|null}} props.headingRef the "Runs" heading, which takes focus after a progress Dismiss
 * @param {{current: HTMLElement|null}} [props.startButtonRef] the Start a run button, which takes focus when its panel closes
 * @param {Array<object>} props.items the loaded rows
 * @param {object|null} props.meta the response's aggregates ({@code summary}, {@code kindCounts}, …)
 * @param {boolean} props.loading true until the first page for the current filters arrives
 * @param {boolean} props.loadingMore true while Load more is in flight
 * @param {string|null} props.error the load error, if any
 * @param {Date|null} props.updatedAt when the head last arrived (the real clock)
 * @param {boolean} props.hasMore whether another page can be loaded
 * @param {{range: string, kind: string, attention: boolean}} props.filters the current filters
 * @param {{setRange: function, setKind: function, toggleAttention: function}} props.filterActions the filter setters
 * @param {string|null} props.selectedKey the open row's id ({@code job-9876})
 * @param {(item: object) => void} props.onSelect called when a row is activated
 * @param {() => void} props.onLoadMore called by Load more
 * @param {() => void} props.onRefresh called by Refresh
 * @param {() => void} props.onStart called by Start a run
 * @param {import('react').ReactNode} [props.progress] the live run-progress panel, drawn above the summary
 * @param {import('react').ReactNode} [props.liveStrip] the running cycle's strip, drawn above the summary
 */
export default function RunsListPane({
  headingRef, startButtonRef = undefined, items, meta = null, loading, loadingMore, error = null, updatedAt = null, hasMore, filters, filterActions,
  selectedKey = null, onSelect, onLoadMore, onRefresh, onStart, progress = null, liveStrip = null,
}) {
  const kindCounts = meta?.kindCounts ?? {};
  let body;
  if (loading) {
    body = (
      <p className="text-sm text-plex-text-muted animate-pulse motion-reduce:animate-none" data-testid="runs-loading">
        Loading runs{'…'}
      </p>
    );
  } else if (items.length === 0) {
    body = (
      <p className="py-6 text-center text-sm text-plex-text-secondary" data-testid="runs-empty">
        {emptyText(meta?.nextOvernightAt)}
      </p>
    );
  } else {
    body = (
      <div>
        <DataGrid
          columns={COLUMNS}
          rows={items}
          getRowId={runRowId}
          getRowLabel={(item) => runRowLabel(item, runStatusView(item.status).label)}
          selectedId={selectedKey}
          onSelect={onSelect}
          testIdPrefix="runs"
        />
        <div className="mt-3 flex items-center justify-between gap-3 text-xs text-plex-text-muted">
          <span data-testid="runs-showing">Showing {items.length} of {meta?.total ?? items.length}</span>
          {hasMore && (
            <button
              type="button"
              className="btn-secondary text-xs max-[639px]:min-h-11"
              onClick={onLoadMore}
              disabled={loadingMore}
              data-testid="runs-load-more"
            >
              {loadingMore ? 'Loading...' : 'Load more'}
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="@container flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="runs-list-pane">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2
            ref={headingRef}
            tabIndex={-1}
            className="text-sm font-semibold text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
            data-testid="runs-heading"
          >
            Runs
          </h2>
          {updatedAt && (
            <span className="font-mono text-[11px] text-plex-text-muted" data-testid="runs-updated">
              Updated {formatClockUk(updatedAt)} {'·'} refreshes every minute
            </span>
          )}
        </div>
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            className={textButtonClass}
            onClick={onRefresh}
            data-testid="runs-refresh-btn"
          >
            Refresh
          </button>
          <button
            ref={startButtonRef}
            type="button"
            className="btn-primary shrink-0 text-xs max-[639px]:min-h-11"
            onClick={onStart}
            data-testid="start-run-btn"
          >
            Start a run
          </button>
        </div>
      </div>

      {progress}

      <ErrorBanner message={error} data-testid="runs-error" />

      {liveStrip}

      {meta?.summary && <RunsSummary summary={meta.summary} />}

      <DataTools testId="runs-tools">
        <Segmented
          label="Range"
          options={RANGE_OPTIONS}
          value={filters.range}
          onChange={filterActions.setRange}
          testId="runs-range"
        />
        <ChipRow testId="runs-chips">
          <ChipGroup label="Kind">
            {KIND_CHIPS.map((chip) => (
              <Chip
                key={chip.value}
                label={chip.label}
                count={kindCounts[chip.value]}
                on={filters.kind === chip.value}
                onClick={() => filterActions.setKind(chip.value)}
                testId={`runs-chip-${chip.value.toLowerCase()}`}
              />
            ))}
          </ChipGroup>
          <ChipDivider />
          <ChipGroup label="Status">
            <Chip
              label="Need attention"
              count={meta?.attentionCount}
              on={filters.attention}
              onClick={filterActions.toggleAttention}
              testId="runs-chip-attention"
            />
          </ChipGroup>
        </ChipRow>
      </DataTools>

      {body}
    </div>
  );
}

RunsListPane.propTypes = {
  headingRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  startButtonRef: PropTypes.shape({ current: PropTypes.any }),
  items: PropTypes.arrayOf(PropTypes.object).isRequired,
  meta: PropTypes.object,
  loading: PropTypes.bool.isRequired,
  loadingMore: PropTypes.bool.isRequired,
  error: PropTypes.string,
  updatedAt: PropTypes.instanceOf(Date),
  hasMore: PropTypes.bool.isRequired,
  filters: PropTypes.shape({
    range: PropTypes.string.isRequired,
    kind: PropTypes.string.isRequired,
    attention: PropTypes.bool.isRequired,
  }).isRequired,
  filterActions: PropTypes.shape({
    setRange: PropTypes.func.isRequired,
    setKind: PropTypes.func.isRequired,
    toggleAttention: PropTypes.func.isRequired,
  }).isRequired,
  selectedKey: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
  onLoadMore: PropTypes.func.isRequired,
  onRefresh: PropTypes.func.isRequired,
  onStart: PropTypes.func.isRequired,
  progress: PropTypes.node,
  liveStrip: PropTypes.node,
};
