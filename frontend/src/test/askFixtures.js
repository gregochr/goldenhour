/**
 * Fixtures for Ask PhotoCast's client tests.
 *
 * <p>The wire shapes are `docs/engineering/ask-photocast-plan.md` §2.9's JSON, key for key: a pick is
 * `{rank, locationId, locationName, regionName, date, targetType, windowId, why}` and carries no
 * score (the client joins those), an event carries `safetyNote` only when the served topic has one,
 * a typed answer carries `charged`/`allowanceLeft`/`allowanceLimit`, and `try` is `{id, text}` and
 * nothing more. Only the prose is invented.
 *
 * <p>The clock is a parameter of the suite, never read here: every date is a literal, and
 * {@link NOW} is the instant the suites freeze to (Monday 5 October 2026, mid-morning UK).
 */

import { factsOf } from './tideFactsFixture.js';

/** The instant every Ask suite freezes the clock to — a Monday, so Saturday is the 10th. */
export const NOW = new Date('2026-10-05T09:00:00Z');

export const WHITBY = 123;
export const SALTBURN = 124;
export const ROSEBERRY = 125;
/** A dark-sky place that only the night payloads carry (it has no solar slot in the fixture briefing). */
export const KIELDER = 130;

/** The eclipse's lens-filter warning, as the server serves it on the event. */
export const ECLIPSE_SAFETY_NOTE = 'Certified solar filter on the lens — not only over your eye';

/** A slot as the briefing serves it. */
export function slot(over = {}) {
  return {
    locationId: WHITBY,
    locationName: 'Whitby',
    solarEventTime: '2026-10-05T17:41:00',
    claudeRating: 5,
    displayVerdict: 'WORTH_IT',
    canopy: false,
    tideState: 'HIGH',
    tideAligned: true,
    tideShortfall: null,
    tideFitPhrase: 'high water at the light',
    ...over,
  };
}

/** A solar window's event summary: one region, its slots. */
function summary(targetType, date, time, slots, regionName = 'North York Moors & Coast') {
  return {
    targetType,
    regions: [{
      regionName,
      displayVerdict: 'WORTH_IT',
      verdict: 'GO',
      summary: '',
      slots,
    }],
    unregioned: [],
    window: {
      verdict: 'WORTH_IT', badges: [], eventTime: `${date}T${time}`, tideFacts: factsOf(slots),
    },
  };
}

/**
 * The briefing the join reads: tonight's sunset with three places, tomorrow's sunrise with one.
 * Whitby is coastal and its tide matches; Saltburn is coastal and misses (wants the water lower);
 * Roseberry is inland, so it carries no tide fact at all.
 */
export function briefing() {
  return {
    generatedAt: '2026-10-05T05:02:11',
    days: [
      {
        date: '2026-10-05',
        eventSummaries: [summary('SUNSET', '2026-10-05', '17:41:00', [
          slot(),
          slot({
            locationId: SALTBURN,
            locationName: 'Saltburn',
            claudeRating: 4,
            tideAligned: false,
            tideShortfall: 'LOWER',
            tideFitPhrase: 'wants low water',
          }),
          slot({
            locationId: ROSEBERRY,
            locationName: 'Roseberry Topping',
            claudeRating: 3,
            displayVerdict: 'MAYBE',
            tideState: null,
            tideAligned: null,
            tideFitPhrase: null,
          }),
        ])],
      },
      {
        date: '2026-10-06',
        eventSummaries: [summary('SUNRISE', '2026-10-06', '05:58:00', [
          slot({ solarEventTime: '2026-10-06T05:58:00', claudeRating: 4 }),
        ])],
      },
    ],
    renderedEvents: [
      { date: '2026-10-05', targetType: 'SUNSET' },
      { date: '2026-10-06', targetType: 'SUNRISE' },
    ],
  };
}

/** A pick exactly as `GET /api/ask/ready` and `POST /api/ask` serve one. */
export function pick(over = {}) {
  return {
    rank: 1,
    locationId: WHITBY,
    locationName: 'Whitby',
    regionName: 'North York Moors & Coast',
    date: '2026-10-05',
    targetType: 'SUNSET',
    windowId: '2026-10-05_sunset',
    why: 'Clear to the west and the tide is in at the light.',
    ...over,
  };
}

/** An event card exactly as served. */
export function eclipseEvent(over = {}) {
  return {
    type: 'ECLIPSE',
    label: 'Partial solar eclipse',
    date: '2026-10-10',
    why: 'A partial eclipse peaks at midday on Saturday.',
    safetyNote: ECLIPSE_SAFETY_NOTE,
    ...over,
  };
}

/** The Ready list of §2.9 (`GET /api/ask/ready?scope=all`), with the catalogue's tabs. */
export function readyResponse() {
  return {
    scope: 'all',
    questions: [
      {
        id: 'BEST_NEXT',
        text: 'Best spot tonight?',
        tabs: ['plan', 'map'],
        generatedAt: '2026-10-05T05:02:11',
        runLabel: '06:02',
        answer: {
          answerable: true,
          kind: 'ready',
          summary: 'Whitby is the best of tonight, with the tide in at the light.',
          picks: [pick(), pick({
            rank: 2,
            locationId: SALTBURN,
            locationName: 'Saltburn',
            why: 'Close behind, though the water is wrong for it.',
          })],
          events: [],
          missing: null,
          try: [],
        },
      },
      {
        id: 'AM_OR_PM',
        text: 'Sunrise or sunset tomorrow?',
        tabs: ['plan'],
        generatedAt: '2026-10-05T05:02:11',
        runLabel: '06:02',
        answer: {
          answerable: true,
          kind: 'ready',
          summary: 'Tomorrow’s sunrise at Whitby beats tonight.',
          picks: [pick({
            date: '2026-10-06', targetType: 'SUNRISE', windowId: '2026-10-06_sunrise',
          })],
          events: [],
          missing: null,
          try: [],
        },
      },
      {
        id: 'COASTAL_HIGH',
        text: 'Best coastal spot at high tide?',
        tabs: ['map'],
        generatedAt: '2026-10-05T17:03:40',
        runLabel: '18:03',
        answer: {
          answerable: true,
          kind: 'ready',
          summary: 'Whitby at high water tonight.',
          picks: [pick()],
          events: [],
          missing: null,
          try: [],
        },
      },
      {
        id: 'RARE_EVENTS',
        text: 'Any rare events coming up?',
        tabs: ['coming-up', 'map'],
        generatedAt: '2026-10-05T05:02:11',
        runLabel: '06:02',
        answer: {
          answerable: true,
          kind: 'ready',
          summary: 'A partial solar eclipse on Saturday is the one to plan for.',
          picks: [],
          events: [eclipseEvent()],
          missing: null,
          try: [],
        },
      },
    ],
  };
}

/** A typed answer (`kind: own`, charged) as `POST /api/ask` serves one. */
export function ownResponse(over = {}) {
  return {
    answerable: true,
    kind: 'own',
    summary: 'Saltburn is your best bet within the hour, though Whitby edges it on the light.',
    picks: [
      pick({ rank: 1, locationId: SALTBURN, locationName: 'Saltburn', why: 'Close, and the sky is open.' }),
      pick({ rank: 2 }),
    ],
    events: [eclipseEvent()],
    missing: null,
    try: [],
    allowanceLeft: 2,
    allowanceLimit: 3,
    charged: true,
    generatedAt: '2026-10-05T05:02:11',
    runLabel: '06:02',
    ...over,
  };
}

/** A `kind: cant` reply — the pre-filter's shape, with no briefing time to name. */
export function cantResponse(over = {}) {
  return {
    answerable: false,
    kind: 'cant',
    summary: 'PhotoCast covers sky colour, weather and tides. It has no car park information.',
    picks: [],
    events: [],
    missing: 'car park information',
    try: [
      { id: 'BEST_NEXT', text: 'Best spot tonight?' },
      { id: 'RARE_EVENTS', text: 'Any rare events coming up?' },
    ],
    allowanceLeft: 3,
    allowanceLimit: 3,
    charged: false,
    generatedAt: null,
    runLabel: null,
    ...over,
  };
}

/** `GET /api/user/settings/ask`. */
export function settings(over = {}) {
  return { enabled: true, used: 0, limit: 3, left: 3, typedAvailable: true, ...over };
}

/**
 * A night pick exactly as `POST /api/ask` serves one (`docs/engineering/ask-night-plan.md` §2.1): Ask's own
 * window kind in `targetType` (`ASTRO` | `AURORA`, never a persisted `TargetType`), the `YYYY-MM-DD_astro`
 * window id, the star at answer time and no verdict.
 */
export function nightPick(over = {}) {
  return {
    rank: 1,
    locationId: KIELDER,
    locationName: 'Kielder Observatory',
    regionName: 'Northumberland & Tyneside',
    date: '2026-10-05',
    targetType: 'ASTRO',
    windowId: '2026-10-05_astro',
    why: 'Low cloud all night and Bortle 3 — the clearest sky on offer.',
    ratingAtAnswer: 4,
    verdictAtAnswer: null,
    ...over,
  };
}

/** One row of `GET /api/astro/conditions?date=` (`AstroConditionsDto`). 05 Oct is BST: 19:40Z is 20:40. */
export function astroRow(over = {}) {
  return {
    locationId: KIELDER,
    locationName: 'Kielder Observatory',
    lat: 55.23,
    lon: -2.58,
    bortleClass: 3,
    stars: 4,
    summary: 'Clear and dark: low cloud, good visibility, little moon.',
    cloudExplanation: 'Cloud cover 12% — clear for most of the night.',
    visibilityExplanation: 'Visibility 30 km.',
    moonExplanation: 'Moon 23%, set before midnight.',
    forecastDate: '2026-10-05',
    moonPhase: 'Waxing Crescent',
    moonIlluminationPct: 23.4,
    nightStart: '2026-10-05T19:40:00',
    nightEnd: '2026-10-06T04:10:00',
    ...over,
  };
}

/** One row of `GET /api/aurora/forecast/results?date=` (`AuroraForecastResultDto`). */
export function auroraRow(over = {}) {
  return {
    locationId: KIELDER,
    locationName: 'Kielder Observatory',
    lat: 55.23,
    lon: -2.58,
    bortleClass: 3,
    stars: 3,
    summary: 'Kp 6 forecast; dark horizon to the north.',
    detail: null,
    triaged: false,
    triageReason: null,
    alertLevel: 'MODERATE',
    maxKp: 6,
    nightStart: '2026-10-05T19:40:00',
    nightEnd: '2026-10-06T04:10:00',
    ...over,
  };
}

/** A promise a test settles by hand — the late-response tests' hand-held request. */
export function deferred() {
  const d = {};
  d.promise = new Promise((resolve, reject) => {
    d.resolve = resolve;
    d.reject = reject;
  });
  return d;
}
