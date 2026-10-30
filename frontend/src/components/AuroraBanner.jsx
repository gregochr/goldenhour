import React, { Component, Suspense, lazy, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { auroraRampHex } from '../utils/auroraRamp.js';
import { ALERT_WORTHY, areaField, isRealAlert } from '../utils/auroraBannerScores.js';
import { formatInstantUk, parseUtcInstant } from '../utils/conversions.js';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { auroraNightLive } from '../utils/auroraTab.js';

// ⚠️ Lazy on purpose: the field thumbnail imports the heat kernel (d3-geo and the land chunk), and
// this banner is imported by App on every tab. A static import would put the kernel in the entry
// chunk for readers who never see a field (aurora-banner-plan.md §4 D6). The build check is
// `grep -l geoMercator dist/assets/index-*.js` finding nothing.
const AuroraFieldThumb = lazy(() => import('./AuroraFieldThumb.jsx'));

/**
 * Severity presentation, keyed by the API's alert level — NEVER by `status.hexColour`, which is the
 * API's amber/red and means "marginal / stand down" everywhere else in PhotoCast. An aurora storm is
 * good news, so severity rides the aurora ramp (docs/design/aurora-banner/README.md, "Severity
 * scale"). `acc` is the fill/glow colour, `ink` the TEXT colour (a ramp colour below `#3AC884` is
 * never text), `fill` the percentage of the glow strip's bottom edge that is lit, `breathes` whether
 * the row ever moves (the spec's Motion column: MODERATE none, STRONG `breathe` while live and Bz is
 * southward — plan §1 #10 equated the old pulse with `breathe` and missed that the old pulse had no
 * level term, the spec's table does).
 * SEVERE and EXTREME take STRONG's row: the API emits only MODERATE and STRONG today
 * ({@link ALERT_WORTHY}), but the mapping is one lookup if that ever changes
 * (docs/engineering/aurora-banner-plan.md §4 D13).
 */
const STRONG_ROW = { acc: '#76FFA0', ink: '#A8FFC4', fill: 92, breathes: true };
export const SEVERITY = Object.freeze({
  MODERATE: Object.freeze({ acc: '#3AC884', ink: '#8CE6B6', fill: 62, breathes: false }),
  STRONG: Object.freeze({ ...STRONG_ROW }),
  SEVERE: Object.freeze({ ...STRONG_ROW }),
  EXTREME: Object.freeze({ ...STRONG_ROW }),
});

const LEVEL_WORDS = { MODERATE: 'Moderate', STRONG: 'Strong', SEVERE: 'Severe', EXTREME: 'Extreme' };

/** The Kp meter's nine bars, bottom-up: Kp 0..8 steps spread over the ramp's 1★..5★. */
const METER_BARS = Array.from({ length: 9 }, (_, k) => k);
/** Bars at and above this index glow when lit — the top of the scale reads as a storm. */
const METER_GLOW_FROM = 6;
/**
 * One custom property per bar, so the meter's colours come from the ramp module and reach the CSS
 * the way the severity colours do — as properties on the root, never as per-element inline styles.
 */
const METER_VARS = Object.fromEntries(
  METER_BARS.map((k) => [`--bar-${k}`, auroraRampHex(1 + (4 * k) / 8)]),
);

/**
 * Formats the detection timestamp for the banner headline.
 *
 * - Same day: "21:34"
 * - Yesterday: "yesterday 21:34"
 * - Older: "3 Apr 21:34"
 *
 * <p>All three read the <b>UK</b> calendar and clock. `detectedAt` is a UTC `Instant` describing an
 * aurora over the UK, so the wall clock a reader wants is the one at the thing being described, not
 * the one on their own wrist — a 21:34 detection said "16:34" to the same person sitting in New
 * York.
 *
 * <p>⚠️ The day boundary moved with the clock, and had to. Rendering "21:34" in London while
 * deciding *which day* that was on the device's calendar is worse than either basis on its own: it
 * puts a UK time under a foreign day label, so a detection at 00:30 UK reads "yesterday 00:30".
 * Both questions now go to `mapDates`.
 *
 * @param {string} isoTimestamp - ISO 8601 instant from the API
 * @param {Date} [now] - the instant "today" is read from; injectable so tests can pin it
 * @returns {string|null} formatted detection label, or null if invalid/missing
 */
export function formatDetectedAt(isoTimestamp, now = new Date()) {
  const time = formatInstantUk(isoTimestamp, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  if (!time) return null;

  const detectedDay = ukDateStr(parseUtcInstant(isoTimestamp));
  if (detectedDay === ukDateStr(now)) return time;
  if (detectedDay === ukDateStrOffset(-1, now)) return `yesterday ${time}`;
  return `${formatInstantUk(isoTimestamp, { day: 'numeric', month: 'short' })} ${time}`;
}


/** Aurora "curtains" glyph used on the glow strip. */
function AuroraGlyph() {
  return (
    <svg viewBox="0 0 28 28" fill="none" aria-hidden="true">
      <g stroke="#fff" strokeWidth="1.5" strokeLinecap="round" opacity="0.92">
        <path d="M6 23c0-9 2-15 4-15" />
        <path d="M12 23c0-11 2-17 4-17" />
        <path d="M18 23c0-9 2-14 4-14" />
      </g>
      <circle cx="22" cy="5" r="1.6" fill="#fff" />
    </svg>
  );
}

/**
 * Bz as a signed one-decimal figure with a true minus sign (U+2212), judged on the ROUNDED value so a
 * reading of −0.04 never prints "−0.0".
 *
 * @param {number} bz the IMF Bz in nT
 * @returns {string} e.g. "−9.2", "+3.2", "0.0"
 */
function formatBz(bz) {
  const rounded = Number(bz.toFixed(1));
  const abs = Math.abs(rounded).toFixed(1);
  if (rounded < 0) return `−${abs}`;
  if (rounded > 0) return `+${abs}`;
  return abs;
}

/**
 * Whether triage found every dark-sky site overcast. An unknown clear count fails open: missing data
 * is not overcast.
 */
function isAllOvercast(status) {
  const dark = status.darkSkyLocationCount;
  const clear = status.clearLocationCount;
  return dark > 0 && clear != null && clear === 0;
}

/**
 * Whether the overcast gate hides this banner: every dark-sky site is overcast, and the alert is not
 * a major storm. Telling a photographer "aurora tonight" when every dark-sky location is overcast is
 * noise in the most valuable slot on the screen — NLC gates the same way (active && clearTonight) —
 * unless it is a STRONG storm, which is rare and worth surfacing under cloud (gaps open, people
 * travel). Fails OPEN when the clear count is unknown (missing data is not overcast). A simulated
 * alert is an admin preview tool that must always render: the gate is about real alerts nobody can
 * act on, not synthetic ones. One definition for the render below and for the viewline request, so a
 * banner nobody sees never asks for a line.
 *
 * @param {object} status the aurora status payload
 * @returns {boolean} true when the banner renders nothing
 */
function hiddenByOvercast(status) {
  const majorStorm = status.level === 'STRONG'; // extend if SEVERE/EXTREME join ALERT_WORTHY
  return isAllOvercast(status) && !majorStorm && status.simulated !== true;
}

/**
 * Everything the two layouts need, derived once from the status so the card and the strip can never
 * disagree about a word, a count or a colour.
 *
 * @param {object} status the aurora status payload (alert-worthy)
 * @param {?{best: {name: string, stars: number}}} field the planning area's rated field once
 *   per-location scores exist ({@link areaField}), else null — a simulated alert never has one
 * @returns {object} the presentation model
 */
function deriveModel(status, field) {
  const simulated = status.simulated === true;
  const forecast = !simulated && status.triggerType === 'forecast';
  const live = !simulated && !forecast;
  const sev = SEVERITY[status.level] ?? SEVERITY.MODERATE;
  const levelWord = LEVEL_WORDS[status.level]
    || (status.level ? status.level.charAt(0) + status.level.slice(1).toLowerCase() : 'Alert');
  const sevLabel = status.gScale ? `${levelWord} · ${status.gScale}` : levelWord;
  const kp = status.forecastKp ?? status.kp;
  const bz = status.bzNanoTesla;
  const dark = status.darkSkyLocationCount;
  const clear = status.clearLocationCount;
  return {
    simulated,
    forecast,
    sev,
    levelWord,
    pillText: simulated ? `${sevLabel} · simulated` : sevLabel,
    kp: kp != null ? Math.round(kp) : null,
    allOvercast: isAllOvercast(status),
    // The site count both layouts word: the clear count once triage has run, else the dark-sky total
    // (and then it is NOT "clear" — missing triage is not a clear sky). Null when there are no
    // Bortle-enriched locations to count.
    siteCount: dark > 0 ? (clear ?? dark) : null,
    sitesClear: dark > 0 && clear != null,
    // Bz is a LIVE reading: a forecast alert has no current Bz and a simulation has no real one.
    bzText: live && bz != null ? formatBz(bz) : null,
    // Live, southward Bz, and a row that moves at all: the one state the banner breathes in.
    breathing: sev.breathes && live && bz != null && bz < -1,
    description: status.description,
    detected: live ? formatDetectedAt(status.detectedAt) : null,
    // The best rated site in the reader's planning area; null while the slot is still pending.
    best: !simulated && field ? { name: field.best.name, stars: field.best.stars } : null,
  };
}

/**
 * A star rating: the digit, a visual ★ and a spoken word. NVDA at its default symbol level does not
 * speak U+2605, so a bare "4★" is announced as "4" and loses its unit (`WindowSheetDialog.jsx` records
 * the same finding); the glyph is hidden and " stars" is read instead, the pattern the rest of the
 * app uses. The visible text is unchanged.
 */
function Stars({ n }) {
  return (
    <span className="wf-aurora-stars">
      {n}
      <span aria-hidden="true">★</span>
      <span className="sr-only">{n === 1 ? ' star' : ' stars'}</span>
    </span>
  );
}

Stars.propTypes = { n: PropTypes.number.isRequired };

/** Noun for a site count: "1 site", "12 sites". */
function siteNoun(n) {
  return n === 1 ? 'site' : 'sites';
}

/**
 * The pending thumbnail: tonight's Kp on a nine-step vertical meter. Until per-location scores exist
 * nothing knows whether a field can be drawn, so this is the slot's only state in B1; the same
 * 96×118 box will hold the field later, so the swap shifts nothing.
 *
 * @param {object} props
 * @param {number|null} [props.kp] tonight's Kp rounded to a whole step; null draws an unlit meter
 * @param {boolean} [props.simulated] a simulated alert shows a test tube and no figure
 */
function PendingSlot({ kp = null, simulated = false }) {
  if (simulated) {
    return (
      <div className="wf-aurora-thumb-pend" data-testid="aurora-banner-slot">
        <span className="wf-aurora-flask" aria-hidden="true">🧪</span>
      </div>
    );
  }
  return (
    <div className="wf-aurora-thumb-pend" data-testid="aurora-banner-slot">
      {kp != null && <span className="wf-aurora-kp" data-testid="aurora-banner-kp">Kp {kp}</span>}
      <span className="wf-aurora-meter" aria-hidden="true" data-testid="aurora-banner-meter">
        {METER_BARS.map((k) => {
          const lit = kp != null && k < kp;
          return (
            <span
              key={k}
              className="wf-aurora-bar"
              data-testid="aurora-banner-bar"
              data-k={k}
              data-lit={lit ? 'true' : 'false'}
              data-glow={lit && k >= METER_GLOW_FROM ? 'true' : 'false'}
            />
          );
        })}
      </span>
      <span className="wf-aurora-pend-label" aria-hidden="true">Scoring / locations</span>
    </div>
  );
}

PendingSlot.propTypes = {
  kp: PropTypes.number,
  simulated: PropTypes.bool,
};

/** The planning area's rated field, as `areaField` builds it (the part the thumbnail reads). */
const FIELD_SHAPE = PropTypes.shape({
  spots: PropTypes.array.isRequired,
  fit: PropTypes.object.isRequired,
});

/**
 * Catches a failure of the lazy field chunk and shows the pending slot instead.
 *
 * <p>`React.lazy` rejects when a hashed chunk 404s — a page kept open across a deploy keeps the JS it
 * loaded — and since the provider lift the nearest boundary above the banner is `PlanErrorBoundary`,
 * so an unguarded thumbnail would take the whole shell to its fallback for a decorative picture
 * (aurora-banner-plan.md §5). A class, because React has no hook form of an error boundary.
 */
class FieldBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    const { fallback, children } = this.props;
    const { failed } = this.state;
    return failed ? fallback : children;
  }
}

FieldBoundary.propTypes = {
  fallback: PropTypes.node.isRequired,
  children: PropTypes.node.isRequired,
};

/**
 * The thumbnail slot's content: tonight's field once per-location scores exist for the planning
 * area, else the pending slot. The lazy chunk's `Suspense` fallback and its error fallback are BOTH
 * the pending slot, so a slow or failed chunk looks like "scores not here yet" and shifts nothing.
 *
 * @param {object} props
 * @param {number|null} props.kp the pending slot's Kp figure
 * @param {boolean} props.simulated a simulated alert shows a test tube and never a field
 * @param {?{spots: Array, fit: object}} props.field the area's rated sites and frame, or null
 * @param {?object} props.viewline the southern viewline to draw on the field, or null
 * @param {function(boolean): void} [props.onViewlineDrawn] told by the thumbnail whether the line is
 *   actually on its canvas; a slot that never mounts the thumbnail (pending, simulated, a failed
 *   chunk) never tells, which leaves the answer "no"
 */
function ThumbSlot({ kp, simulated, field, viewline = null, onViewlineDrawn = null }) {
  const pending = <PendingSlot kp={kp} simulated={simulated} />;
  if (simulated || !field) return pending;
  return (
    <FieldBoundary fallback={pending}>
      <Suspense fallback={pending}>
        <AuroraFieldThumb
          spots={field.spots}
          fit={field.fit}
          viewline={viewline}
          kp={kp}
          onViewlineDrawn={onViewlineDrawn}
        />
      </Suspense>
    </FieldBoundary>
  );
}

ThumbSlot.propTypes = {
  kp: PropTypes.number,
  simulated: PropTypes.bool.isRequired,
  field: FIELD_SHAPE,
  viewline: PropTypes.object,
  onViewlineDrawn: PropTypes.func,
};

/** The shape both layouts read; see {@link deriveModel}. */
const MODEL_SHAPE = PropTypes.shape({
  simulated: PropTypes.bool.isRequired,
  forecast: PropTypes.bool.isRequired,
  sev: PropTypes.shape({
    acc: PropTypes.string.isRequired,
    ink: PropTypes.string.isRequired,
    fill: PropTypes.number.isRequired,
    breathes: PropTypes.bool.isRequired,
  }).isRequired,
  levelWord: PropTypes.string.isRequired,
  pillText: PropTypes.string.isRequired,
  kp: PropTypes.number,
  allOvercast: PropTypes.bool.isRequired,
  siteCount: PropTypes.number,
  sitesClear: PropTypes.bool.isRequired,
  bzText: PropTypes.string,
  breathing: PropTypes.bool.isRequired,
  description: PropTypes.string,
  detected: PropTypes.string,
  best: PropTypes.shape({
    name: PropTypes.string.isRequired,
    stars: PropTypes.number.isRequired,
  }),
}).isRequired;

/**
 * The root's custom properties: the severity colours (and the strip's lit fraction) reach the
 * stylesheet here and nowhere else. The fraction travels twice: `--fill` as a percentage for the
 * lit edge's WIDTH, `--fill-ratio` as a bare number so the edge's background can be stretched back
 * to the whole strip (`background-size: calc(100% / var(--fill-ratio))` — a division by a number is
 * the one thing calc() allows, and a percentage cannot be one).
 */
export function rootVars(m, extra) {
  return {
    '--acc': m.sev.acc,
    '--acc-ink': m.sev.ink,
    '--fill': `${m.sev.fill}%`,
    '--fill-ratio': String(m.sev.fill / 100),
    ...extra,
  };
}

/**
 * The viewline's one spoken sentence: the response's `summary` with one small, deliberate departure
 * from verbatim - a full stop is added when the server's sentence ends without terminal punctuation
 * (all of its summaries do), so a screen reader pauses before the call to action. Punctuation
 * followed by a closing quote or bracket still counts as terminal.
 */
function spokenViewline(summary) {
  const text = summary.trim();
  // \x22 and \x27 are the straight quotes, escaped so no quote character sits in this source line.
  return /[.!?][)\]\x22\x27”’]*$/.test(text) ? text : `${text}.`;
}

/**
 * The call to action's words, or null when the banner has nowhere honest to go (overlay convergence
 * C4: a control that opens nothing is banned, so a banner with no door is a statement, not a button).
 * A real alert's door is the Map tab; a SIMULATED alert's is Operations for an admin, and a plain
 * "test alert" word for anyone else, because a simulation has no map and no scores to show.
 *
 * @param {object}    m             the presentation model
 * @param {?function} onActivate    the banner's door, or null
 * @param {string}    simulatedDoor the words when a simulated alert has a door
 * @param {string}    realDoor      the words when a real alert has a door
 * @param {string}    simulatedNoDoor the words for a simulated alert with none
 * @returns {?string}
 */
function ctaText(m, onActivate, simulatedDoor, realDoor, simulatedNoDoor) {
  if (m.simulated) return onActivate ? simulatedDoor : simulatedNoDoor;
  return onActivate ? realDoor : null;
}

/**
 * Whether a key press on the root should activate the surface. The dismiss button sits inside the
 * root and its Enter/Space bubbles here — without the target check a keyboard dismiss ALSO opened
 * the Map tab.
 */
function activates(e) {
  return e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ');
}

/**
 * The wide layout (≥ 640px): thumbnail slot at left, the alert in words at right.
 *
 * @param {object} props
 * @param {object} props.m the presentation model from {@link deriveModel}
 * @param {function} props.onActivate the banner's door (the Map tab, or Operations for a simulated alert), or null when it has none
 * @param {function} props.onDismiss dismisses the banner at its current level
 * @param {?object} props.field the planning area's rated field ({@link areaField}), or null while
 *   the slot is pending
 * @param {?object} [props.viewline] the southern viewline drawn on the field, or null
 * @param {?string} [props.viewlineSummary] the viewline response's sentence, spoken to assistive
 *   technology and drawn nowhere; null whenever the line is not on screen
 * @param {function(boolean): void} [props.onViewlineDrawn] handed to the thumbnail, which reports
 *   whether the line is on its canvas
 */
function AuroraCard({
  m, field = null, viewline = null, viewlineSummary = null, onViewlineDrawn = null, onActivate = null, onDismiss,
}) {
  const parts = [];
  if (m.best) {
    // Ready: the best rated site in the reader's area, then how many sites are clear. Without triage
    // the count is the dark-sky total, and then it is NOT "clear" (missing triage is not a clear sky).
    parts.push(
      <span key="best">
        Best: <b>{m.best.name}</b> <Stars n={m.best.stars} />
      </span>,
    );
    if (m.siteCount != null) {
      parts.push(
        <span key="sites">
          {m.siteCount} {m.sitesClear ? '' : 'dark-sky '}{siteNoun(m.siteCount)}{m.sitesClear ? ' clear' : ''}
        </span>,
      );
    }
  } else if (m.siteCount != null) {
    parts.push(
      <span key="sites">
        <b>{m.siteCount}</b> dark-sky {siteNoun(m.siteCount)}{m.sitesClear ? ' clear' : ''}
      </span>,
    );
  }
  if (m.bzText) {
    parts.push(<span key="bz">Bz <b>{m.bzText} nT</b></span>);
  }
  const cta = ctaText(m, onActivate, 'Operations →', 'View on map →', 'Test alert');
  const whenLabel = m.simulated ? 'Simulated' : m.forecast ? 'Forecast · tonight' : 'Active now';

  return (
    <>
      {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
      <div
        role="alert"
        data-testid="aurora-banner"
        data-layout="card"
        style={rootVars(m, METER_VARS)}
        className={[
          'wf-aurora-card',
          m.simulated && 'wf-aurora-simulated',
          m.breathing && 'wf-aurora-breathe',
        ].filter(Boolean).join(' ')}
        onClick={onActivate ?? undefined}
        onKeyDown={onActivate ? (e) => {
          if (!activates(e)) return;
          e.preventDefault();
          onActivate();
        } : undefined}
        tabIndex={onActivate ? 0 : undefined}
        data-actionable={onActivate ? 'true' : 'false'}
      >
        {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
        <div className="wf-aurora-thumb" data-testid="aurora-banner-thumb">
          <ThumbSlot
            kp={m.kp}
            simulated={m.simulated}
            field={field}
            viewline={viewline}
            onViewlineDrawn={onViewlineDrawn}
          />
        </div>
        <div className="wf-aurora-body">
          <span className="wf-aurora-pill" data-testid="aurora-banner-pill">
            <span className="wf-aurora-dot" aria-hidden="true" />
            {m.pillText}
          </span>
          <span className="wf-aurora-when" data-testid="aurora-banner-when">
            {whenLabel}
            {m.detected && (
              <>
                {' · '}
                <span data-testid="aurora-banner-detected">detected {m.detected}</span>
              </>
            )}
          </span>
          <p className="wf-aurora-title">{m.description}</p>
          {m.allOvercast ? (
            <p data-testid="aurora-banner-overcast" className="wf-aurora-best">
              All locations overcast — no clear skies forecast tonight
            </p>
          ) : (
            parts.length > 0 && (
              <p className="wf-aurora-best" data-testid="aurora-banner-best">
                {parts.map((part, i) => (
                  <React.Fragment key={part.key}>
                    {i > 0 && ' · '}
                    {part}
                  </React.Fragment>
                ))}
              </p>
            )
          )}
          {viewlineSummary && (
            // The line and its label on the thumbnail are aria-hidden decoration; this is the one
            // sentence of them a screen reader gets, inside the alert's own text (plan §6, closed B4).
            <span className="sr-only" data-testid="aurora-banner-viewline-sr">
              {spokenViewline(viewlineSummary)}
            </span>
          )}
          {cta && <span className="wf-aurora-cta" data-testid="aurora-banner-cta">{cta}</span>}
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss aurora banner"
          data-testid="aurora-banner-dismiss"
          className="wf-aurora-x"
        >
          ✕
        </button>
      </div>
    </>
  );
}

AuroraCard.propTypes = {
  m: MODEL_SHAPE,
  field: FIELD_SHAPE,
  viewline: PropTypes.object,
  viewlineSummary: PropTypes.string,
  onViewlineDrawn: PropTypes.func,
  onActivate: PropTypes.func,
  onDismiss: PropTypes.func.isRequired,
};

/**
 * The narrow layout (< 640px): one 56px strip — glyph, two lines, a `Map →` door, dismiss.
 *
 * @param {object} props
 * @param {object} props.m the presentation model from {@link deriveModel}
 * @param {function} props.onActivate the banner's door (the Map tab, or Operations for a simulated alert), or null when it has none
 * @param {function} props.onDismiss dismisses the banner at its current level
 */
function AuroraStrip({ m, onActivate = null, onDismiss }) {
  // The best site gets its own markup (a name that ellipsises and a rating that never does); every
  // other form is one text run.
  const showBest = !m.allOvercast && m.best;
  let detail = null;
  if (m.allOvercast) {
    detail = 'all sites overcast';
  } else if (!showBest && m.siteCount != null) {
    detail = `${m.siteCount} dark ${siteNoun(m.siteCount)}${m.sitesClear ? ' clear' : ''}`;
  }
  const cta = ctaText(m, onActivate, 'Operations →', 'Map →', 'Test');
  const headline = m.simulated ? 'Aurora simulated' : m.forecast ? 'Aurora tonight' : 'Aurora active now';

  return (
    <>
      {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
      <div
        role="alert"
        data-testid="aurora-banner"
        data-layout="strip"
        style={rootVars(m)}
        className={[
          'wf-aurora-strip',
          m.simulated && 'wf-aurora-simulated',
          m.breathing && 'wf-aurora-breathe',
        ].filter(Boolean).join(' ')}
        onClick={onActivate ?? undefined}
        onKeyDown={onActivate ? (e) => {
          if (!activates(e)) return;
          e.preventDefault();
          onActivate();
        } : undefined}
        tabIndex={onActivate ? 0 : undefined}
        data-actionable={onActivate ? 'true' : 'false'}
      >
        {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/no-noninteractive-tabindex */}
        <span className="wf-aurora-glyph" aria-hidden="true">
          {m.simulated ? '🧪' : <AuroraGlyph />}
        </span>
        <div className="wf-aurora-strip-text">
          <p className="wf-aurora-strip-l1" data-testid="aurora-banner-strip-headline">
            <span className="wf-aurora-strip-lvl">{m.levelWord}</span>{' '}
            {headline}
          </p>
          {(m.kp != null || detail || showBest) && (
            <p
              className={showBest ? 'wf-aurora-strip-l2 wf-aurora-strip-l2-best' : 'wf-aurora-strip-l2'}
              data-testid="aurora-banner-strip-detail"
            >
              {m.kp != null && <b>Kp {m.kp}</b>}
              {showBest ? (
                <>
                  <span className="wf-aurora-keep">{m.kp != null ? ' · best ' : 'best '}</span>
                  <span className="wf-aurora-strip-name">{m.best.name}</span>
                  <span className="wf-aurora-keep"> </span>
                  <Stars n={m.best.stars} />
                </>
              ) : (
                <>
                  {m.kp != null && detail && ' · '}
                  {detail}
                </>
              )}
            </p>
          )}
        </div>
        {cta && <span className="wf-aurora-strip-cta" data-testid="aurora-banner-cta">{cta}</span>}
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss aurora banner"
          data-testid="aurora-banner-dismiss"
          className="wf-aurora-x wf-aurora-x-strip"
        >
          ✕
        </button>
      </div>
    </>
  );
}

AuroraStrip.propTypes = {
  m: MODEL_SHAPE,
  onActivate: PropTypes.func,
  onDismiss: PropTypes.func.isRequired,
};

/**
 * Aurora alert banner displayed when NOAA SWPC reports a moderate or strong alert.
 *
 * Behaviour:
 * - Not rendered for free-tier users (403 from the API → status is null)
 * - Not rendered for QUIET or MINOR status, nor for a MODERATE alert under full overcast
 * - Severity rides the aurora ramp, chosen from `status.level` (never the API's `hexColour`)
 * - At 640px and up a card: a thumbnail slot (Kp on a nine-step meter while no per-location scores
 *   exist for the planning area, tonight's aurora field once they do), the level pill, when-line,
 *   description, best/sites-clear line and a call to action
 * - Below 640px a 56px glow strip
 * - Needs the briefing provider for the planning area and the HOME drive (App mounts it above the
 *   banner block); without one the field falls back to every rated site
 * - The card's field carries the southern viewline (live first, forecast else) once it is drawn
 * - A STRONG alert breathes gently while it is live and Bz is southward (< −1 nT)
 * - Dismissible per session; re-appears if the level escalates
 *
 * @param {object} props
 * @param {function} [props.onViewOnMap] - Called, with the Plan's live lens
 *   (`{minRating, limitMinutes}`), when the banner is activated for a live/forecast alert; the parent
 *   opens the Map tab on the aurora night. When omitted the banner has no door and no call to action.
 * @param {function} [props.onOpenSimulated] - Called when a SIMULATED alert's banner is activated (the
 *   admin's Operations tab). When omitted a simulated banner is a statement, not a button.
 */
export default function AuroraBanner({ onViewOnMap = null, onOpenSimulated = null }) {
  const { status } = useAuroraStatus();
  const isMobile = useIsMobile();
  const [dismissedLevel, setDismissedLevel] = useState(null);
  // Whether the thumbnail has the viewline on its canvas, as the thumbnail itself reports it. The
  // request's own gate cannot know: a chunk still loading, a failed chunk, a declined draw or a
  // line the projection cannot place all leave the slot without a line while the data is in hand.
  const [lineDrawn, setLineDrawn] = useState(false);

  // Tonight's per-location ratings, restricted to the reader's planning area (plan §4 D8). The hooks
  // run before the early returns below: nothing is asked for a status that is null, not alert-worthy
  // or simulated (the hook gates itself), and `useWindowFirstBriefing` answers empty outside the
  // briefing provider — then every rated site counts and frames itself, the degrade path.
  // A banner dismissed at this level draws nothing, so it asks for nothing either (null is "no alert"
  // to the hook); an escalation changes the level and asks again.
  const dismissed = Boolean(status) && dismissedLevel === status.level;
  const scores = useAuroraScores(dismissed ? null : status);
  const { heatSpots, reachById, origin, reachLens, ratingLens } = useWindowFirstBriefing();
  const field = useMemo(
    () => (status?.simulated === true ? null : areaField(scores.spots, heatSpots, reachById, origin)),
    [status?.simulated, scores.spots, heatSpots, reachById, origin],
  );

  // The southern viewline is drawn on the field and nowhere else, so it is asked for only when that
  // field is actually on screen: a real alert (not simulated), not dismissed, the wide layout (the
  // strip has no thumbnail), the planning area's rated field present, and the banner not suppressed
  // by the overcast gate below. No request while the slot shows the Kp meter or a test tube. Both
  // sources, the hook's default: the live OVATION contour, else NOAA's forecast line — the Map tab's
  // own precedence. Enabled by the owner, 2026-10-12 (plan §4 D4); there is no flag.
  const viewlineEnabled = isRealAlert(status) && !dismissed && !isMobile && field !== null
    && !hiddenByOvercast(status);
  const { viewline } = useAuroraViewline(viewlineEnabled);

  // Not eligible (free-tier, 403) or not yet loaded
  if (!status) return null;

  // Not alert-worthy
  if (!ALERT_WORTHY.has(status.level)) return null;

  // Dismissed at this exact level — re-show only on escalation
  if (dismissedLevel === status.level) return null;

  const m = deriveModel(status, field);

  // Gate on clear skies (see hiddenByOvercast).
  if (hiddenByOvercast(status)) return null;

  function handleDismiss(e) {
    e.stopPropagation();
    setDismissedLevel(status.level);
  }

  // The banner's door, or null when it has none (overlay convergence C4). A REAL alert hands the Plan's
  // live lens (rating floor, reach tier) to `onViewOnMap`, which opens the Map tab on the aurora night;
  // a SIMULATED alert has no map (no scores, no stored run - `utils/auroraTab.js`), so its door is the
  // admin's Operations tab (`onOpenSimulated`, withheld from everyone else). With no handler the banner
  // is a statement: no press, no tab stop, no call to action - never the dead hash write it once made.
  //
  // A real alert's door also needs the Map tab to HAVE a night row for it (overlay convergence C4
  // review): either the live night the Map tab's own offer tests (`auroraNightLive` - active, real,
  // its night not over) or a rated run to select (`scores.ready`, the stored run for the night in
  // progress or the live field). A stale or inactive status with a forecast on screen has neither, and
  // the press would leave the map on a solar window.
  const nightRowAvailable = auroraNightLive(status) || scores.ready;
  let handleActivate = null;
  if (m.simulated) {
    handleActivate = onOpenSimulated ? () => onOpenSimulated() : null;
  } else if (onViewOnMap && nightRowAvailable) {
    handleActivate = () => onViewOnMap({
      minRating: ratingLens?.minRating ?? null,
      limitMinutes: reachLens?.tier?.limitMinutes ?? null,
    });
  }

  // The line is spoken exactly when it is drawn: the thumbnail says a line is on its canvas
  // (`lineDrawn`), the request's own gate still holds (never a test tube, the strip or a dismissed
  // card), the line is active, and there is a sentence to speak. Never the data predicate alone.
  const viewlineSummary = lineDrawn && viewlineEnabled && viewline?.active === true
    && typeof viewline.summary === 'string' && viewline.summary.trim() !== ''
    ? viewline.summary
    : null;

  // The strip has no thumbnail, so only the card is handed the field.
  return isMobile
    ? <AuroraStrip m={m} onActivate={handleActivate} onDismiss={handleDismiss} />
    : (
      <AuroraCard
        m={m}
        field={field}
        viewline={viewline}
        viewlineSummary={viewlineSummary}
        onViewlineDrawn={setLineDrawn}
        onActivate={handleActivate}
        onDismiss={handleDismiss}
      />
    );
}

AuroraBanner.propTypes = {
  onViewOnMap: PropTypes.func,
  onOpenSimulated: PropTypes.func,
};
