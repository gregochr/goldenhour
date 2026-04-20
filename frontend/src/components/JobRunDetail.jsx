import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { getApiCalls, getBatchSummary } from '../api/metricsApi';
import { formatCostGbp, formatCostUsd, formatTokens } from '../utils/formatCost';

/**
 * Expandable detail view for a job run showing all API calls.
 *
 * Displays per-service breakdown:
 * - Service name
 * - Call count
 * - Average duration
 * - Cost (token-based micro-dollars with GBP conversion)
 * - Token breakdown for Anthropic calls
 * - Error count and rate
 *
 * For batch runs (SCHEDULED_BATCH, BATCH_NEAR_TERM, BATCH_FAR_TERM), shows a batch
 * token summary instead of individual API calls.
 */
const BATCH_RUN_TYPES = new Set(['SCHEDULED_BATCH', 'BATCH_NEAR_TERM', 'BATCH_FAR_TERM']);

const JobRunDetail = ({ jobRun }) => {
  const [apiCalls, setApiCalls] = useState([]);
  const [batchSummary, setBatchSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        if (BATCH_RUN_TYPES.has(jobRun.runType)) {
          const batchResp = await getBatchSummary(jobRun.id).catch(() => null);
          if (batchResp?.data) {
            setBatchSummary(batchResp.data);
          }
          // Also fetch api calls in case there are any
          const apiResp = await getApiCalls(jobRun.id);
          setApiCalls(apiResp.data || []);
        } else {
          const response = await getApiCalls(jobRun.id);
          setApiCalls(response.data || []);
        }
      } catch (err) {
        setError(err.message || 'Failed to load API calls');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [jobRun.id, jobRun.runType]);

  if (loading) {
    return <div className="text-plex-text-muted text-sm">Loading...</div>;
  }

  if (error) {
    return <div className="text-red-400 text-sm">Error: {error}</div>;
  }

  const exchangeRate = jobRun.exchangeRateGbpPerUsd;

  // Group API calls by service
  const serviceStats = apiCalls.reduce((acc, call) => {
    if (!acc[call.service]) {
      acc[call.service] = {
        calls: [],
        totalDuration: 0,
        totalCostPence: 0,
        totalCostMicroDollars: 0,
        totalInputTokens: 0,
        totalOutputTokens: 0,
        totalCacheWriteTokens: 0,
        totalCacheReadTokens: 0,
        count: 0,
        errorCount: 0,
      };
    }
    const s = acc[call.service];
    s.calls.push(call);
    s.totalDuration += call.durationMs || 0;
    s.totalCostPence += call.costPence || 0;
    s.totalCostMicroDollars += call.costMicroDollars || 0;
    s.totalInputTokens += call.inputTokens || 0;
    s.totalOutputTokens += call.outputTokens || 0;
    s.totalCacheWriteTokens += call.cacheCreationInputTokens || 0;
    s.totalCacheReadTokens += call.cacheReadInputTokens || 0;
    s.count += 1;
    if (!call.succeeded) {
      s.errorCount += 1;
    }
    return acc;
  }, {});

  // Calculate grand total cost
  const totalCostMicroDollars = batchSummary?.estimatedCostMicroDollars
    || Object.values(serviceStats).reduce((sum, stats) => sum + stats.totalCostMicroDollars, 0);
  const totalCostPence = Object.values(serviceStats)
    .reduce((sum, stats) => sum + stats.totalCostPence, 0);

  // Calculate evaluation summary
  const locationsProcessed = jobRun.locationsProcessed || 0;
  const minDate = jobRun.minTargetDate;
  const maxDate = jobRun.maxTargetDate;
  const daysCount = minDate && maxDate
    ? Math.floor((new Date(maxDate) - new Date(minDate)) / (1000 * 60 * 60 * 24)) + 1
    : 0;

  const isBatchWithNoApiCalls = batchSummary && Object.entries(serviceStats).length === 0;

  return (
    <div className="mt-4 space-y-3 bg-plex-bg p-4 rounded-lg border border-plex-border">
      {/* Evaluation Summary */}
      <div className="mb-4 pb-4 border-b border-plex-border">
        <h4 className="font-semibold text-plex-text text-sm mb-2">Evaluation Summary</h4>
        <div className="grid grid-cols-2 gap-3 text-sm">
          {locationsProcessed > 0 && (
            <div className="bg-plex-surface p-2 rounded border border-plex-border">
              <div className="text-plex-text-muted text-xs">Locations</div>
              <div className="font-semibold text-plex-text">{locationsProcessed}</div>
            </div>
          )}
          {daysCount > 0 && (
            <div className="bg-plex-surface p-2 rounded border border-plex-border">
              <div className="text-plex-text-muted text-xs">Days</div>
              <div className="font-semibold text-plex-text">
                {daysCount} <span className="text-plex-text-muted text-xs">({minDate} to {maxDate})</span>
              </div>
            </div>
          )}
          <div className="bg-plex-surface p-2 rounded border border-plex-border">
            <div className="text-plex-text-muted text-xs">Job Run ID</div>
            <div className="font-semibold text-plex-text">{jobRun.id}</div>
          </div>
          {BATCH_RUN_TYPES.has(jobRun.runType) && jobRun.notes && (
            <div className="bg-plex-surface p-2 rounded border border-plex-border col-span-2">
              <div className="text-plex-text-muted text-xs">Anthropic Batch ID</div>
              <div className="font-semibold text-plex-text" style={{ fontFamily: 'monospace' }}>
                {jobRun.notes.replace('Anthropic batch: ', '')}
              </div>
            </div>
          )}
        </div>
      </div>

      <h4 className="font-semibold text-plex-text text-sm">API Call Breakdown</h4>

      {isBatchWithNoApiCalls ? (
        <div className="bg-plex-surface p-3 rounded border border-plex-border text-sm">
          <div className="flex justify-between items-start">
            <div>
              <div className="font-medium text-plex-text">ANTHROPIC (Batch)</div>
              <div className="text-xs text-plex-text-muted mt-1">
                {batchSummary.requestCount} requests: {batchSummary.succeededCount ?? 0} succeeded, {batchSummary.erroredCount ?? 0} errored
              </div>
              <div className="text-xs text-plex-gold mt-1 font-semibold">
                Cost: {formatCostGbp(batchSummary.estimatedCostMicroDollars, exchangeRate, 0)}
                {batchSummary.estimatedCostMicroDollars > 0 && (
                  <span className="text-plex-text-muted font-normal ml-2">
                    ({formatCostUsd(batchSummary.estimatedCostMicroDollars)})
                  </span>
                )}
              </div>
              {(batchSummary.totalInputTokens > 0 || batchSummary.totalOutputTokens > 0) ? (
                <div className="text-xs text-plex-text-muted mt-1">
                  Tokens: {formatTokens(batchSummary.totalInputTokens)} in
                  {' / '}{formatTokens(batchSummary.totalOutputTokens)} out
                  {batchSummary.totalCacheCreationTokens > 0 && (
                    <> / {formatTokens(batchSummary.totalCacheCreationTokens)} cache write</>
                  )}
                  {batchSummary.totalCacheReadTokens > 0 && (
                    <> / {formatTokens(batchSummary.totalCacheReadTokens)} cache read</>
                  )}
                </div>
              ) : (
                <div className="text-xs text-plex-text-muted mt-1">
                  No token data — batch failed before processing
                </div>
              )}
            </div>
            <div className="flex gap-1">
              <span className={`inline-block px-2 py-1 rounded-full text-xs font-medium ${
                batchSummary.status === 'COMPLETED'
                  ? 'bg-green-900/30 text-green-400'
                  : batchSummary.status === 'FAILED'
                    ? 'bg-red-900/30 text-red-400'
                    : 'bg-yellow-900/30 text-yellow-400'
              }`}>
                {batchSummary.status}
              </span>
            </div>
          </div>
        </div>
      ) : Object.entries(serviceStats).length === 0 ? (
        <p className="text-plex-text-muted text-sm">No API calls recorded</p>
      ) : (
        <div className="space-y-2">
          {Object.entries(serviceStats).map(([service, stats]) => (
            <div
              key={service}
              className="bg-plex-surface p-3 rounded border border-plex-border text-sm"
            >
              <div className="flex justify-between items-start">
                <div>
                  <div className="font-medium text-plex-text">{service}</div>
                  <div className="text-xs text-plex-text-muted mt-1">
                    {stats.count} calls, avg {Math.round(stats.totalDuration / stats.count)}ms
                  </div>
                  <div className="text-xs text-plex-gold mt-1 font-semibold">
                    Cost: {formatCostGbp(stats.totalCostMicroDollars, exchangeRate, stats.totalCostPence)}
                    {stats.totalCostMicroDollars > 0 && (
                      <span className="text-plex-text-muted font-normal ml-2">
                        ({formatCostUsd(stats.totalCostMicroDollars)})
                      </span>
                    )}
                  </div>
                  {/* Token breakdown for Anthropic */}
                  {service === 'ANTHROPIC' && stats.totalInputTokens > 0 && (
                    <div className="text-xs text-plex-text-muted mt-1">
                      Tokens: {formatTokens(stats.totalInputTokens)} in
                      {' / '}{formatTokens(stats.totalOutputTokens)} out
                      {stats.totalCacheWriteTokens > 0 && (
                        <> / {formatTokens(stats.totalCacheWriteTokens)} cache write</>
                      )}
                      {stats.totalCacheReadTokens > 0 && (
                        <> / {formatTokens(stats.totalCacheReadTokens)} cache read</>
                      )}
                    </div>
                  )}
                  {stats.errorCount > 0 && (
                    <div className={`text-xs mt-1 ${(stats.errorCount / stats.count) < 0.05 ? 'text-yellow-400' : 'text-red-400'}`}>
                      {stats.errorCount} failures ({((stats.errorCount / stats.count) * 100).toFixed(2)}%)
                    </div>
                  )}
                </div>
                <div className="flex gap-1">
                  {stats.errorCount > 0 && (() => {
                    const failRate = stats.errorCount / stats.count;
                    const isAmber = failRate < 0.05;
                    return (
                      <span className={`inline-block px-2 py-1 rounded-full text-xs font-medium ${isAmber ? 'bg-yellow-900/30 text-yellow-400' : 'bg-red-900/30 text-red-400'}`}>
                        {stats.errorCount} errors
                      </span>
                    );
                  })()}
                  {stats.errorCount === 0 && (
                    <span className="inline-block px-2 py-1 rounded-full bg-green-900/30 text-green-400 text-xs font-medium">
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
        <div className="mt-3 pt-3 border-t border-plex-border">
          <h5 className="font-medium text-plex-text text-xs mb-2">Anthropic Evaluation Breakdown</h5>
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
                if (dateA !== dateB) return dateA.localeCompare(dateB);
                if (eventA !== eventB) return eventA.localeCompare(eventB);
                return modelA.localeCompare(modelB);
              });

            return (
              <div className="space-y-1">
                {sortedKeys.map((key) => {
                  const { date, event, model, count } = breakdown[key];
                  return (
                    <div key={key} className="bg-plex-surface p-2 rounded border border-plex-border text-xs">
                      <div className="grid grid-cols-4 gap-2">
                        <div>
                          <div className="text-plex-text-muted text-xs">Date</div>
                          <div className="font-semibold text-plex-text">{date}</div>
                        </div>
                        <div>
                          <div className="text-plex-text-muted text-xs">Event</div>
                          <div className="font-semibold text-plex-text">{event}</div>
                        </div>
                        <div>
                          <div className="text-plex-text-muted text-xs">Model</div>
                          <div className="font-semibold text-plex-text">{model}</div>
                        </div>
                        <div>
                          <div className="text-plex-text-muted text-xs">Count</div>
                          <div className="font-semibold text-plex-text">{count}</div>
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
        <div className="mt-3 pt-3 border-t border-plex-border">
          <h5 className="font-medium text-plex-text text-xs mb-2">Failed Calls</h5>
          <div className="space-y-1">
            {apiCalls
              .filter((call) => !call.succeeded)
              .map((call) => (
                <div key={call.id} className="text-xs text-red-400 bg-red-900/20 p-2 rounded">
                  <div className="font-medium">{call.service}</div>
                  <div className="text-red-400">{call.errorMessage}</div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Total cost */}
      {(totalCostMicroDollars > 0 || totalCostPence > 0) && (
        <div className="mt-3 pt-3 border-t border-plex-border">
          <div className="flex justify-between items-center">
            <span className="font-medium text-plex-text text-sm">Total Cost</span>
            <div className="text-right">
              <span className="text-lg font-bold text-plex-gold">
                {formatCostGbp(totalCostMicroDollars, exchangeRate, totalCostPence)}
              </span>
              {totalCostMicroDollars > 0 && (
                <div className="text-xs text-plex-text-muted">
                  {formatCostUsd(totalCostMicroDollars)}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

JobRunDetail.propTypes = {
  jobRun: PropTypes.shape({
    id: PropTypes.number.isRequired,
    runType: PropTypes.string.isRequired,
    notes: PropTypes.string,
    locationsProcessed: PropTypes.number,
    minTargetDate: PropTypes.string,
    maxTargetDate: PropTypes.string,
    exchangeRateGbpPerUsd: PropTypes.number,
  }).isRequired,
};

export default JobRunDetail;
