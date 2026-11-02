import PropTypes from 'prop-types';
import StatusDot from '../../data/StatusDot.jsx';
import { Chip, ChipDivider, ChipGroup, ChipRow } from '../../data/Chips.jsx';
import ExternalCallsTable from './ExternalCallsTable.jsx';
import { noteToneClass, runStatusView } from '../../../utils/runStatus.js';
import { formatClockUk } from '../../../utils/runsView.js';
import {
  FILTER_CHIPS, formatClockSecondsUk, formatGbp, formatUsd, isCycleType, rowCostIsOpen, rowCostText,
  rowDurationText, rowMatchesFilter, rowStartText, typeStyle,
} from '../../../utils/runsDay.js';

/** The row's grid: start, run, [duration, [cost,]] status, widening with the list column (container queries, F9). */
const ROW_GRID = 'grid grid-cols-[52px_minmax(0,1fr)_92px] items-start gap-x-3 @min-[600px]:grid-cols-[62px_minmax(0,1fr)_110px_92px] @min-[900px]:grid-cols-[62px_minmax(0,1fr)_110px_72px_92px]';

/** A row of the facts line: a label and its mono value. */
function Fact({ label, children, testId }) {
  return (
    <div className="inline-flex items-baseline gap-1.5" data-testid={testId}>
      <dt className="text-plex-text-muted">{label}</dt>
      <dd className="font-mono font-medium text-plex-text">{children}</dd>
    </div>
  );
}

Fact.propTypes = { label: PropTypes.string.isRequired, children: PropTypes.node.isRequired, testId: PropTypes.string.isRequired };

/** The expanded row's facts, by what the row is: finished (or running) facts, or a scheduled row's expectations. */
function RowFacts({ row }) {
  const facts = row.facts ?? {};
  if (row.status === 'SCHEDULED') {
    return (
      <dl className="flex flex-wrap items-baseline gap-x-5 gap-y-1 text-[12px]" data-testid={`runs-day-facts-${row.id}`}>
        <Fact label="Expected start" testId="runs-fact-expected-start">
          {row.estimated ? '~' : ''}{formatClockUk(facts.expectedStart ?? row.startedAt)}
        </Fact>
        <Fact label="Expected finish" testId="runs-fact-expected-finish">
          {facts.expectedFinish ? `~${formatClockUk(facts.expectedFinish)}` : '—'}
        </Fact>
        <div className="text-plex-text-muted" data-testid="runs-fact-estimate-note">Estimate: median of last 30 runs</div>
      </dl>
    );
  }
  const cost = row.costGbp != null ? `${formatGbp(row.costGbp)} · ${formatUsd(row.costUsd)}` : '—';
  return (
    <dl className="flex flex-wrap items-baseline gap-x-5 gap-y-1 text-[12px]" data-testid={`runs-day-facts-${row.id}`}>
      <Fact label="Started" testId="runs-fact-started">{formatClockSecondsUk(facts.startedAt ?? row.startedAt)}</Fact>
      {facts.finishedAt && <Fact label="Finished" testId="runs-fact-finished">{formatClockSecondsUk(facts.finishedAt)}</Fact>}
      <Fact label="Cost" testId="runs-fact-cost">{rowCostIsOpen(row) ? `${cost} so far` : cost}</Fact>
      {facts.fasterThanPercent != null && (
        <Fact label="Speed" testId="runs-fact-speed">faster than {facts.fasterThanPercent}% of last 30</Fact>
      )}
      {facts.batches != null && (
        <Fact label="Batches" testId="runs-fact-batches">
          {facts.batches} {'·'} {facts.requests ?? 0} requests
        </Fact>
      )}
      {facts.answersReadyAt && (
        <Fact label="Answers ready" testId="runs-fact-answers-ready">{formatClockUk(facts.answersReadyAt)}</Fact>
      )}
    </dl>
  );
}

RowFacts.propTypes = { row: PropTypes.object.isRequired };

/** The panel a row's "Open run detail" opens, or null when the row has none (a Sky eval, a scheduled fire). */
function detailTarget(row) {
  if (row.kind === 'CYCLE' && row.cycleId != null) return { href: `#manage/runs/cycle/${row.cycleId}` };
  if (row.kind === 'JOB' && row.jobRunId != null) return { href: `#manage/runs/job/${row.jobRunId}` };
  return null;
}

/** One row: the toggle button, and under it, when open, the facts, the detail link and the external calls. */
function DayRow({ row, open, selected, onToggle, onOpenDetail }) {
  const status = runStatusView(row.status);
  const scheduled = row.status === 'SCHEDULED';
  const target = detailTarget(row);
  const duration = rowDurationText(row);
  const cost = rowCostText(row);
  const dim = scheduled ? 'text-plex-text-secondary' : 'text-plex-text';
  return (
    <li
      className={`border-l-2 border-t border-t-plex-border first:border-t-0 ${open ? 'border-l-plex-gold bg-plex-surface-light' : 'border-l-transparent'}`}
      data-testid={`runs-day-item-${row.id}`}
    >
      <button
        type="button"
        id={`runs-day-row-btn-${row.id}`}
        className={`${ROW_GRID} w-full cursor-pointer px-3 py-2.5 text-left transition-colors hover:bg-white/4 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold max-[639px]:min-h-11`}
        aria-expanded={open}
        aria-controls={`runs-day-detail-${row.id}`}
        aria-current={selected ? 'true' : undefined}
        onClick={() => onToggle(row.id)}
        data-testid={`runs-day-row-${row.id}`}
        data-status={row.status}
        data-type={row.type}
      >
        <span className="pt-0.5 font-mono text-[12px] text-plex-text-secondary" data-testid={`runs-day-start-${row.id}`}>
          {rowStartText(row)}
        </span>
        <span className="min-w-0">
          <span className="flex min-w-0 items-center gap-2">
            <span className={`size-2 shrink-0 rounded-[2px] ${typeStyle(row.type).swatch}`} aria-hidden="true" />
            {isCycleType(row.type) && (
              <span
                className="shrink-0 rounded-sm border border-plex-border px-1.5 py-px font-mono text-[9.5px] tracking-wide text-plex-text-secondary"
                data-testid={`runs-day-cycle-tag-${row.id}`}
              >
                CYCLE
              </span>
            )}
            <span
              className={`truncate ${scheduled ? 'font-normal' : 'font-medium'} ${dim}`}
              title={row.name}
              data-testid={`runs-day-name-${row.id}`}
            >
              {row.name}
            </span>
          </span>
          {row.subline && (
            <span className="block truncate text-xs text-plex-text-secondary" data-testid={`runs-day-subline-${row.id}`}>
              {row.subline}
            </span>
          )}
          {row.note && (
            <span
              className={`block min-w-0 line-clamp-2 break-words [overflow-wrap:anywhere] text-xs ${noteToneClass(row.noteTone)}`}
              title={row.note}
              data-testid={`runs-day-note-${row.id}`}
            >
              {row.note}
            </span>
          )}
          <span className="mt-0.5 block font-mono text-[11px] text-plex-text-muted @min-[600px]:hidden" data-testid={`runs-day-compact-${row.id}`}>
            {[duration, cost].filter((part) => part !== '—').join(' · ')}
          </span>
        </span>
        <span className="hidden text-right @min-[600px]:block" data-testid={`runs-day-duration-${row.id}`}>
          <span className="font-mono text-[12.5px] text-plex-text-secondary">{duration}</span>
          {row.slow && (
            <span className="block font-mono text-[10px] text-verdict-marginal" data-testid={`runs-day-slow-${row.id}`}>
              slow {'·'} top 10%
            </span>
          )}
        </span>
        <span className="hidden text-right @min-[900px]:block" data-testid={`runs-day-cost-${row.id}`}>
          <span className="font-mono text-[12.5px] text-plex-text-secondary">{cost}</span>
          {rowCostIsOpen(row) && <span className="block font-mono text-[10px] text-plex-text-muted">so far</span>}
        </span>
        <span className="pt-0.5">
          <StatusDot tone={status.tone} halo={status.halo} hollow={status.hollow} tintText testId={`runs-day-status-${row.id}`}>
            {status.label}
          </StatusDot>
        </span>
      </button>
      {open && (
        <div
          id={`runs-day-detail-${row.id}`}
          role="region"
          aria-labelledby={`runs-day-row-btn-${row.id}`}
          className="flex flex-col gap-2 px-3 pb-3 pl-[76px] max-[639px]:pl-3"
          data-testid={`runs-day-detail-${row.id}`}
        >
          <RowFacts row={row} />
          {target && (
            <a
              href={target.href}
              className="w-fit text-[12.5px] text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:flex max-[639px]:min-h-11 max-[639px]:items-center"
              onClick={(event) => {
                if (event.metaKey || event.ctrlKey || event.shiftKey || event.button > 0) return;
                event.preventDefault();
                onOpenDetail(row);
              }}
              data-testid={`runs-row-${row.id}`}
            >
              Open run detail {'→'}
            </a>
          )}
          {!scheduled && <ExternalCallsTable runId={row.id} logged={row.kind !== 'SKY_EVAL'} />}
        </div>
      )}
    </li>
  );
}

DayRow.propTypes = {
  row: PropTypes.object.isRequired,
  open: PropTypes.bool.isRequired,
  selected: PropTypes.bool.isRequired,
  onToggle: PropTypes.func.isRequired,
  onOpenDetail: PropTypes.func.isRequired,
};

/** A group's header: "FINISHED · 5", "STILL TO RUN TODAY · 8 · times after a cycle are estimates". */
function GroupHeader({ children, testId }) {
  return (
    <li className="border-t border-plex-border bg-plex-bg/30 px-3 py-1.5 font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted first:border-t-0" data-testid={testId}>
      {children}
    </li>
  );
}

GroupHeader.propTypes = { children: PropTypes.node.isRequired, testId: PropTypes.string.isRequired };

/**
 * The Day view's run card (the handoff's "Run list card"): the filter chips with the day's served counts, the column
 * header, and the rows in time order. On today the rows split into FINISHED and STILL TO RUN TODAY (times after a
 * cycle are estimates, the {@code ~}); a past day is one list. A row is one button: it toggles open (one at a time,
 * {@code aria-expanded}) to its facts, an "Open run detail" link to the existing panel, and its external calls. Rows hold
 * no other control. The columns follow the list column's width (container queries): Cost goes under 900px and Duration
 * under 600px, where a compact line under the name carries them.
 *
 * @param {object} props
 * @param {object} props.day the served {@code RunDayResponse}
 * @param {string} props.filter the chip key ({@code all}, {@code cycles}, ... {@code attention})
 * @param {(filter: string) => void} props.onFilterChange sets the chip
 * @param {string|null} props.openId the expanded row's id
 * @param {(id: string) => void} props.onToggle expands or collapses a row
 * @param {string|null} [props.selectedKey] the row whose panel is open ({@code cycle-12})
 * @param {(row: object) => void} props.onOpenDetail opens a row's panel
 */
export default function DayRunList({ day, filter, onFilterChange, openId, onToggle, selectedKey = null, onOpenDetail }) {
  const counts = day.filterCounts ?? {};
  const rows = day.runs.filter((row) => rowMatchesFilter(row, filter));
  const finished = rows.filter((row) => row.status !== 'SCHEDULED');
  const upcoming = rows.filter((row) => row.status === 'SCHEDULED');
  const renderRow = (row) => (
    <DayRow
      key={row.id}
      row={row}
      open={openId === row.id}
      selected={selectedKey === row.id}
      onToggle={onToggle}
      onOpenDetail={onOpenDetail}
    />
  );

  let body;
  if (day.runs.length === 0) {
    body = <p className="py-6 text-center text-sm text-plex-text-secondary" data-testid="runs-day-empty">No runs on this day.</p>;
  } else if (rows.length === 0) {
    body = (
      <p className="py-6 text-center text-sm text-plex-text-secondary" data-testid="runs-day-filter-empty">
        No runs match this filter.{' '}
        <button
          type="button"
          className="underline hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          onClick={() => onFilterChange('all')}
          data-testid="runs-day-filter-clear"
        >
          Show all
        </button>
      </p>
    );
  } else if (day.isToday) {
    body = (
      <ul>
        {finished.length > 0 && <GroupHeader testId="runs-day-group-finished">Finished {'·'} {finished.length}</GroupHeader>}
        {finished.map(renderRow)}
        {upcoming.length > 0 && (
          <GroupHeader testId="runs-day-group-upcoming">
            Still to run today {'·'} {upcoming.length} {'·'} times after a cycle are estimates
          </GroupHeader>
        )}
        {upcoming.map(renderRow)}
      </ul>
    );
  } else {
    body = <ul>{rows.map(renderRow)}</ul>;
  }

  return (
    <section className="@container rounded-xl border border-plex-border bg-plex-surface p-4 max-[639px]:px-3" data-testid="runs-day-list">
      <ChipRow testId="runs-chips">
        <ChipGroup label="Type">
          {FILTER_CHIPS.map((chip) => (
            <Chip
              key={chip.key}
              label={chip.label}
              count={counts[chip.countKey]}
              on={filter === chip.key}
              onClick={() => onFilterChange(chip.key)}
              testId={`runs-chip-${chip.key}`}
            />
          ))}
        </ChipGroup>
        <ChipDivider />
        <ChipGroup label="Status">
          <Chip
            label="Need attention"
            count={counts.ATTENTION}
            on={filter === 'attention'}
            onClick={() => onFilterChange(filter === 'attention' ? 'all' : 'attention')}
            testId="runs-chip-attention"
          />
        </ChipGroup>
      </ChipRow>
      <div
        className={`${ROW_GRID} mt-3 border-b border-plex-border px-3 pb-1.5 font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted`}
        aria-hidden="true"
      >
        <span>Start</span>
        <span>Run</span>
        <span className="hidden text-right @min-[600px]:block">Duration</span>
        <span className="hidden text-right @min-[900px]:block">Cost</span>
        <span>Status</span>
      </div>
      {body}
    </section>
  );
}

DayRunList.propTypes = {
  day: PropTypes.object.isRequired,
  filter: PropTypes.string.isRequired,
  onFilterChange: PropTypes.func.isRequired,
  openId: PropTypes.string,
  onToggle: PropTypes.func.isRequired,
  selectedKey: PropTypes.string,
  onOpenDetail: PropTypes.func.isRequired,
};
