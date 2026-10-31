import React, { useState, useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { getDispositionBreakdown } from '../api/metricsApi';
import PanelSection from './data/PanelSection.jsx';
import { formatIsoDay } from '../utils/jobRunPanel.js';

/**
 * Per-category swatch colour + display label.
 *
 * Order in this array is the order categories appear in the summary rows,
 * which deliberately puts EVALUATED first (FORCE_EVALUATED, its stability-gate override, right after it),
 * SUBMISSION_FAILED next (a candidate this cycle meant to send to Claude but couldn't, so it reads
 * as close kin to EVALUATED rather than buried among the SKIPPED_* triage
 * reasons), and the spec's primary skip categories before the rarer ones.
 *
 * <p>Swatches follow the Runs handoff: Evaluated go; Submission failed, Error and Unknown location coral; Hard
 * constraint, Triaged and No prompt amber; Cached tide; Past date, Stability and No refresh needed ink-3.
 * Force evaluated reads as evaluated (go), and Travel day, a skip by the admin's own choice, as ink-3.
 */
const CATEGORY_DISPLAY = [
  { key: 'EVALUATED',                  label: 'Evaluated',          swatch: 'bg-plex-ok' },
  { key: 'FORCE_EVALUATED',            label: 'Force evaluated',    swatch: 'bg-plex-ok' },
  { key: 'SUBMISSION_FAILED',          label: 'Submission failed',  swatch: 'bg-plex-coral' },
  { key: 'SKIPPED_HARD_CONSTRAINT',    label: 'Hard constraint',    swatch: 'bg-verdict-marginal' },
  { key: 'SKIPPED_TRIAGED',            label: 'Triaged',            swatch: 'bg-verdict-marginal' },
  { key: 'SKIPPED_NO_PROMPT',          label: 'No prompt',          swatch: 'bg-verdict-marginal' },
  { key: 'SKIPPED_CACHED',             label: 'Cached',             swatch: 'bg-tide' },
  { key: 'SKIPPED_PAST_DATE',          label: 'Past date',          swatch: 'bg-plex-text-muted' },
  { key: 'SKIPPED_TRAVEL_DAY',         label: 'Travel day',         swatch: 'bg-plex-text-muted' },
  { key: 'SKIPPED_STABILITY',          label: 'Stability gate',     swatch: 'bg-plex-text-muted' },
  { key: 'SKIPPED_UNKNOWN_LOCATION',   label: 'Unknown location',   swatch: 'bg-plex-coral' },
  { key: 'SKIPPED_ERROR',              label: 'Error',              swatch: 'bg-plex-coral' },
  { key: 'SKIPPED_NO_REFRESH_NEEDED',  label: 'No refresh needed',  swatch: 'bg-plex-text-muted' },
];

const KNOWN_KEYS = new Set(CATEGORY_DISPLAY.map((c) => c.key));

/** How many entries an opened category lists before "+{n} more". */
const PREVIEW_ENTRIES = 3;

/**
 * Dispositions section of the job-run panel (V101), restyled for the Runs handoff.
 *
 * <p>Renders "{n} candidates" beside the section label, a 10px stacked bar (one segment per category, 2px gaps),
 * then one row per category (swatch, label, count, chevron). Opening a row lists its first three entries
 * ("{location} · {date} · {event}", the detail in ink-3) and "+{n} more", which expands the full list in place.
 * Self-hides when the run has no disposition data (non-batch runs and the cycle's 2nd/3rd/4th bucket job runs).
 *
 * <p>The category order and the forward-compat fallback are unchanged: an unknown category (e.g. a future value
 * written by a newer deployment) passes through with its raw key as the label rather than crashing.
 *
 * @param {object} props
 * @param {number} props.jobRunId the job run to load the breakdown for
 * @param {(totalCount: number) => void} [props.onLoaded] called once the breakdown has loaded, with its total, so
 *   the panel can tell whether it has anything at all to show
 */
const DispositionBreakdown = ({ jobRunId, onLoaded = undefined }) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null); // disposition key currently expanded
  const [showAll, setShowAll] = useState(false); // the opened category lists every entry
  // The first entry "+{n} more" reveals: focus moves to it, since the button that was pressed is gone.
  const firstRevealed = useRef(null);
  useEffect(() => {
    if (showAll) firstRevealed.current?.focus({ preventScroll: true });
  }, [showAll]);

  useEffect(() => {
    let cancelled = false;
    const fetchData = async () => {
      try {
        const response = await getDispositionBreakdown(jobRunId);
        if (!cancelled) {
          setData(response.data);
          onLoaded?.(response.data?.totalCount ?? 0);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err.message || 'Failed to load disposition breakdown');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };
    fetchData();
    return () => {
      cancelled = true;
    };
    // `onLoaded` is a notification, never an input: a parent passing a new function must not refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobRunId]);

  if (loading || error) {
    // Loading is silent — the section is auxiliary; if the parent shows its
    // own loading state, we don't add a second one. Errors are also silent
    // because dispositions are a diagnostic detail; the parent's API call
    // breakdown is the primary content.
    return null;
  }
  if (!data || data.totalCount === 0) {
    return null;
  }

  const orderedCategories = [
    ...CATEGORY_DISPLAY.filter((c) => (data.countsByDisposition[c.key] || 0) > 0),
    // Forward-compat: any disposition key the deployment doesn't recognise
    ...Object.keys(data.countsByDisposition)
      .filter((k) => !KNOWN_KEYS.has(k))
      .map((k) => ({ key: k, label: k, swatch: 'bg-plex-text-muted' })),
  ];

  const toggle = (key) => {
    setExpanded(expanded === key ? null : key);
    setShowAll(false);
  };

  return (
    <PanelSection
      label="Dispositions"
      testId="disposition-breakdown"
      action={(
        <span className="font-mono text-[11px] text-plex-text-muted" data-testid="disposition-summary">
          <span className="text-plex-text">{data.totalCount}</span>
          {' candidate'}
          {data.totalCount === 1 ? '' : 's'}
        </span>
      )}
    >
      {/* The stacked bar: decorative, the rows below carry every number. */}
      <div className="mb-2 flex h-2.5 gap-0.5 overflow-hidden rounded-full" aria-hidden="true" data-testid="disposition-bar">
        {orderedCategories.map(({ key, swatch }) => (
          <span
            key={key}
            className={`h-full min-w-1 grow-(--n) basis-0 ${swatch}`}
            style={{ '--n': data.countsByDisposition[key] || 0 }}
          />
        ))}
      </div>

      <div>
        {orderedCategories.map(({ key, label, swatch }) => {
          const count = data.countsByDisposition[key] || 0;
          const isExpanded = expanded === key;
          const entries = data.entries.filter((e) => e.disposition === key);
          const shown = isExpanded && !showAll ? entries.slice(0, PREVIEW_ENTRIES) : entries;
          const hidden = entries.length - shown.length;
          return (
            <div
              key={key}
              className="border-b border-plex-border/60 text-[13px] last:border-b-0"
              data-testid={`disposition-row-${key}`}
            >
              <button
                type="button"
                onClick={() => toggle(key)}
                aria-expanded={isExpanded}
                className="flex min-h-9 w-full cursor-pointer items-center gap-2.5 py-2 text-left transition-colors hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
              >
                <span className={`size-2 shrink-0 rounded-[2px] ${swatch}`} aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate text-plex-text">{label}</span>
                <span className="font-mono text-[12.5px] text-plex-text">{count}</span>
                <span className="w-3 text-center text-plex-text-muted" aria-hidden="true">
                  {isExpanded ? '▾' : '▸'}
                </span>
              </button>
              {isExpanded && (
                <div className="space-y-2 pb-2.5 pl-[18px]" data-testid={`disposition-entries-${key}`}>
                  {shown.map((entry, index) => (
                    // Two entries can share a location, date and event (a retried slot), so the index is part of the key.
                    <div
                      key={`${entry.locationName}-${entry.evaluationDate}-${entry.eventType}-${index}`}
                      ref={showAll && index === PREVIEW_ENTRIES ? firstRevealed : undefined}
                      tabIndex={showAll && index === PREVIEW_ENTRIES ? -1 : undefined}
                      className="min-w-0 break-words focus:outline-hidden"
                      data-testid="disposition-entry"
                    >
                      <span className="text-plex-text">{entry.locationName}</span>
                      <span className="text-plex-text-secondary">
                        {' · '}{formatIsoDay(entry.evaluationDate)}{' · '}{String(entry.eventType).toLowerCase()}
                      </span>
                      {entry.detail && (
                        <div className="mt-0.5 text-xs text-plex-text-muted" data-testid="disposition-detail">
                          {entry.detail}
                        </div>
                      )}
                    </div>
                  ))}
                  {hidden > 0 && (
                    <button
                      type="button"
                      onClick={() => setShowAll(true)}
                      className="text-xs text-tide hover:text-tide-light focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
                      data-testid={`disposition-more-${key}`}
                    >
                      +{hidden} more
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </PanelSection>
  );
};

DispositionBreakdown.propTypes = {
  jobRunId: PropTypes.number.isRequired,
  onLoaded: PropTypes.func,
};

export default DispositionBreakdown;
