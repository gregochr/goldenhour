import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { getApiCalls } from '../api/metricsApi';

/**
 * Expandable detail view for a job run showing all API calls.
 *
 * Displays per-service breakdown:
 * - Service name
 * - Call count
 * - Average duration
 * - Error count and rate
 */
const JobRunDetail = ({ jobRun }) => {
  const [apiCalls, setApiCalls] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchApiCalls = async () => {
      try {
        const response = await getApiCalls(jobRun.id);
        setApiCalls(response.data || []);
      } catch (err) {
        setError(err.message || 'Failed to load API calls');
      } finally {
        setLoading(false);
      }
    };

    fetchApiCalls();
  }, [jobRun.id]);

  if (loading) {
    return <div className="text-gray-500 text-sm">Loading...</div>;
  }

  if (error) {
    return <div className="text-red-600 text-sm">Error: {error}</div>;
  }

  // Group API calls by service
  const serviceStats = apiCalls.reduce((acc, call) => {
    if (!acc[call.service]) {
      acc[call.service] = {
        calls: [],
        totalDuration: 0,
        totalCost: 0,
        count: 0,
        errorCount: 0,
      };
    }
    acc[call.service].calls.push(call);
    acc[call.service].totalDuration += call.durationMs || 0;
    acc[call.service].totalCost += call.costPence || 0;
    acc[call.service].count += 1;
    if (!call.succeeded) {
      acc[call.service].errorCount += 1;
    }
    return acc;
  }, {});

  // Calculate grand total cost
  const totalCostPence = Object.values(serviceStats).reduce((sum, stats) => sum + stats.totalCost, 0);

  // Calculate evaluation summary
  const locationsProcessed = jobRun.locationsProcessed || 0;
  const minDate = jobRun.minTargetDate;
  const maxDate = jobRun.maxTargetDate;
  const daysCount = minDate && maxDate
    ? Math.floor((new Date(maxDate) - new Date(minDate)) / (1000 * 60 * 60 * 24)) + 1
    : 0;

  return (
    <div className="mt-4 space-y-3 bg-gray-50 p-4 rounded-lg border border-gray-200">
      {/* Evaluation Summary */}
      {(locationsProcessed > 0 || daysCount > 0) && (
        <div className="mb-4 pb-4 border-b border-gray-200">
          <h4 className="font-semibold text-gray-900 text-sm mb-2">Evaluation Summary</h4>
          <div className="grid grid-cols-2 gap-3 text-sm">
            {locationsProcessed > 0 && (
              <div className="bg-white p-2 rounded border border-gray-200">
                <div className="text-gray-600 text-xs">Locations</div>
                <div className="font-semibold text-gray-900">{locationsProcessed}</div>
              </div>
            )}
            {daysCount > 0 && (
              <div className="bg-white p-2 rounded border border-gray-200">
                <div className="text-gray-600 text-xs">Days</div>
                <div className="font-semibold text-gray-900">
                  {daysCount} <span className="text-gray-500 text-xs">({minDate} to {maxDate})</span>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      <h4 className="font-semibold text-gray-900 text-sm">API Call Breakdown</h4>

      {Object.entries(serviceStats).length === 0 ? (
        <p className="text-gray-500 text-sm">No API calls recorded</p>
      ) : (
        <div className="space-y-2">
          {Object.entries(serviceStats).map(([service, stats]) => (
            <div
              key={service}
              className="bg-white p-3 rounded border border-gray-200 text-sm"
            >
              <div className="flex justify-between items-start">
                <div>
                  <div className="font-medium text-gray-900">{service}</div>
                  <div className="text-xs text-gray-600 mt-1">
                    {stats.count} calls, avg {Math.round(stats.totalDuration / stats.count)}ms
                  </div>
                  <div className="text-xs text-blue-600 mt-1 font-semibold">
                    Cost: £{(stats.totalCost / 1000).toFixed(3)}
                  </div>
                  {stats.errorCount > 0 && (
                    <div className="text-xs text-red-600 mt-1">
                      {stats.errorCount} failures ({Math.round((stats.errorCount / stats.count) * 100)}%)
                    </div>
                  )}
                </div>
                <div className="flex gap-1">
                  {stats.errorCount > 0 && (
                    <span className="inline-block px-2 py-1 rounded-full bg-red-100 text-red-700 text-xs font-medium">
                      {stats.errorCount} errors
                    </span>
                  )}
                  {stats.errorCount === 0 && (
                    <span className="inline-block px-2 py-1 rounded-full bg-green-100 text-green-700 text-xs font-medium">
                      All OK
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Anthropic breakdown by model, day, and event */}
      {apiCalls.some((call) => call.service === 'ANTHROPIC') && (
        <div className="mt-3 pt-3 border-t border-gray-200">
          <h5 className="font-medium text-gray-900 text-xs mb-2">Anthropic Evaluation Breakdown</h5>
          {(() => {
            const anthropicCalls = apiCalls.filter((call) => call.service === 'ANTHROPIC');
            const breakdown = anthropicCalls.reduce((acc, call) => {
              if (!call.targetDate || !call.targetType || !call.evaluationModel) {
                return acc;
              }
              const key = `${call.targetDate}|${call.targetType}|${call.evaluationModel}`;
              if (!acc[key]) {
                acc[key] = {
                  date: call.targetDate,
                  event: call.targetType,
                  model: call.evaluationModel,
                  count: 0,
                };
              }
              acc[key].count += 1;
              return acc;
            }, {});

            const sortedKeys = Object.keys(breakdown)
              .sort((a, b) => {
                const [dateA, eventA, modelA] = a.split('|');
                const [dateB, eventB, modelB] = b.split('|');
                // Sort by date, then event, then model
                if (dateA !== dateB) return dateA.localeCompare(dateB);
                if (eventA !== eventB) return eventA.localeCompare(eventB);
                return modelA.localeCompare(modelB);
              });

            return (
              <div className="space-y-1">
                {sortedKeys.map((key) => {
                  const { date, event, model, count } = breakdown[key];
                  return (
                    <div key={key} className="bg-white p-2 rounded border border-gray-200 text-xs">
                      <div className="grid grid-cols-3 gap-2">
                        <div>
                          <div className="text-gray-600 text-xs">Date</div>
                          <div className="font-semibold text-gray-900">{date}</div>
                        </div>
                        <div>
                          <div className="text-gray-600 text-xs">Event</div>
                          <div className="font-semibold text-gray-900">{event}</div>
                        </div>
                        <div>
                          <div className="text-gray-600 text-xs">Model</div>
                          <div className="font-semibold text-gray-900">{model}</div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            );
          })()}
        </div>
      )}

      {/* Failed calls detail */}
      {apiCalls.some((call) => !call.succeeded) && (
        <div className="mt-3 pt-3 border-t border-gray-200">
          <h5 className="font-medium text-gray-900 text-xs mb-2">Failed Calls</h5>
          <div className="space-y-1">
            {apiCalls
              .filter((call) => !call.succeeded)
              .map((call) => (
                <div key={call.id} className="text-xs text-red-600 bg-red-50 p-2 rounded">
                  <div className="font-medium">{call.service}</div>
                  <div className="text-red-700">{call.errorMessage}</div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Total cost */}
      {totalCostPence > 0 && (
        <div className="mt-3 pt-3 border-t border-gray-200">
          <div className="flex justify-between items-center">
            <span className="font-medium text-gray-900 text-sm">Total Cost</span>
            <span className="text-lg font-bold text-blue-600">£{(totalCostPence / 1000).toFixed(3)}</span>
          </div>
        </div>
      )}
    </div>
  );
};

JobRunDetail.propTypes = {
  jobRun: PropTypes.shape({
    id: PropTypes.number.isRequired,
    locationsProcessed: PropTypes.number,
    minTargetDate: PropTypes.string,
    maxTargetDate: PropTypes.string,
  }).isRequired,
};

export default JobRunDetail;
