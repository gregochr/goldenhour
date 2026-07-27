import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import { buildCloseToHome } from '../utils/closeToHome.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';

/** Accent for the whole block — its own warm gold, distinct from the bone `--color-plex-gold`. */
const GOLD = 'var(--color-close-to-home)';

/** Tooltip carrying the ranking rule. A one-time explanation, so it stays out of the body copy. */
const RANKING_TIP = 'Ranked by rating. Gated by distance from your home postcode — region '
  + 'boundaries are ignored, so a nearby spot in the next region still counts as local.';

/**
 * Star-badge colour bands. Mirrors the heatmap's star pills so a 4★ reads the same everywhere.
 *
 * @param {number} rating 1–5
 * @returns {{background: string, color: string}} inline style
 */
function starBadgeStyle(rating) {
  if (rating >= 4) return { background: 'rgba(138,174,114,0.22)', color: '#b6d49e' };
  if (rating >= 3) return { background: 'rgba(224,165,66,0.20)', color: '#f0cd8a' };
  return { background: 'rgba(200,69,47,0.20)', color: '#f0a08e' };
}

/* eslint-disable react/prop-types */

/** `🚗 22 min · 9 mi` — drive time is per-user and may be absent; distance always is not. */
function TravelLine({ driveMinutes, distanceMiles }) {
  const drive = formatDriveDuration(driveMinutes);
  return (
    <span>
      {drive ? `🚗 ${drive} · ` : ''}
      {distanceMiles} mi
    </span>
  );
}

/* eslint-enable react/prop-types */

/**
 * "Close to home" — the Plan tab's low-risk *local* decision, between the headline picks and the
 * hot topics. Two parts, no modes, never scoped to the selected briefing day:
 *
 * <ol>
 *   <li><b>The breadcrumb</b> — an honest verdict on the very next solar event, plus the next
 *       local window worth leaving the house for. <b>Never hidden on a poor verdict</b>: an honest
 *       "stay in" plus a date to look forward to beats an absent block.</li>
 *   <li><b>Best nearby across the horizon</b> — the top rated spots within the radius. Hidden when
 *       nothing qualifies; the breadcrumb stays.</li>
 * </ol>
 *
 * <p>Renders nothing when no home postcode is saved, or when nothing at all sits within the
 * radius — see {@link buildCloseToHome}.
 *
 * @param {Object}   props
 * @param {Array}    props.briefingDays     briefing.days
 * @param {Array}    props.locations        enabled locations (name, lat, lon, regionName)
 * @param {?Object}  props.homeCoords       {lat, lon} from the saved postcode, or null
 * @param {?Map}     props.driveMap         location name → drive minutes (per user)
 * @param {?Map}     props.evaluationScores "region|date|targetType|location" → {rating, …}
 * @param {string}   props.todayStr         today's ISO date
 * @param {string}   props.tomorrowStr      tomorrow's ISO date
 * @param {?Function} props.onShowOnMap     (date, targetType, locationName) → open the map focused
 */
export default function CloseToHome({
  briefingDays,
  locations,
  homeCoords = null,
  driveMap = null,
  evaluationScores = null,
  todayStr,
  tomorrowStr,
  onShowOnMap = null,
}) {
  // Horizon-aware, not day-scoped: recomputes only when the payload, roster or postcode changes.
  const model = useMemo(() => buildCloseToHome({
    briefingDays, locations, homeCoords, evaluationScores, driveMap, todayStr, tomorrowStr,
  }), [briefingDays, locations, homeCoords, evaluationScores, driveMap, todayStr, tomorrowStr]);

  if (!model) return null;

  const { radiusMiles, horizonDays, cards, multiDay, breadcrumb } = model;
  const countLine = cards.length > 0
    ? `${cards.length} within reach · next ${horizonDays} days`
    : `None within reach · next ${horizonDays} days`;

  return (
    <div
      data-testid="close-to-home"
      className="relative overflow-hidden mb-3"
      style={{
        border: '1px solid rgba(201,162,75,0.42)',
        borderRadius: '10px',
        background: 'linear-gradient(180deg, rgba(201,162,75,0.07), rgba(0,0,0,0.10)), var(--color-plex-surface)',
      }}
    >
      {/* Gold accent rail — marks the block as its own channel, not another neutral section. */}
      <span
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: '0 auto 0 0',
          width: '3px',
          background: 'linear-gradient(180deg, var(--color-close-to-home-light), var(--color-close-to-home))',
        }}
      />

      {/* ── Header ── */}
      <div className="flex items-center" style={{ gap: '11px', padding: '13px 16px 10px' }}>
        <span
          aria-hidden="true"
          className="shrink-0 flex items-center justify-center"
          style={{
            width: '31px',
            height: '31px',
            borderRadius: '8px',
            background: 'linear-gradient(155deg, #E4C878, #B0832F)',
            color: '#2a1c05',
            fontSize: '15px',
          }}
        >
          ⌂
        </span>
        <div>
          <div
            className="font-mono uppercase"
            style={{ fontSize: '10px', fontWeight: 600, letterSpacing: '0.1em', color: GOLD }}
          >
            Close to home
          </div>
          <div
            className="font-bold text-plex-text inline-flex items-center"
            style={{ fontSize: '15.5px', letterSpacing: '-0.01em', marginTop: '2px', gap: '7px' }}
          >
            Within {radiusMiles} miles of home
            <InfoTip text={RANKING_TIP} heading="How this is ranked" accentColor={GOLD} />
          </div>
        </div>
        <span
          data-testid="close-to-home-count"
          className="ml-auto font-mono text-plex-text-secondary text-right"
          style={{ fontSize: '11px' }}
        >
          {countLine}
        </span>
      </div>

      {/* ── Part 1 · the next-event breadcrumb (always rendered, poor verdict included) ── */}
      <div
        data-testid="close-to-home-breadcrumb"
        className="flex items-center flex-wrap"
        style={{
          margin: '0 16px 14px',
          border: '1px solid var(--color-plex-border)',
          borderRadius: '9px',
          padding: '12px 14px',
          background: 'rgba(0,0,0,0.16)',
          gap: '13px',
        }}
      >
        <div className="flex flex-col" style={{ gap: '3px', minWidth: '150px' }}>
          <span
            data-testid="close-to-home-verdict"
            className={breadcrumb.worthIt ? 'text-plex-text' : 'text-plex-text-secondary'}
            style={{ fontSize: '13px', fontWeight: 600 }}
          >
            {breadcrumb.worthIt ? '◎ Worth it' : '○ Stay in'}
          </span>
          {breadcrumb.eventLabel && (
            <span className="font-mono text-plex-text-secondary" style={{ fontSize: '11px' }}>
              {breadcrumb.eventLabel}
              {breadcrumb.eventTime && ` · ${breadcrumb.eventTime}`}
            </span>
          )}
        </div>

        <p
          className="text-plex-text-secondary"
          style={{ flex: 1, minWidth: '220px', fontSize: '12.5px', lineHeight: 1.5 }}
        >
          {breadcrumb.reason}
        </p>

        {breadcrumb.nextWindow && (
          <div
            data-testid="close-to-home-next-window"
            style={{ borderLeft: `2px solid ${GOLD}`, paddingLeft: '12px' }}
          >
            <div
              className="font-mono uppercase"
              style={{ fontSize: '10px', letterSpacing: '0.08em', color: GOLD }}
            >
              Next local window
            </div>
            <div className="text-plex-text" style={{ fontSize: '13px', fontWeight: 600 }}>
              {breadcrumb.nextWindow.locationName} · {breadcrumb.nextWindow.rating.toFixed(1)}★
            </div>
            <div
              className="font-mono text-plex-text-secondary"
              style={{ fontSize: '10.5px', marginTop: '2px' }}
            >
              {breadcrumb.nextWindow.detail}
              {formatDriveDuration(breadcrumb.nextWindow.driveMinutes)
                && ` · 🚗 ${formatDriveDuration(breadcrumb.nextWindow.driveMinutes)}`}
            </div>
          </div>
        )}
      </div>

      {/* ── Part 2 · best nearby across the horizon (hidden when nothing qualifies) ── */}
      {cards.length > 0 && (
        <div
          data-testid="close-to-home-cards"
          className="grid grid-cols-1 min-[900px]:grid-cols-4"
          style={{ gap: '8px', padding: '0 16px 14px' }}
        >
          {cards.map((card) => (
            <button
              key={card.key}
              type="button"
              data-testid="close-to-home-card"
              data-lead={card.lead || undefined}
              onClick={() => onShowOnMap?.(card.date, card.targetType, card.locationName)}
              // Border, radius and background live in the .cth-card CSS keyed on data-lead —
              // an inline border shorthand would outrank the hover rule and kill the affordance.
              className="cth-card flex flex-col text-left"
              style={{ gap: '5px', padding: '10px 11px' }}
            >
              <span className="flex items-center justify-between" style={{ gap: '8px' }}>
                <span className="text-plex-text" style={{ fontSize: '12.5px', fontWeight: 600 }}>
                  {card.locationName}
                </span>
                <span
                  data-testid="close-to-home-stars"
                  className="font-mono whitespace-nowrap"
                  style={{
                    fontSize: '10.5px',
                    fontWeight: 600,
                    padding: '2px 6px',
                    borderRadius: '5px',
                    ...starBadgeStyle(card.rating),
                  }}
                >
                  {card.rating.toFixed(1)}★
                </span>
              </span>

              {/* Day chip only when the result set actually spans more than one day. */}
              {multiDay && (
                <span
                  data-testid="close-to-home-day-chip"
                  className="font-mono uppercase"
                  style={{ fontSize: '10px', fontWeight: 600, letterSpacing: '0.05em', color: GOLD }}
                >
                  {card.dayChip}
                </span>
              )}

              {/* The location's actual region — the honesty label, never a filter. Kept at
                  --ink-2 (text-secondary) after a WCAG audit; --ink-3 measures 3.55:1 at this
                  size and fails AA. Do not lower it. */}
              {card.regionName && (
                <span
                  data-testid="close-to-home-region"
                  className="font-mono text-plex-text-secondary"
                  style={{ fontSize: '10.5px' }}
                >
                  {card.regionName}
                </span>
              )}

              <span
                className="flex items-center flex-wrap font-mono text-plex-text-secondary"
                style={{ fontSize: '10.5px', gap: '7px' }}
              >
                <TravelLine driveMinutes={card.driveMinutes} distanceMiles={card.distanceMiles} />
                {card.tideLabel && (
                  <span style={{ color: 'var(--color-tide)' }}>🌊 {card.tideLabel}</span>
                )}
              </span>

              <span className="font-mono" style={{ fontSize: '10px', color: 'var(--color-tide)' }}>
                ◍ Open on map →
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

CloseToHome.propTypes = {
  briefingDays: PropTypes.arrayOf(PropTypes.shape({
    date: PropTypes.string,
    eventSummaries: PropTypes.array,
  })),
  locations: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string,
    lat: PropTypes.number,
    lon: PropTypes.number,
    regionName: PropTypes.string,
  })),
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  driveMap: PropTypes.instanceOf(Map),
  evaluationScores: PropTypes.instanceOf(Map),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onShowOnMap: PropTypes.func,
};
