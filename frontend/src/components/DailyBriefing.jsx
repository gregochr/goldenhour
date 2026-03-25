import React, { useCallback, useEffect, useRef, useState } from 'react';
import { getDailyBriefing } from '../api/briefingApi.js';

const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Colour pill for a verdict (GO / MARGINAL / STANDDOWN).
 *
 * @param {object} props
 * @param {string} props.verdict
 */
function VerdictPill({ verdict }) {
  const colours = {
    GO: 'bg-green-600 text-white',
    MARGINAL: 'bg-amber-600 text-white',
    STANDDOWN: 'bg-red-700 text-white',
  };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-xs font-bold uppercase ${colours[verdict] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {verdict}
    </span>
  );
}

/**
 * Inline chip for a flag string.
 *
 * @param {object} props
 * @param {string} props.label
 */
function FlagChip({ label }) {
  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-xs text-plex-text-secondary">
      {label}
    </span>
  );
}

/**
 * Formats a UTC ISO datetime string to a human-readable time (HH:MM).
 *
 * @param {string} isoString
 * @returns {string}
 */
function formatTime(isoString) {
  if (!isoString) return '';
  return isoString.substring(11, 16);
}

/**
 * Formats a UTC ISO datetime string to a relative freshness label.
 *
 * @param {string} isoString
 * @returns {string}
 */
function formatAge(isoString) {
  if (!isoString) return '';
  const generated = new Date(isoString + 'Z');
  const now = new Date();
  const diffMin = Math.round((now - generated) / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHrs = Math.round(diffMin / 60);
  return `${diffHrs}h ago`;
}

/**
 * Collapsible daily briefing card displayed above the map view.
 *
 * Collapsed: headline + freshness timestamp.
 * Expanded: per-day sections with sunrise/sunset sub-sections,
 *           region rows with verdict pills, and expandable location detail.
 */
export default function DailyBriefing() {
  const [briefing, setBriefing] = useState(null);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState(false);
  const [expandedRegions, setExpandedRegions] = useState(new Set());
  const intervalRef = useRef(null);

  const fetchBriefing = useCallback(async () => {
    try {
      const data = await getDailyBriefing();
      setBriefing(data);
    } catch {
      // Transient — keep existing data
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchBriefing();
    intervalRef.current = setInterval(fetchBriefing, POLL_INTERVAL_MS);

    function handleFocus() {
      fetchBriefing();
    }
    window.addEventListener('focus', handleFocus);

    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, [fetchBriefing]);

  if (loading || !briefing) return null;

  const toggleRegion = (key) => {
    setExpandedRegions((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const todayStr = new Date().toISOString().slice(0, 10);

  return (
    <div
      data-testid="daily-briefing"
      className="card mb-4 overflow-hidden"
    >
      {/* Collapsed header — always visible */}
      <button
        data-testid="briefing-toggle"
        className="w-full flex items-center justify-between gap-3 text-left"
        onClick={() => setExpanded((v) => !v)}
      >
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-lg shrink-0" aria-hidden="true">
            {expanded ? '▾' : '▸'}
          </span>
          <div className="min-w-0">
            <p className="text-sm font-semibold text-plex-text leading-tight truncate">
              {briefing.headline}
            </p>
            <p className="text-xs text-plex-text-muted mt-0.5">
              Briefing as of {formatAge(briefing.generatedAt)}
            </p>
          </div>
        </div>
      </button>

      {/* Expanded content */}
      {expanded && (
        <div className="mt-3 space-y-4" data-testid="briefing-expanded">
          {briefing.days.map((day) => {
            const dateLabel = day.date === todayStr ? 'Today' : 'Tomorrow';
            return (
              <div key={day.date}>
                <h3 className="text-xs font-bold text-plex-text-secondary uppercase tracking-wide mb-2">
                  {dateLabel} &mdash; {day.date}
                </h3>
                {day.eventSummaries.map((es) => (
                  <div key={es.targetType} className="mb-3">
                    <p className="text-xs font-semibold text-plex-text-secondary mb-1">
                      {es.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset'}
                    </p>

                    {/* Region rows */}
                    {es.regions.map((region) => {
                      const regionKey = `${day.date}-${es.targetType}-${region.regionName}`;
                      const isOpen = expandedRegions.has(regionKey);
                      return (
                        <div key={regionKey} className="mb-1">
                          <button
                            data-testid="region-row"
                            className="w-full flex items-center gap-2 px-2 py-1.5 rounded hover:bg-plex-bg/50 text-left"
                            onClick={() => toggleRegion(regionKey)}
                          >
                            <VerdictPill verdict={region.verdict} />
                            <span className="text-sm text-plex-text font-medium">
                              {region.regionName}
                            </span>
                            <span className="text-xs text-plex-text-secondary flex-1 truncate">
                              {region.summary}
                            </span>
                            <span className="text-xs text-plex-text-muted shrink-0">
                              {isOpen ? '▾' : '▸'}
                            </span>
                          </button>

                          {/* Tide highlights */}
                          {region.tideHighlights?.length > 0 && (
                            <div className="flex flex-wrap gap-1 px-2 mt-0.5">
                              {region.tideHighlights.map((hl) => (
                                <FlagChip key={hl} label={hl} />
                              ))}
                            </div>
                          )}

                          {/* Expanded location slots */}
                          {isOpen && (
                            <div className="ml-4 mt-1 space-y-1" data-testid="region-slots">
                              {region.slots.map((slot) => (
                                <div
                                  key={slot.locationName}
                                  className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 text-xs"
                                  data-testid="briefing-slot"
                                >
                                  <VerdictPill verdict={slot.verdict} />
                                  <span className="font-medium text-plex-text">
                                    {slot.locationName}
                                  </span>
                                  <span className="text-plex-text-secondary">
                                    {formatTime(slot.solarEventTime)}
                                  </span>
                                  {slot.flags?.map((flag) => (
                                    <FlagChip key={flag} label={flag} />
                                  ))}
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      );
                    })}

                    {/* Unregioned slots */}
                    {es.unregioned?.length > 0 && (
                      <div className="ml-2 mt-1 space-y-1">
                        {es.unregioned.map((slot) => (
                          <div
                            key={slot.locationName}
                            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 text-xs"
                            data-testid="briefing-slot"
                          >
                            <VerdictPill verdict={slot.verdict} />
                            <span className="font-medium text-plex-text">
                              {slot.locationName}
                            </span>
                            <span className="text-plex-text-secondary">
                              {formatTime(slot.solarEventTime)}
                            </span>
                            {slot.flags?.map((flag) => (
                              <FlagChip key={flag} label={flag} />
                            ))}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
