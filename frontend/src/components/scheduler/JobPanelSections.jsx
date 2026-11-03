import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import StatusDot from '../data/StatusDot.jsx';
import KvList from '../data/KvList.jsx';
import {
  fireDuration,
  fireStatusView,
  formatFireLabel,
  relativeTime,
} from '../../utils/schedulerView.js';
import { formatRunStarted, formatSeconds } from '../../utils/runsView.js';

/** The link look the panel uses for a route elsewhere ("Open in Runs →"). */
const LINK_CLASS = 'flex items-center text-[13px] text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11';

/** A Runs list item's address in the Runs tab: a cycle opens its cycle panel, a job run its job-run panel. */
function runHref(item) {
  return `#manage/runs/${item.kind === 'CYCLE' ? 'cycle' : 'job'}/${item.id}`;
}

/**
 * The next fires under the schedule: up to three, "Tomorrow 12:27" with how far off on the right. The server serves
 * none for a job that is paused or off. Real clock (R-D7).
 *
 * @param {object} props
 * @param {Array<string>} props.fires the served next-fire instants
 */
export function NextFiresList({ fires }) {
  if (!fires || fires.length === 0) return null;
  return (
    <ul className="mt-3 flex flex-col gap-1" data-testid="scheduler-panel-upcoming">
      {fires.map((instant) => (
        <li key={instant} className="flex items-baseline justify-between gap-3 font-mono text-[12.5px]">
          <span>{formatFireLabel(instant)}</span>
          <span className="text-plex-text-muted">{relativeTime(instant, { future: true })}</span>
        </li>
      ))}
    </ul>
  );
}

NextFiresList.propTypes = { fires: PropTypes.arrayOf(PropTypes.string) };

/**
 * The Last run section: the newest fire's status, when, how long it took and the job's own one-line result, with
 * "Open in Runs →" when the server can attribute a run to the job (it served {@code lastRun}); else a line saying
 * individual runs are not listed in Runs yet.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {object|null} props.detail the panel's detail, for the typical duration
 */
export function LastRunSection({ job, detail }) {
  const fire = job.lastFire;
  const status = fire ? fireStatusView(fire.status) : null;
  const took = fire ? fireDuration(fire) : '';
  const typical = detail?.typicalDurationSeconds != null ? `typically ${formatSeconds(detail.typicalDurationSeconds)}` : '';
  let footer = null;
  if (job.lastRun == null && !job.runGroup) {
    footer = (
      <p className="mt-2 text-[13px] text-plex-text-secondary" data-testid="scheduler-panel-no-runs">
        Individual runs of this job aren&apos;t listed in Runs yet.
      </p>
    );
  }
  const action = job.lastRun ? (
    <a href={runHref(job.lastRun)} className={LINK_CLASS} data-testid="scheduler-panel-open-run">
      Open in Runs <span aria-hidden="true">&rarr;</span>
    </a>
  ) : null;
  return (
    <PanelSection label="Last run" action={action} testId="scheduler-panel-last-run">
      {fire ? (
        <>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 font-mono text-[12.5px]" data-testid="scheduler-panel-last-line">
            <StatusDot tone={status.tone} halo={status.halo} tintText>{status.label}</StatusDot>
            <span>{formatRunStarted(fire.firedAt)}</span>
            <span className="text-plex-text-muted">
              {[relativeTime(fire.firedAt), took && `took ${took}`, typical].filter(Boolean).join(' · ')}
            </span>
          </p>
          {fire.summary && (
            <p className="mt-2 text-[13px] text-plex-text" data-testid="scheduler-panel-last-summary">{fire.summary}</p>
          )}
        </>
      ) : (
        <p className="text-[13px] text-plex-text-secondary" data-testid="scheduler-panel-last-none">No run recorded yet.</p>
      )}
      {footer}
    </PanelSection>
  );
}

LastRunSection.propTypes = {
  job: PropTypes.shape({
    lastFire: PropTypes.object,
    lastRun: PropTypes.object,
    runGroup: PropTypes.string,
  }).isRequired,
  detail: PropTypes.object,
};

/** The pounds figure to the penny. */
const pounds = (value) => `£${Number(value).toFixed(2)}`;

/**
 * The Claude cost section. A job that calls Claude shows its last-30-days spend and about what a run costs (from the
 * panel's detail: loading and error states included), with a note when a cost is still unknown or manual runs are
 * counted; any other job says it costs nothing (and, for a poller, whose batch it is counted against).
 *
 * @param {object} props
 * @param {object} props.catalogue the job's served catalogue entry
 * @param {object|null} props.detail the panel's detail
 * @param {boolean} props.loading true while the detail loads
 * @param {boolean} props.error true when it could not load
 */
export function ClaudeCostSection({ catalogue, detail, loading, error }) {
  let body;
  if (!catalogue.usesClaude) {
    const pollsFor = (catalogue.collectsResultsFrom ?? []).length > 0;
    body = (
      <p className="text-[13px] text-plex-text" data-testid="scheduler-panel-cost-none">
        None. This job doesn&apos;t call Claude{pollsFor ? '; batch costs are counted against the job that submitted them' : ''}.
      </p>
    );
  } else if (loading) {
    body = <p className="text-xs text-plex-text-muted" data-testid="scheduler-panel-cost-loading">Loading cost{'…'}</p>;
  } else if (error || detail == null) {
    body = <p className="text-xs text-plex-coral" data-testid="scheduler-panel-cost-error">Couldn&apos;t load the Claude cost.</p>;
  } else {
    const cost = detail.claudeCost30d;
    if (cost == null || cost.gbp == null) {
      body = (
        <p className="text-[13px] text-plex-text-secondary" data-testid="scheduler-panel-cost-unavailable">
          {cost?.reason ?? 'No cost figure is available for this job.'}
        </p>
      );
    } else {
      const notes = [];
      if (cost.complete === false) notes.push('Some costs are still unknown, so this is a floor.');
      if (cost.inclManual) notes.push('Includes manual runs.');
      if (cost.includes?.length > 0) notes.push(`Counts ${cost.includes.join(', ')}.`);
      body = (
        <>
          <p className="text-[13px] text-plex-text" data-testid="scheduler-panel-cost-figure">
            {pounds(cost.gbp)} over the last 30 days
            {cost.perRun != null && <> {'·'} about {pounds(cost.perRun)} a run</>}
          </p>
          {notes.length > 0 && (
            <p className="mt-1 text-xs text-plex-text-secondary" data-testid="scheduler-panel-cost-notes">{notes.join(' ')}</p>
          )}
        </>
      );
    }
  }
  return <PanelSection label="Claude cost" testId="scheduler-panel-cost">{body}</PanelSection>;
}

ClaudeCostSection.propTypes = {
  catalogue: PropTypes.shape({ usesClaude: PropTypes.bool, collectsResultsFrom: PropTypes.arrayOf(PropTypes.string) }).isRequired,
  detail: PropTypes.object,
  loading: PropTypes.bool.isRequired,
  error: PropTypes.bool.isRequired,
};

/** A service chip: Anthropic's in the gold, the rest in the quiet ink. */
function ServiceChip({ name }) {
  const gold = name.startsWith('Anthropic');
  return (
    <li
      className={`rounded border px-2 py-0.5 font-mono text-[11.5px] ${
        gold ? 'border-skyeval-version/60 text-skyeval-version' : 'border-plex-border-light text-plex-text-secondary'
      }`}
    >
      {name}
    </li>
  );
}

ServiceChip.propTypes = { name: PropTypes.string.isRequired };

/**
 * The Calls out to section: the external services as chips, or "Nothing outside PhotoCast. Database only."
 *
 * @param {object} props
 * @param {Array<string>} props.services the catalogue's services
 */
export function CallsOutSection({ services }) {
  return (
    <PanelSection label="Calls out to" testId="scheduler-panel-services">
      {services.length === 0 ? (
        <p className="text-[13px] text-plex-text-secondary" data-testid="scheduler-panel-services-none">
          Nothing outside PhotoCast. Database only.
        </p>
      ) : (
        <ul className="flex flex-wrap gap-1.5" data-testid="scheduler-panel-services-list">
          {services.map((name) => <ServiceChip key={name} name={name} />)}
        </ul>
      )}
    </PanelSection>
  );
}

CallsOutSection.propTypes = { services: PropTypes.arrayOf(PropTypes.string).isRequired };

/** The dependency edges, in order: the catalogue's field and the words that label its jobs. */
const EDGES = [
  ['resultsCollectedBy', 'Results collected by'],
  ['collectsResultsFrom', 'Collects results from'],
  ['watches', 'Watches'],
  ['watchedBy', 'Watched by'],
  ['backstoppedBy', 'Backstopped by'],
  ['backstops', 'Backstop for'],
  ['staggeredBefore', 'Followed 30 min later by'],
  ['staggeredAfter', 'Runs 30 min after'],
];

/**
 * The Connected jobs section: each non-empty edge with its label, every job a button that opens that job in the
 * panel. A key no job answers to (a retired job) is plain text. Nothing is drawn when the job has no edges.
 *
 * @param {object} props
 * @param {object} props.catalogue the job's catalogue entry
 * @param {Array<object>} props.jobs every served job, for names
 * @param {(jobKey: string) => void} props.onSelectJob opens a job's panel
 */
export function ConnectedJobsSection({ catalogue, jobs, onSelectJob }) {
  const present = EDGES.filter(([field]) => (catalogue[field] ?? []).length > 0);
  if (present.length === 0) return null;
  const nameOf = (key) => jobs.find((j) => j.jobKey === key)?.displayName ?? null;
  return (
    <PanelSection label="Connected jobs" testId="scheduler-panel-connected">
      <dl className="flex flex-col gap-2.5 text-[13px]">
        {present.map(([field, label]) => (
          <div key={field} data-testid={`scheduler-edge-${field}`}>
            <dt className="text-xs text-plex-text-secondary">{label}</dt>
            <dd className="flex flex-wrap gap-x-3">
              {catalogue[field].map((key) => {
                const name = nameOf(key);
                return name == null ? (
                  <span key={key} className="font-mono text-[12.5px] text-plex-text-muted">{key}</span>
                ) : (
                  <button
                    key={key}
                    type="button"
                    onClick={() => onSelectJob(key)}
                    className="cursor-pointer text-tide hover:underline focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
                    data-testid={`scheduler-connected-${key}`}
                  >
                    {name}
                  </button>
                );
              })}
            </dd>
          </div>
        ))}
      </dl>
    </PanelSection>
  );
}

ConnectedJobsSection.propTypes = {
  catalogue: PropTypes.object.isRequired,
  jobs: PropTypes.arrayOf(PropTypes.object).isRequired,
  onSelectJob: PropTypes.func.isRequired,
};

/**
 * The Technical section: the job key, the config key that gates it and the stored description, in mono.
 *
 * @param {object} props
 * @param {object} props.job the served job
 */
export function TechnicalSection({ job }) {
  const items = [{ label: 'Job key', value: job.jobKey, mono: true, testId: 'scheduler-technical-key' }];
  if (job.configSource) items.push({ label: 'Requires', value: job.configSource, mono: true, testId: 'scheduler-technical-config' });
  if (job.description) items.push({ label: 'Description', value: job.description, mono: true, testId: 'scheduler-technical-description' });
  return (
    <PanelSection label="Technical" testId="scheduler-panel-technical">
      <KvList items={items} />
    </PanelSection>
  );
}

TechnicalSection.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    configSource: PropTypes.string,
    description: PropTypes.string,
  }).isRequired,
};
