/**
 * The forecast-detail facts the Plan-tab map overlay's popup showed and the location sheet did not
 * (overlay convergence C2, `docs/engineering/overlay-convergence-plan.md` §3 C2, owner decision D3).
 *
 * <p><b>Nothing here derives a verdict, a rating or a score.</b> Every figure is a field the
 * forecast row (`GET /api/forecast`, slim) or its detail row (`GET /api/forecast/{id}`) already
 * serves, formatted for display; the badges are filter/map/select over those fields, the already
 * licensed class (CLAUDE.md, *Backend-heavy*). Two thresholds were carried over UNCHANGED from the
 * since-deleted `MarkerPopupContent.jsx` because the popup applied them client-side before this
 * module existed: the dust test (`isDustEnhanced`) and the rising-tide window (`risingTideOf`). They
 * are MOVED, not invented, and this module is their ONE home. They are a debt with a name, not a
 * precedent: the exit for both is a served boolean on the detail DTO.
 *
 * <p>Role gating mirrors the popup's, section by section: a LITE reader gets the plain "High
 * aerosols" wording, no inversion badge, and a greyed, locked bluebell badge; surge, sea state and
 * rising tide carry no gate (the API serves them to every role).
 */
import { formatEventTimeUk, formatGeneratedAtFull, mpsToMph } from './conversions.js';
import { formatCompass } from './hourlyComfort.js';
import { modelLabel } from './modelLabels.js';

/** The rising-tide window, minutes either side of the solar event — the popup's own constant. */
const RISING_TIDE_WINDOW_MINUTES = 90;
/** AOD above which dust is "elevated" — the popup's own constant. */
const DUST_AOD = 0.3;
/** Dust concentration (µg/m³) above which dust is "elevated" — the popup's own constant. */
const DUST_UGM3 = 50;
/** PM2.5 (µg/m³) at or above which a dusty sky reads as smoke, not warm-tone dust. */
const DUST_PM25_CEILING = 35;
/** Bluebell score from which the badge appears. */
const BLUEBELL_MIN_SCORE = 2;
/** Bluebell score from which the badge is emphasised. */
const BLUEBELL_STRONG_SCORE = 4;

/** User-facing label for each TriageReason enum value - the one table the sheet and the popup share. */
const TRIAGE_REASON_LABELS = {
  HIGH_CLOUD: 'heavy low cloud at the solar horizon',
  PRECIPITATION: 'active precipitation forecast at event time',
  LOW_VISIBILITY: 'visibility too low (fog or heavy haze)',
  TIDE_MISALIGNED: 'tide not aligned with location preference',
  GENERIC: 'regional conditions predicted poor',
};

/**
 * Minutes from one ISO datetime to another.
 *
 * @param {string} a
 * @param {string} b
 * @returns {number} positive when {@code b} is after {@code a}
 */
function minutesBetween(a, b) {
  return (new Date(b) - new Date(a)) / 60000;
}

/**
 * The rising-tide warning: a high tide within 90 minutes either side of the solar event.
 *
 * @param {?object} detail the detail row ({@code solarEventTime}, {@code nextHighTideTime})
 * @returns {?{minutesAway: number, highTideTime: string}} null when none applies
 */
export function risingTideOf(detail) {
  if (!detail || !detail.solarEventTime || !detail.nextHighTideTime) return null;
  const diff = minutesBetween(detail.solarEventTime, detail.nextHighTideTime);
  if (Number.isNaN(diff) || diff < -RISING_TIDE_WINDOW_MINUTES || diff > RISING_TIDE_WINDOW_MINUTES) {
    return null;
  }
  return { minutesAway: Math.round(diff), highTideTime: detail.nextHighTideTime };
}

/**
 * Whether aerosols suggest mineral dust likely to enhance warm tones (high AOD or dust, with PM2.5
 * low enough to rule out smoke).
 *
 * @param {?object} detail the detail row
 * @returns {boolean}
 */
export function isDustEnhanced(detail) {
  if (!detail) return false;
  const aodHigh = detail.aerosolOpticalDepth != null && detail.aerosolOpticalDepth > DUST_AOD;
  const dustHigh = detail.dust != null && detail.dust > DUST_UGM3;
  const pm25Low = detail.pm25 == null || detail.pm25 < DUST_PM25_CEILING;
  return (aodHigh || dustHigh) && pm25Low;
}

/**
 * The words after the rising-tide clock time.
 *
 * @param {number} minutesAway signed minutes from the event to high water
 * @param {string} eventWord 'sunrise' or 'sunset'
 * @returns {string}
 */
function risingTideOffset(minutesAway, eventWord) {
  if (minutesAway > 0) return ` (${minutesAway} min after ${eventWord})`;
  if (minutesAway < 0) return ` (${Math.abs(minutesAway)} min before ${eventWord})`;
  return ` (at ${eventWord})`;
}

/**
 * Claude's valley mist reading as a badge, or null. Only the two readings worth a word draw one:
 * LIKELY ("Valley mist likely") and FOGBOUND ("Fogbound", a white frame, not mist with a view
 * through it). POSSIBLE, NONE and an absent field draw nothing. A lookup of a served band, the
 * inversion badge's twin, shared by the popup and the sheet so the two cannot word it apart
 * (valley-mist-plan M2).
 *
 * @param {?object} detail the detail row, or null
 * @returns {?{tone: string, text: string}}
 */
export function mistBadgeOf(detail) {
  switch (detail?.mistPotential) {
    case 'LIKELY': return { tone: 'sea', text: 'Valley mist likely' };
    case 'FOGBOUND': return { tone: 'amber', text: 'Fogbound' };
    default: return null;
  }
}

/**
 * The detail-derived badges for one window, in the popup's order: dust, inversion, valley mist,
 * bluebell, rising tide, sea state, surge. Each is {@code {key, tone, glyph, text, locked?}}; the glyph is
 * decorative and the text is the whole accessible reading.
 *
 * @param {?object} detail the detail row, or null while it loads / after a failed fetch
 * @param {{eventType: string, isPro: boolean}} ctx the window's event and the reader's tier
 * @returns {Array<{key: string, tone: string, glyph: string, text: string, locked?: boolean}>}
 */
export function detailBadges(detail, { eventType, isPro }) {
  if (!detail) return [];
  const badges = [];
  if (isDustEnhanced(detail)) {
    badges.push(isPro
      ? {
        key: 'dust', tone: 'amber', glyph: '🏜️',
        text: 'Elevated dust (e.g. Saharan dust) — expect unusually vivid warm tones',
      }
      : { key: 'dust', tone: 'amber', glyph: '🌫️', text: 'High aerosols' });
  }
  if (isPro && detail.inversionPotential && detail.inversionPotential !== 'NONE') {
    const strong = detail.inversionPotential === 'STRONG';
    badges.push({
      key: 'inversion',
      tone: strong ? 'violet' : 'blue',
      glyph: '☁️',
      text: strong
        ? 'Strong cloud inversion — dramatic sea of clouds likely'
        : 'Moderate cloud inversion — cloud blanket below viewpoint possible',
    });
  }
  const mist = mistBadgeOf(detail);
  if (isPro && mist) {
    badges.push({ key: 'mist', tone: mist.tone, glyph: '🌫️', text: mist.text });
  }
  if (detail.bluebellScore != null && detail.bluebellScore >= BLUEBELL_MIN_SCORE) {
    badges.push(isPro
      ? {
        key: 'bluebell',
        tone: detail.bluebellScore >= BLUEBELL_STRONG_SCORE ? 'indigo-strong' : 'indigo',
        glyph: '🌸',
        text: `Bluebell: ${detail.bluebellSummary ?? `${detail.bluebellScore}/5`}`,
      }
      : {
        key: 'bluebell', tone: 'indigo', glyph: '🌸', locked: true,
        text: 'Bluebell conditions — Upgrade to Pro',
      });
  }
  // Pro and Admin only (tide-light-pro-plan.md A7): the offset to the light is Pro.
  const tide = isPro ? risingTideOf(detail) : null;
  if (tide) {
    const eventWord = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
    badges.push({
      key: 'rising-tide',
      tone: 'amber',
      glyph: '⚠️',
      text: `Rising tide — high at ${formatEventTimeUk(tide.highTideTime)}`
        + `${risingTideOffset(tide.minutesAway, eventWord)}`,
    });
  }
  if (detail.seaState) {
    badges.push({
      key: 'sea-state',
      tone: 'sea',
      glyph: '🌊',
      text: `Seas ${Number(detail.significantWaveHeightMetres).toFixed(1)} m · ${detail.seaState}`,
    });
  }
  if (detail.surgeTotalMetres != null
    && (detail.surgeRiskLevel === 'HIGH' || detail.surgeRiskLevel === 'MODERATE')) {
    badges.push({
      key: 'surge',
      tone: 'amber',
      glyph: '⚡',
      text: `Surge ${Number(detail.surgeTotalMetres).toFixed(1)} m above normal`,
    });
  }
  return badges;
}

/**
 * The wind and comfort rows for one window, from the SLIM forecast row (so they need no detail
 * fetch): temperature and feels-like, wind, rain chance, and precipitation when there is any.
 * Empty without a temperature, exactly as the popup's block was.
 *
 * @param {?object} forecast the slim forecast row
 * @returns {Array<{key: string, icon: string, strong: string, rest: string}>}
 */
export function comfortRows(forecast) {
  if (!forecast || forecast.temperatureCelsius == null) return [];
  const feels = forecast.apparentTemperatureCelsius ?? forecast.temperatureCelsius;
  const compass = formatCompass(forecast.windDirection);
  const rows = [
    {
      key: 'temperature', icon: 'temperature',
      strong: `${Math.round(forecast.temperatureCelsius)}°C`,
      rest: ` · feels like ${Math.round(feels)}°C`,
    },
    {
      key: 'wind', icon: 'wind',
      strong: `${mpsToMph(forecast.windSpeed)} mph`,
      rest: compass ? ` ${compass}` : '',
    },
    {
      key: 'rain', icon: 'rain',
      strong: `${forecast.precipitationProbabilityPercent ?? 0}%`,
      rest: ' rain chance',
    },
  ];
  const mm = parseFloat(forecast.precipitation ?? 0);
  if (mm > 0) rows.push({ key: 'precip', icon: 'precip', strong: `${mm.toFixed(1)} mm`, rest: ' precip' });
  return rows;
}

/**
 * The "Forecast generated" footer: when, by which model, and — for a triaged row that no live
 * evaluation outranks — that Claude was not invoked. Null without a run time.
 *
 * @param {?object} forecast the slim forecast row ({@code forecastRunAt}, {@code triageReason})
 * @param {?object} detail the detail row ({@code evaluationModel}), or null
 * @param {boolean} triageSuperseded true when a live rating outranks this row's triage verdict, so
 *   the "not evaluated by Claude" clause would contradict the rating shown
 * @param {boolean} [isPro] false for a Lite reader, who gets the generic reason for a tide stand-down
 * @returns {?string}
 */
export function generatedFooter(forecast, detail, triageSuperseded = false, isPro = true) {
  if (!forecast?.forecastRunAt) return null;
  const base = formatGeneratedAtFull(forecast.forecastRunAt);
  if (!base) return null;
  const evaluationModel = detail?.evaluationModel;
  const model = evaluationModel && evaluationModel !== 'WILDLIFE' ? modelLabel(evaluationModel) : null;
  if (forecast.triageReason && !triageSuperseded) {
    // A Lite reader is not told the tide missed the location's light (tide-light-pro-plan.md A9):
    // the stand-down stays, the tide wording goes.
    const reasonKey = !isPro && forecast.triageReason === 'TIDE_MISALIGNED'
      ? 'GENERIC' : forecast.triageReason;
    const reason = TRIAGE_REASON_LABELS[reasonKey] ?? TRIAGE_REASON_LABELS.GENERIC;
    return model
      ? `Forecast generated: ${base} (${model} run, but not evaluated by Claude due to ${reason})`
      : `Forecast generated: ${base} (not evaluated by Claude due to ${reason})`;
  }
  return model ? `Forecast generated: ${base} by ${model}` : `Forecast generated: ${base}`;
}
