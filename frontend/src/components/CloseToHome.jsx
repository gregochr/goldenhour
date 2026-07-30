import React from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { LOCATION_TYPE_ICONS, locationTypeLabel } from '../utils/locationTypes.js';
import { formatEventTimeUk } from '../utils/conversions.js';

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

/** Sunrise rises in marginal amber, sunset falls in dust orange — the window header's leading mark. */
function eventMark(targetType) {
  return targetType === 'SUNRISE'
    ? { glyph: '↑', colour: 'var(--color-marginal)' }
    : { glyph: '↓', colour: 'var(--color-dust)' };
}

/**
 * `Tomorrow sunrise` / `Thursday sunset` — the window's own label.
 *
 * The server sends the date and event; the wording is the client's job, which is why the endpoint
 * returns facts rather than prose.
 */
function windowLabel(dateStr, targetType, todayStr, tomorrowStr) {
  const word = targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  if (dateStr === todayStr) return `Today ${word}`;
  if (dateStr === tomorrowStr) return `Tomorrow ${word}`;
  const d = new Date(`${dateStr}T00:00:00`);
  return `${d.toLocaleDateString('en-GB', { weekday: 'long' })} ${word}`;
}

/**
 * `05:09` in UK local time from a UTC ISO date-time, or null.
 *
 * Routed through the shared formatter rather than slicing the string. Slicing renders raw UTC,
 * which is an hour early for the whole of BST — the same event then showed two different times on
 * one screen, because the Plan tab's drill-down list already used this formatter.
 */
function timeOf(iso) {
  return formatEventTimeUk(iso);
}

/**
 * The breadcrumb's one plain sentence.
 *
 * The endpoint supplies the FACTS — worthIt, the leading location, the dominant stand-down cause —
 * and the wording is assembled here. That split is deliberate: the logic needed one home on the
 * server, the copy did not, and putting user-facing English in Java buys nothing.
 */
function breadcrumbReason(breadcrumb, todayStr, tomorrowStr) {
  if (!breadcrumb?.date) return 'No upcoming sunrise or sunset to assess.';
  if (breadcrumb.worthIt) {
    return breadcrumb.topHeadline
      || breadcrumb.topSummary
      || `${breadcrumb.topLocationName} leads the local options at ${breadcrumb.topRating}★.`;
  }
  const when = breadcrumb.date === todayStr || breadcrumb.date === tomorrowStr
    ? windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr).toLowerCase()
    : `on ${windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr)}`;
  return breadcrumb.dominantReason
    ? `${breadcrumb.dominantReason} nearby — nothing within reach is worth the trip ${when}.`
    : `Nothing within reach is worth the trip ${when}.`;
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
 * radius. The derivation lives on the server ({@code GET /api/briefing/close-to-home}) so the
 * proximity join can use the location FK rather than a name string — renaming a location used to
 * empty this block silently for every user in range.
 *
 * @param {Object}    props
 * @param {?Object}   props.panel        the endpoint payload, or null while loading / when unset
 * @param {string}    props.todayStr     today's ISO date, for "Today"/"Tomorrow" wording
 * @param {string}    props.tomorrowStr  tomorrow's ISO date
 * @param {?Function} props.onShowOnMap  (date, targetType, locationName) → open the map focused
 */
export default function CloseToHome({
  panel = null,
  todayStr,
  tomorrowStr,
  onShowOnMap = null,
}) {
  if (!panel) return null;
  const model = panel;

  const { radiusMiles, horizonDays, windows = [], breadcrumb } = model;
  if (windows.length === 0 && !breadcrumb?.date) return null;

  // The busiest single window, NOT the sum. Each window holds at most one entry per location, so
  // summing counted a location once per window it qualified in — five locations across three
  // windows read "15 within reach" under a heading that says "within 22 miles of home".
  const reach = windows.reduce((n, w) => Math.max(n, w.withinReach), 0);
  const countLine = windows.length > 0
    ? `${reach} within reach · ${windows.length} window${windows.length === 1 ? '' : 's'}`
      + ` · next ${horizonDays} days`
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
          {breadcrumb.date && (
            <span className="font-mono text-plex-text-secondary" style={{ fontSize: '11px' }}>
              {windowLabel(breadcrumb.date, breadcrumb.targetType, todayStr, tomorrowStr)}
              {timeOf(breadcrumb.eventTime) && ` · ${timeOf(breadcrumb.eventTime)}`}
            </span>
          )}
        </div>

        <p
          className="text-plex-text-secondary"
          style={{ flex: 1, minWidth: '220px', fontSize: '12.5px', lineHeight: 1.5 }}
        >
          {breadcrumbReason(breadcrumb, todayStr, tomorrowStr)}
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
              {windowLabel(breadcrumb.nextWindow.date, breadcrumb.nextWindow.targetType,
                todayStr, tomorrowStr)}
              {timeOf(breadcrumb.nextWindow.eventTime)
                && ` ${timeOf(breadcrumb.nextWindow.eventTime)}`}
              {formatDriveDuration(breadcrumb.nextWindow.driveMinutes)
                && ` · 🚗 ${formatDriveDuration(breadcrumb.nextWindow.driveMinutes)}`}
            </div>
          </div>
        )}
      </div>

      {/* ── Part 2 · nearby locations, GROUPED BY EVENT WINDOW ──
          A flat list never said which event a card was for, and a sunrise-only suggestion is an
          easy dismissal when the same region's sunset is the Best Bet. The window header carries
          day, event and time, so no per-card day chip is needed. */}
      {windows.map((w) => {
        const mark = eventMark(w.targetType);
        const time = timeOf(w.eventTime);
        return (
          <div key={`${w.date}|${w.targetType}`} data-testid="cth-window" style={{ margin: '0 16px 12px' }}>
            {/* Window header */}
            <div
              className="flex items-center flex-wrap"
              style={{ gap: '10px', paddingBottom: '7px' }}
            >
              <span
                data-testid="cth-window-label"
                className="font-mono"
                style={{ fontSize: '11.5px', fontWeight: 600, color: 'var(--color-plex-text)' }}
              >
                <span style={{ color: mark.colour }}>{mark.glyph}</span>{' '}
                {windowLabel(w.date, w.targetType, todayStr, tomorrowStr)}
                {time && ` · ${time}`}
              </span>

              {w.bestRating != null && (
                <span className="font-mono text-plex-text-secondary" style={{ fontSize: '10.5px' }}>
                  best {w.bestRating.toFixed(1)}★
                </span>
              )}

              {/* The signal the region-level grid structurally cannot show: the region stands down
                  for this window while a nearby spot still rates well on its own. */}
              {w.notInBriefing && (
                <span
                  data-testid="cth-flag-not-in-briefing"
                  className="font-mono uppercase"
                  title={`${w.flaggedRegionName} is Stand down for this window region-wide, but `
                    + 'these locations still rate well individually.'}
                  style={{
                    fontSize: '10px',
                    fontWeight: 600,
                    letterSpacing: '0.04em',
                    color: 'var(--color-close-to-home-light)',
                    background: 'rgba(201,162,75,0.14)',
                    boxShadow: 'inset 0 0 0 1px rgba(201,162,75,0.38)',
                    borderRadius: '5px',
                    padding: '3px 8px',
                    cursor: 'help',
                  }}
                >
                  Not in the briefing
                </span>
              )}

              {w.sameWindowAsBestBet && (
                <span
                  data-testid="cth-flag-same-as-best-bet"
                  className="font-mono uppercase"
                  title={`The Best Bet for this window is ${w.bestBetRegionName} — going local `
                    + 'is not a compromise.'}
                  style={{
                    fontSize: '10px',
                    fontWeight: 600,
                    letterSpacing: '0.04em',
                    color: 'var(--color-close-to-home-light)',
                    background: 'rgba(201,162,75,0.14)',
                    boxShadow: 'inset 0 0 0 1px rgba(201,162,75,0.38)',
                    borderRadius: '5px',
                    padding: '3px 8px',
                    cursor: 'help',
                  }}
                >
                  Same window as best bet
                </span>
              )}

              <span
                aria-hidden="true"
                style={{ flex: 1, height: '1px', background: 'var(--color-plex-border)' }}
              />
              <span className="font-mono text-plex-text-secondary" style={{ fontSize: '10.5px' }}>
                {w.withinReach} within reach
              </span>
            </div>

            {/* Grid sized to the group's card count, so a single-card window renders one
                quarter-width card rather than stretching the full width. */}
            <div
              data-testid="cth-window-cards"
              // Sizing lives in the .cth-window-grid class rule, gated on a min-width query; only
              // the count comes through inline as a custom property. Inline grid-template-columns
              // would win at every viewport and squeeze four cards into ~72px each on a phone.
              className="cth-window-grid"
              style={{ '--cth-cards': w.cards.length }}
            >
              {w.cards.map((card) => (
                <button
                  key={`${card.locationId ?? card.locationName}`}
                  type="button"
                  data-testid="close-to-home-card"
                  data-lead={card.lead || undefined}
                  onClick={() => onShowOnMap?.(w.date, w.targetType, card.locationName)}
                  // Border/radius/background live in .cth-card CSS keyed on data-lead — an inline
                  // border shorthand would outrank the hover rule and kill the affordance.
                  className="cth-card flex flex-col text-left"
                  style={{ gap: '5px', padding: '10px 11px' }}
                >
                  <span className="flex items-center justify-between" style={{ gap: '8px' }}>
                    <span
                      className="text-plex-text flex items-center"
                      style={{ fontSize: '12.5px', fontWeight: 600, gap: '5px' }}
                    >
                      {/* Subject icons, the same vocabulary the map and admin use. */}
                      {(card.locationTypes ?? []).map((t) => (
                        <span key={t} title={locationTypeLabel(t)} aria-label={locationTypeLabel(t)}>
                          {LOCATION_TYPE_ICONS[t] ?? ''}
                        </span>
                      ))}
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
                    <TravelLine
                      driveMinutes={card.driveMinutes}
                      distanceMiles={card.distanceMiles}
                    />
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
          </div>
        );
      })}

    </div>
  );
}

CloseToHome.propTypes = {
  panel: PropTypes.shape({
    radiusMiles: PropTypes.number,
    horizonDays: PropTypes.number,
    windows: PropTypes.array,
    breadcrumb: PropTypes.object,
  }),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onShowOnMap: PropTypes.func,
};
