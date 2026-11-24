/**
 * The map view's address: `#map/<yyyy-mm-dd>/<sunrise|sunset|night|aurora>[/<locationId>]`
 * (`docs/engineering/one-screen-plan.md` D4). Pure parse and format, no React and no history calls: the
 * dialog-history engine writes the string (`utils/dialogHistory.js`, the per-layer `url`), and `App` reads it once
 * at load (`takeMapRoute`).
 *
 * <p>No personal data rides it: no origin, postcode, drive limit or lens. Area, region and names focus have no URL
 * form, so a refresh keeps only the window and (for a place) its id.
 */

/** The hash prefix every map address starts with. */
export const MAP_HASH = '#map';

/** URL word to the served `targetType`. */
const EVENT_BY_WORD = Object.freeze({
  sunrise: 'SUNRISE',
  sunset: 'SUNSET',
  night: 'ASTRO',
  aurora: 'AURORA',
});

/** Served `targetType` to URL word. High water and anything else has no address form. */
const WORD_BY_EVENT = Object.freeze({
  SUNRISE: 'sunrise',
  SUNSET: 'sunset',
  ASTRO: 'night',
  AURORA: 'aurora',
});

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Whether a string is a real calendar date, not merely date-shaped ("2026-13-45" is not).
 *
 * @param {string} value
 * @returns {boolean}
 */
function isRealDate(value) {
  if (!DATE_PATTERN.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const stamp = new Date(Date.UTC(year, month - 1, day));
  return stamp.getUTCFullYear() === year && stamp.getUTCMonth() === month - 1 && stamp.getUTCDate() === day;
}

/**
 * Parses a location hash.
 *
 * <p>Anything that is not a map address at all (`#manage/runs`, an empty hash, a token) is {@code null}. A map
 * address with bad or missing parts still opens the map: the parts that fail come back {@code null} rather than
 * throwing, so `#map/not-a-date/sunrise` is "the map, no window" and `#map/2026-11-24/sunrise/abc` is "the window,
 * no place".
 *
 * @param {?string} hash the `location.hash`, with or without its `#`
 * @returns {?{date: ?string, eventType: ?string, locationId: ?number}} null when the hash is not a map address
 */
export function parseMapRoute(hash) {
  if (typeof hash !== 'string') return null;
  const text = hash.startsWith('#') ? hash : `#${hash}`;
  if (text !== MAP_HASH && !text.startsWith(`${MAP_HASH}/`)) return null;
  const [, date = '', word = '', idPart = ''] = text.split('/');
  const eventType = EVENT_BY_WORD[word] ?? null;
  const validWindow = isRealDate(date) && eventType != null;
  const id = /^[1-9]\d{0,9}$/.test(idPart) ? Number(idPart) : null;
  return {
    date: validWindow ? date : null,
    eventType: validWindow ? eventType : null,
    locationId: validWindow ? id : null,
  };
}

/**
 * Formats the address for a window and an optional place.
 *
 * <p>A window with no address form (high water, nothing selected) formats as the bare `#map`, which is still a
 * valid address: it opens the map on its own default window.
 *
 * @param {?{date: ?string, eventType: ?string, locationId: ?(number|string)}} [route]
 * @returns {string} e.g. `#map/2026-11-24/sunrise/12`
 */
export function formatMapRoute(route = null) {
  const word = WORD_BY_EVENT[route?.eventType];
  if (!word || !isRealDate(route?.date ?? '')) return MAP_HASH;
  const base = `${MAP_HASH}/${route.date}/${word}`;
  const id = Number(route.locationId);
  return Number.isInteger(id) && id > 0 ? `${base}/${id}` : base;
}

/**
 * Whether a load-time route's window can be shown (D4): `'past'` (a date before today, or today's window that no
 * longer has a card), `'unforecast'` (a date the forecast does not cover), or `'ok'`. Unknown inputs never invent a
 * complaint: with no list of forecast dates yet, a future date is `'ok'`.
 *
 * @param {{date: ?string, eventType: ?string}} window the parsed window
 * @param {object} facts
 * @param {string} facts.todayStr the UK civil date
 * @param {?Array<string>} facts.forecastDates the dates the reader holds a colour forecast for (null: not known)
 * @param {?Set<string>} facts.windowKeys the `date:TYPE` keys the screen has a card for (null: not known)
 * @returns {'ok'|'past'|'unforecast'}
 */
export function routeWindowStatus(window, { todayStr, forecastDates = null, windowKeys = null }) {
  if (!window?.date || !window.eventType) return 'ok';
  if (window.date < todayStr) return 'past';
  const solar = window.eventType === 'SUNRISE' || window.eventType === 'SUNSET';
  if (Array.isArray(forecastDates) && solar && !forecastDates.includes(window.date)) return 'unforecast';
  if (solar && window.date === todayStr && windowKeys && !windowKeys.has(`${window.date}:${window.eventType}`)) {
    return 'past';
  }
  return 'ok';
}

/** The page's own path and query without any hash: where closing the map view returns the address to. */
export function bareAddress(loc = typeof window === 'undefined' ? null : window.location) {
  return loc ? `${loc.pathname}${loc.search}` : '';
}

/** The route read at load, kept so React StrictMode's double render and a re-mount see the same answer. */
let loadRoute;

/**
 * Reads the map address once per page load and rewrites the address bar to the bare path (D4). The route is
 * remembered until {@link consumeMapRoute}, so a StrictMode double-invoked initialiser reads the same value. Call
 * only after `takeLinkToken` / `takeInviteToken` have run (they own `#token=` and `#invite=` fragments, which are
 * never a map address).
 *
 * @returns {?{date: ?string, eventType: ?string, locationId: ?number}} null when the address carried no route
 */
export function takeMapRoute() {
  if (loadRoute !== undefined) return loadRoute;
  if (typeof window === 'undefined') return null;
  loadRoute = parseMapRoute(window.location.hash);
  if (loadRoute) {
    try {
      window.history.replaceState(window.history.state, '', bareAddress(window.location));
    } catch {
      // A hostile history: the route is still honoured, the address just keeps its hash.
    }
  }
  return loadRoute;
}

/** Spends the load route so a rewind remount (or any later mount) does not replay it. */
export function consumeMapRoute() {
  loadRoute = null;
}

/** Test seam: forgets the remembered load route so the next {@link takeMapRoute} reads the address again. */
export function __resetMapRouteForTests() {
  loadRoute = undefined;
}
