import React from 'react';
import PropTypes from 'prop-types';

/**
 * 7-day summary card showing aggregated job run statistics.
 *
 * Displays:
 * - Total runs by job type
 * - Overall success rate
 * - Slowest service (by avg latency)
 * - Common error types
 */
const MetricsSummary = ({ runs, apiCalls }) => {
  if (!runs || runs.length === 0) {
    return (
      <div className="bg-white rounded-lg shadow-sm p-6 border border-gray-200">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">7-Day Summary</h3>
        <p className="text-gray-500">No job runs in the past 7 days</p>
      </div>
    );
  }

  // Calculate statistics
  const totalRuns = runs.length;
  const totalSucceeded = runs.reduce((sum, run) => sum + (run.succeeded || 0), 0);
  const totalFailed = runs.reduce((sum, run) => sum + (run.failed || 0), 0);
  const totalEvaluations = totalSucceeded + totalFailed;
  const successRate = totalEvaluations > 0
    ? Math.round((totalSucceeded / totalEvaluations) * 100)
    : 0;

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
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <div className="text-sm font-medium text-gray-500">Total Runs</div>
        <p className="text-xs text-gray-400 mt-1">Number of forecast job runs over the last 7 days, grouped by job type (SONNET, HAIKU, WILDLIFE, TIDE)</p>
        <div className="mt-3 text-3xl font-bold text-gray-900">{totalRuns}</div>
        <div className="mt-2 text-xs text-gray-600">
          {Object.entries(runsByType).map(([type, count]) => (
            <div key={type}>{type}: {count}</div>
          ))}
        </div>
      </div>

      {/* Success Rate */}
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <div className="text-sm font-medium text-gray-500">Success Rate</div>
        <p className="text-xs text-gray-400 mt-1">Percentage of location evaluations that completed without error. Failures may indicate API issues, bad data, or transient network problems</p>
        <div className="mt-3 text-3xl font-bold text-gray-900">{successRate}%</div>
        <div className="mt-2 text-xs text-gray-600">
          {totalSucceeded} succeeded, {totalFailed} failed
        </div>
      </div>

      {/* Slowest Service */}
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <div className="text-sm font-medium text-gray-500">Slowest Service</div>
        <p className="text-xs text-gray-400 mt-1">External service with the highest average response time. Long latencies may indicate API rate limits, degradation, or geographic latency</p>
        {slowestService ? (
          <>
            <div className="mt-3 text-lg font-semibold text-gray-900">{slowestService.service}</div>
            <div className="mt-1 text-sm text-orange-600">{slowestService.avgDuration}ms avg</div>
          </>
        ) : (
          <div className="mt-3 text-gray-500">No data</div>
        )}
      </div>

      {/* Evaluation Count */}
      <div className="bg-white rounded-lg shadow-sm p-4 border border-gray-200">
        <div className="text-sm font-medium text-gray-500">Evaluations</div>
        <p className="text-xs text-gray-400 mt-1">Total location-date-model combinations evaluated. One location across 8 days = 8 evaluations (SONNET + HAIKU)</p>
        <div className="mt-3 text-3xl font-bold text-gray-900">{totalEvaluations}</div>
        <div className="mt-1 text-xs text-gray-600">in {totalRuns} runs</div>
      </div>
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
