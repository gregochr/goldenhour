import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { getDispositionBreakdown } from '../api/metricsApi';

/**
 * Per-category visual cue + display label.
 *
 * Order in this array is the order categories appear in the summary row,
 * which deliberately puts EVALUATED first and the spec's primary skip
 * categories before the rarer ones.
 */
const CATEGORY_DISPLAY = [
  { key: 'EVALUATED',                  label: 'Evaluated',          tone: 'text-green-400' },
  { key: 'SKIPPED_HARD_CONSTRAINT',    label: 'Hard constraint',    tone: 'text-orange-400' },
  { key: 'SKIPPED_TRIAGED',            label: 'Triaged',            tone: 'text-yellow-400' },
  { key: 'SKIPPED_CACHED',             label: 'Cached',             tone: 'text-blue-400' },
  { key: 'SKIPPED_PAST_DATE',          label: 'Past date',          tone: 'text-plex-text-muted' },
  { key: 'SKIPPED_STABILITY',          label: 'Stability gate',     tone: 'text-purple-400' },
  { key: 'SKIPPED_UNKNOWN_LOCATION',   label: 'Unknown location',   tone: 'text-red-400' },
  { key: 'SKIPPED_ERROR',              label: 'Error',              tone: 'text-red-400' },
  { key: 'SKIPPED_NO_REFRESH_NEEDED',  label: 'No refresh needed',  tone: 'text-plex-text-muted' },
];

const KNOWN_KEYS = new Set(CATEGORY_DISPLAY.map((c) => c.key));

/**
 * Disposition Breakdown section of the Job Run detail view (V101).
 *
 * <p>Renders a summary row reconciling "N candidates" across every disposition
 * category, with click-to-expand drill-down per category showing each affected
 * location and the human-readable skip detail. Self-hides when the run has no
 * disposition data (non-batch runs and the cycle's 2nd/3rd/4th bucket job runs).
 *
 * <p>Unknown forward-compat categories (e.g. a future
 * {@code SKIPPED_NO_REFRESH_NEEDED} value written by a newer deployment) pass
 * through unchanged with a generic display label rather than crashing.
 */
const DispositionBreakdown = ({ jobRunId }) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null); // disposition key currently expanded

  useEffect(() => {
    let cancelled = false;
    const fetchData = async () => {
      try {
        const response = await getDispositionBreakdown(jobRunId);
        if (!cancelled) {
          setData(response.data);
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
      .map((k) => ({ key: k, label: k, tone: 'text-plex-text-muted' })),
  ];

  return (
    <div
      className="mt-3 pt-3 border-t border-plex-border"
      data-testid="disposition-breakdown"
    >
      <h5 className="font-medium text-plex-text text-xs mb-2">
        Disposition Breakdown
      </h5>

      {/* Summary line — the reconciliation row the spec's acceptance criteria
          depends on: totals must visibly add up on screen. */}
      <div
        className="text-xs text-plex-text-muted mb-2"
        data-testid="disposition-summary"
      >
        <span className="font-semibold text-plex-text">
          {data.totalCount}
        </span>
        {' candidate'}
        {data.totalCount === 1 ? '' : 's'}
        {' considered'}
      </div>

      <div className="space-y-1">
        {orderedCategories.map(({ key, label, tone }) => {
          const count = data.countsByDisposition[key] || 0;
          const isExpanded = expanded === key;
          const entries = data.entries.filter((e) => e.disposition === key);
          return (
            <div
              key={key}
              className="bg-plex-surface rounded border border-plex-border text-xs"
              data-testid={`disposition-row-${key}`}
            >
              <button
                type="button"
                onClick={() => setExpanded(isExpanded ? null : key)}
                className="w-full p-2 flex justify-between items-center hover:bg-plex-bg transition-colors"
              >
                <span className={`font-medium ${tone}`}>{label}</span>
                <span className="flex items-center gap-2">
                  <span className="font-semibold text-plex-text">{count}</span>
                  <span className="text-plex-text-muted">
                    {isExpanded ? '▾' : '▸'}
                  </span>
                </span>
              </button>
              {isExpanded && (
                <div
                  className="border-t border-plex-border p-2 space-y-1"
                  data-testid={`disposition-entries-${key}`}
                >
                  {entries.map((entry) => (
                    <div
                      key={`${entry.locationName}-${entry.evaluationDate}-${entry.eventType}`}
                      className="text-plex-text-muted"
                    >
                      <span className="text-plex-text">{entry.locationName}</span>
                      <span className="text-plex-text-muted">
                        {' · '}{entry.evaluationDate}{' · '}{entry.eventType}
                      </span>
                      {entry.detail && (
                        <div className="text-plex-text-muted pl-2 mt-0.5">
                          {entry.detail}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

DispositionBreakdown.propTypes = {
  jobRunId: PropTypes.number.isRequired,
};

export default DispositionBreakdown;
