import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { formatCostUsd } from '../utils/formatCost';

/**
 * Summary cards showing aggregated job run statistics.
 *
 * Displays:
 * - Total runs by job type
 * - Overall success rate
 * - Slowest service (by avg latency)
 * - Evaluation count
 * - Total operational cost (token-based with GBP/USD)
 *
 * Toggle between "Today" and "Last 7 Days" to filter the data.
 */
const MetricsSummary = ({ runs, apiCalls }) => {
  const [range, setRange] = useState('7d');

  const todayStr = useMemo(() => new Date().toLocaleDateString('en-CA'), []);

  const filteredRuns = useMemo(() => {
    if (!runs || runs.length === 0) return [];
    if (range === 'today') {
      return runs.filter((r) => r.startedAt && r.startedAt.slice(0, 10) === todayStr);
    }
    // Last 7 days
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - 7);
    return runs.filter((r) => r.startedAt && new Date(r.startedAt) >= cutoff);
  }, [runs, range, todayStr]);

  const filteredRunIds = useMemo(() => new Set(filteredRuns.map((r) => r.id)), [filteredRuns]);

  const filteredApiCalls = useMemo(() => {
    if (!apiCalls || apiCalls.length === 0) return [];
    return apiCalls.filter((c) => filteredRunIds.has(c.jobRunId));
  }, [apiCalls, filteredRunIds]);

  const rangeLabel = range === 'today' ? 'today' : 'the last 7 days';

  if (!runs || runs.length === 0) {
    return (
      <div className="card">
        <h3 className="text-lg font-semibold text-plex-text mb-4">Summary</h3>
        <p className="text-plex-text-secondary">No job runs available</p>
      </div>
    );
  }

  // Calculate statistics from filtered runs
  const totalRuns = filteredRuns.length;
  const totalSucceeded = filteredRuns.reduce((sum, run) => sum + (run.succeeded || 0), 0);
  const totalFailed = filteredRuns.reduce((sum, run) => sum + (run.failed || 0), 0);
  const totalEvaluations = totalSucceeded + totalFailed;
  const successRate = totalEvaluations > 0
    ? (totalSucceeded / totalEvaluations) * 100
    : 0;

  // Use the most recent exchange rate from the runs that have one
  const latestRunWithRate = filteredRuns.find((r) => r.exchangeRateGbpPerUsd);
  const exchangeRate = latestRunWithRate?.exchangeRateGbpPerUsd;

  // Aggregate costs — per run, prefer micro-dollars (token-based) when available,
  // fall back to legacy pence for older runs, then combine into a single GBP total
  const totalCostMicroDollars = filteredRuns.reduce(
    (sum, run) => sum + (run.totalCostMicroDollars || 0), 0);
  const legacyOnlyPence = filteredRuns.reduce((sum, run) => {
    if ((run.totalCostMicroDollars || 0) > 0) return sum;
    return sum + (run.totalCostPence || 0);
  }, 0);
  const combinedCostGbp =
    (totalCostMicroDollars > 0 && exchangeRate
      ? (totalCostMicroDollars / 1_000_000) * exchangeRate
      : 0)
    + legacyOnlyPence / 1000;

  const hasCost = combinedCostGbp > 0;
  const hasMixedPricing = totalCostMicroDollars > 0 && legacyOnlyPence > 0;

  // Count runs by job type
  const runsByType = filteredRuns.reduce((acc, run) => {
    const label = run.runType || run.jobName || 'Unknown';
    acc[label] = (acc[label] || 0) + 1;
    return acc;
  }, {});

  // Find slowest service by average duration
  let slowestService = null;
  if (filteredApiCalls.length > 0) {
    const serviceStats = filteredApiCalls.reduce((acc, call) => {
      if (!acc[call.service]) {
        acc[call.service] = { totalDuration: 0, count: 0 };
      }
      acc[call.service].totalDuration += call.durationMs || 0;
      acc[call.service].count += 1;
      return acc;
    }, {});

    let maxAvg = 0;
    Object.entries(serviceStats).forEach(([service, stats]) => {
      const avg = stats.totalDuration / stats.count;
      if (avg > maxAvg) {
        maxAvg = avg;
        slowestService = { service, avgDuration: Math.round(avg) };
      }
    });
  }

  return (
    <div>
      {/* Time range toggle */}
      <div className="flex items-center gap-4 mb-4">
        <span className="text-sm text-plex-text-secondary">Show:</span>
        <div className="flex gap-1 bg-plex-surface-light rounded-lg p-0.5">
          <button
            onClick={() => setRange('today')}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              range === 'today'
                ? 'bg-plex-gold text-gray-900'
                : 'text-plex-text-secondary hover:text-plex-text'
            }`}
            data-testid="summary-range-today"
          >
            Today
          </button>
          <button
            onClick={() => setRange('7d')}
            className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
              range === '7d'
                ? 'bg-plex-gold text-gray-900'
                : 'text-plex-text-secondary hover:text-plex-text'
            }`}
            data-testid="summary-range-7d"
          >
            Last 7 Days
          </button>
        </div>
      </div>

      {filteredRuns.length === 0 ? (
        <div className="card">
          <p className="text-plex-text-secondary">No job runs {rangeLabel}</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          {/* Total Runs */}
          <div className="card">
            <div className="text-sm font-medium text-plex-text-secondary">Total Runs</div>
            <p className="text-xs text-plex-text-muted mt-1">Forecast job runs {rangeLabel}, grouped by type</p>
            <div className="mt-3 text-3xl font-bold text-plex-text">{totalRuns}</div>
            <div className="mt-2 text-xs text-plex-text-muted">
              {Object.entries(runsByType).map(([type, count]) => (
                <div key={type}>{type}: {count}</div>
              ))}
            </div>
          </div>

          {/* Success Rate */}
          <div className="card">
            <div className="text-sm font-medium text-plex-text-secondary">Success Rate</div>
            <p className="text-xs text-plex-text-muted mt-1">Evaluations that completed without error</p>
            <div className="mt-3 text-3xl font-bold text-plex-text">{successRate.toFixed(1)}%</div>
            <div className="mt-2 text-xs text-plex-text-muted">
              {totalSucceeded.toLocaleString()} succeeded, {totalFailed.toLocaleString()} failed
            </div>
          </div>

          {/* Slowest Service */}
          <div className="card">
            <div className="text-sm font-medium text-plex-text-secondary">Slowest Service</div>
            <p className="text-xs text-plex-text-muted mt-1">Highest average response time</p>
            {slowestService ? (
              <>
                <div className="mt-3 text-lg font-semibold text-plex-text truncate" title={slowestService.service}>{slowestService.service.replace(/_/g, ' ')}</div>
                <div className="mt-1 text-sm text-plex-gold">{(slowestService.avgDuration / 1000).toFixed(1)}s avg</div>
              </>
            ) : (
              <div className="mt-3 text-plex-text-muted">No data</div>
            )}
          </div>

          {/* Evaluation Count */}
          <div className="card">
            <div className="text-sm font-medium text-plex-text-secondary">Evaluations</div>
            <p className="text-xs text-plex-text-muted mt-1">Location-date combinations evaluated</p>
            <div className="mt-3 text-3xl font-bold text-plex-text">{totalEvaluations.toLocaleString()}</div>
            <div className="mt-1 text-xs text-plex-text-muted">in {totalRuns} runs</div>
          </div>

          {/* Total Cost */}
          {hasCost && (
            <div className="card">
              <div className="text-sm font-medium text-plex-text-secondary">Total Cost</div>
              <p className="text-xs text-plex-text-muted mt-1">
                {hasMixedPricing
                  ? 'Token-based + legacy flat-rate pricing'
                  : totalCostMicroDollars > 0
                    ? 'Token-based pricing (actual usage)'
                    : 'Estimated flat-rate pricing'}
              </p>
              <div className="mt-3 text-3xl font-bold text-plex-gold">
                {combinedCostGbp < 0.01
                  ? `${(combinedCostGbp * 100).toFixed(2)}p`
                  : `£${combinedCostGbp.toFixed(2)}`}
              </div>
              {totalCostMicroDollars > 0 && (
                <div className="mt-1 text-sm text-plex-text-muted">
                  {formatCostUsd(totalCostMicroDollars)}
                  {hasMixedPricing && ' (token-based only)'}
                </div>
              )}
              <div className="mt-2 text-xs text-plex-text-muted">
                {totalRuns} runs, {totalEvaluations} evaluations
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

MetricsSummary.propTypes = {
  runs: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.number.isRequired,
      jobName: PropTypes.string,
      runType: PropTypes.string,
      startedAt: PropTypes.string.isRequired,
      durationMs: PropTypes.number,
      succeeded: PropTypes.number,
      failed: PropTypes.number,
      totalCostPence: PropTypes.number,
      totalCostMicroDollars: PropTypes.number,
      exchangeRateGbpPerUsd: PropTypes.number,
    })
  ).isRequired,
  apiCalls: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.number,
      jobRunId: PropTypes.number,
      service: PropTypes.string,
      durationMs: PropTypes.number,
    })
  ),
};

export default MetricsSummary;
