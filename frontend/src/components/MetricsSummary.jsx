import React from 'react';
import PropTypes from 'prop-types';
import { formatCostGbp, formatCostUsd } from '../utils/formatCost';

/**
 * 7-day summary card showing aggregated job run statistics.
 *
 * Displays:
 * - Total runs by job type
 * - Overall success rate
 * - Slowest service (by avg latency)
 * - Evaluation count
 * - Total operational cost (token-based with GBP/USD)
 */
const MetricsSummary = ({ runs, apiCalls }) => {
  if (!runs || runs.length === 0) {
    return (
      <div className="card">
        <h3 className="text-lg font-semibold text-plex-text mb-4">7-Day Summary</h3>
        <p className="text-plex-text-secondary">No job runs in the past 7 days</p>
      </div>
    );
  }

  // Calculate statistics
  const totalRuns = runs.length;
  const totalSucceeded = runs.reduce((sum, run) => sum + (run.succeeded || 0), 0);
  const totalFailed = runs.reduce((sum, run) => sum + (run.failed || 0), 0);
  const totalEvaluations = totalSucceeded + totalFailed;
  const successRate = totalEvaluations > 0
    ? (totalSucceeded / totalEvaluations) * 100
    : 0;

  // Aggregate costs — prefer micro-dollars when available
  const totalCostMicroDollars = runs.reduce(
    (sum, run) => sum + (run.totalCostMicroDollars || 0), 0);
  const totalCostPence = runs.reduce(
    (sum, run) => sum + (run.totalCostPence || 0), 0);

  // Use the most recent exchange rate from the runs that have one
  const latestRunWithRate = runs.find((r) => r.exchangeRateGbpPerUsd);
  const exchangeRate = latestRunWithRate?.exchangeRateGbpPerUsd;

  const hasCost = totalCostMicroDollars > 0 || totalCostPence > 0;

  // Count runs by job type
  const runsByType = runs.reduce((acc, run) => {
    acc[run.jobName] = (acc[run.jobName] || 0) + 1;
    return acc;
  }, {});

  // Find slowest service by average duration
  let slowestService = null;
  if (apiCalls && apiCalls.length > 0) {
    const serviceStats = apiCalls.reduce((acc, call) => {
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
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
      {/* Total Runs */}
      <div className="card">
        <div className="text-sm font-medium text-plex-text-secondary">Total Runs</div>
        <p className="text-xs text-plex-text-muted mt-1">Number of forecast job runs over the last 7 days, grouped by job type</p>
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
        <p className="text-xs text-plex-text-muted mt-1">Percentage of location evaluations that completed without error</p>
        <div className="mt-3 text-3xl font-bold text-plex-text">{successRate.toFixed(3)}%</div>
        <div className="mt-2 text-xs text-plex-text-muted">
          {totalSucceeded.toLocaleString()} succeeded, {totalFailed.toLocaleString()} failed
        </div>
      </div>

      {/* Slowest Service */}
      <div className="card">
        <div className="text-sm font-medium text-plex-text-secondary">Slowest Service</div>
        <p className="text-xs text-plex-text-muted mt-1">External service with the highest average response time</p>
        {slowestService ? (
          <>
            <div className="mt-3 text-lg font-semibold text-plex-text">{slowestService.service}</div>
            <div className="mt-1 text-sm text-plex-gold">{(slowestService.avgDuration / 1000).toFixed(1)}s avg</div>
          </>
        ) : (
          <div className="mt-3 text-plex-text-muted">No data</div>
        )}
      </div>

      {/* Evaluation Count */}
      <div className="card">
        <div className="text-sm font-medium text-plex-text-secondary">Evaluations</div>
        <p className="text-xs text-plex-text-muted mt-1">Total location-date combinations evaluated</p>
        <div className="mt-3 text-3xl font-bold text-plex-text">{totalEvaluations.toLocaleString()}</div>
        <div className="mt-1 text-xs text-plex-text-muted">in {totalRuns} runs</div>
      </div>

      {/* Total Cost */}
      {hasCost && (
        <div className="card">
          <div className="text-sm font-medium text-plex-text-secondary">Total Cost</div>
          <p className="text-xs text-plex-text-muted mt-1">
            {totalCostMicroDollars > 0 ? 'Token-based pricing (actual usage)' : 'Estimated flat-rate pricing'}
          </p>
          <div className="mt-3 text-3xl font-bold text-plex-gold">
            {formatCostGbp(totalCostMicroDollars, exchangeRate, totalCostPence)}
          </div>
          {totalCostMicroDollars > 0 && (
            <div className="mt-1 text-sm text-plex-text-muted">
              {formatCostUsd(totalCostMicroDollars)}
            </div>
          )}
          <div className="mt-2 text-xs text-plex-text-muted">
            {totalRuns} runs, {totalEvaluations} evaluations
          </div>
        </div>
      )}
    </div>
  );
};

MetricsSummary.propTypes = {
  runs: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.number.isRequired,
      jobName: PropTypes.string.isRequired,
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
      service: PropTypes.string,
      durationMs: PropTypes.number,
    })
  ),
};

export default MetricsSummary;
