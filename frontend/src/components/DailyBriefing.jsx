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
 * Returns the representative solar event time for an event summary (first slot's time).
 *
 * @param {object} es - BriefingEventSummary
 * @returns {string|null} ISO datetime string or null
 */
function getEventTime(es) {
  for (const r of es.regions || []) {
    for (const s of r.slots || []) {
      if (s.solarEventTime) return s.solarEventTime;
    }
  }
  for (const s of es.unregioned || []) {
    if (s.solarEventTime) return s.solarEventTime;
  }
  return null;
}

/**
 * Returns true if the solar event for this summary has already passed.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {boolean}
 */
function isEventPast(es) {
  const t = getEventTime(es);
  if (!t) return false;
  return new Date(t + 'Z') < new Date();
}

/**
 * Returns verdict counts across all regions in an event summary.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {{ GO: number, MARGINAL: number, STANDDOWN: number }}
 */
function getVerdictCounts(es) {
  const counts = { GO: 0, MARGINAL: 0, STANDDOWN: 0 };
  (es.regions || []).forEach((r) => { counts[r.verdict] = (counts[r.verdict] || 0) + 1; });
  return counts;
}

/**
 * Returns true if any slot in this event summary has tide alignment.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {boolean}
 */
function hasTideAligned(es) {
  return (es.regions || []).some((r) => (r.slots || []).some((s) => s.tideAligned));
}

/**
 * Compact single-row summary of one solar event shown in the collapsed briefing card.
 *
 * @param {object} props
 * @param {string} props.dayLabel   - "Today" or "Tomorrow"
 * @param {object} props.es         - BriefingEventSummary
 */
function EventSummaryRow({ dayLabel, es }) {
  const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
  const eventLabel = es.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
  const counts = getVerdictCounts(es);
  const tideAligned = hasTideAligned(es);

  const countColours = {
    GO: 'text-green-400',
    MARGINAL: 'text-amber-400',
    STANDDOWN: 'text-red-400',
  };

  return (
    <div
      data-testid="event-summary-row"
      className="flex items-center gap-2 text-xs py-0.5"
    >
      <span className="w-36 shrink-0 font-medium text-plex-text">
        {emoji} {dayLabel} {eventLabel}
      </span>
      <span className="flex gap-2 flex-wrap">
        {counts.GO > 0 && (
          <span className={countColours.GO} data-testid="go-count">
            {counts.GO} GO
          </span>
        )}
        {counts.MARGINAL > 0 && (
          <span className={countColours.MARGINAL} data-testid="marginal-count">
            {counts.MARGINAL} MARGINAL
          </span>
        )}
        {counts.STANDDOWN > 0 && (
          <span className={countColours.STANDDOWN} data-testid="standdown-count">
            {counts.STANDDOWN} STANDDOWN
          </span>
        )}
      </span>
      {tideAligned && (
        <span title="Tide-aligned location in this event" className="text-blue-400 shrink-0">
          🌊
        </span>
      )}
    </div>
  );
}

/**
 * Collapsible daily briefing card displayed above the map view.
 *
 * Collapsed: per-event compact rows for all upcoming solar events (past events hidden),
 *            showing verdict counts and tide alignment at a glance.
 * Expanded: per-day sections with region rows and expandable location detail.
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

  // Collect upcoming (not-yet-past) event summaries across all days
  const upcomingEvents = briefing.days.flatMap((day) =>
    (day.eventSummaries || [])
      .filter((es) => !isEventPast(es))
      .map((es) => ({
        es,
        dayLabel: day.date === todayStr ? 'Today' : 'Tomorrow',
        date: day.date,
      })),
  );

  return (
    <div
      data-testid="daily-briefing"
      className="card mb-4 overflow-hidden"
    >
      {/* Header toggle row */}
      <button
        data-testid="briefing-toggle"
        className="w-full flex items-center justify-between gap-3 text-left"
        onClick={() => setExpanded((v) => !v)}
      >
        <span className="text-xs font-semibold text-plex-text-secondary uppercase tracking-wide">
          Daily Briefing
        </span>
        <span className="flex items-center gap-2 text-xs text-plex-text-muted">
          {formatAge(briefing.generatedAt)}
          <span aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        </span>
      </button>

      {/* Compact upcoming-event rows — visible when collapsed */}
      {!expanded && (
        <div className="mt-2 space-y-0.5" data-testid="briefing-collapsed-events">
          {upcomingEvents.length === 0 ? (
            <p className="text-xs text-plex-text-muted italic mt-1">
              No upcoming events in the next two days
            </p>
          ) : (
            upcomingEvents.map(({ es, dayLabel, date }) => (
              <EventSummaryRow
                key={`${date}-${es.targetType}`}
                dayLabel={dayLabel}
                es={es}
              />
            ))
          )}
        </div>
      )}

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
                      {es.targetType === 'SUNRISE' ? '🌅 Sunrise' : '🌇 Sunset'}
                      {isEventPast(es) && (
                        <span className="ml-2 text-plex-text-muted font-normal">(passed)</span>
                      )}
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
