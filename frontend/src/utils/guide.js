/**
 * The in-app guide's addressing: where the pages live, what they are called, and which sections the
 * app opens (docs/engineering/user-guide-plan.md, G2).
 *
 * <p>The guide is nine static pages on the landing site (`landing/guide/`), shown in an iframe with
 * `?embed=1`. A copy change is a landing deploy, not an app release; an id rename is a frontend test
 * failure (`guideSections.test.js` reads `landing/guide/*.html`).
 */

/**
 * The guide site's origin, normalised (a trailing slash or a path in the env value still matches an
 * `event.origin`). `VITE_GUIDE_ORIGIN` is for local dev only: production's CSP `frame-src` is fixed
 * in the repo-root `nginx.conf`.
 */
const DEFAULT_GUIDE_ORIGIN = 'https://photocast.online';

/**
 * Parses a configured guide origin. A malformed value must never throw at module load (that would blank
 * the whole app), so it falls back to the default; in a production build a non-https origin also does.
 *
 * @param {?string} configured the raw `VITE_GUIDE_ORIGIN`
 * @param {boolean} production whether this is a production build
 * @returns {string} an origin
 */
export function resolveGuideOrigin(configured, production) {
  if (!configured) return DEFAULT_GUIDE_ORIGIN;
  try {
    const { origin, protocol } = new URL(configured);
    if (production && protocol !== 'https:') return DEFAULT_GUIDE_ORIGIN;
    return origin;
  } catch {
    return DEFAULT_GUIDE_ORIGIN;
  }
}

export const GUIDE_ORIGIN = resolveGuideOrigin(import.meta.env.VITE_GUIDE_ORIGIN, import.meta.env.PROD);

/** Page id (the file name without `.html`) to its display name, for the panel header. */
export const GUIDE_PAGES = Object.freeze({
  index: 'Guide',
  'getting-started': 'Getting started',
  'reading-the-forecast': 'Reading the forecast',
  'at-a-glance': 'At a Glance',
  'coming-up': 'Coming up',
  map: 'Map',
  'night-sky': 'Aurora and night sky',
  'ask-photocast': 'Ask PhotoCast',
  settings: 'Settings',
});

/** The tab id (as `WindowFirstShell` names it) to the guide page that explains it. No Operations entry. */
export const TAB_GUIDE = Object.freeze({
  plan: 'at-a-glance',
  'coming-up': 'coming-up',
  map: 'map',
});

/**
 * Every `page#section` the app opens. G3's links read from here, and `guideSections.test.js` checks
 * each id exists in `landing/guide/`. Rename one in the guide and it must change here too.
 */
export const GUIDE_SECTIONS = Object.freeze({
  lens: Object.freeze({ page: 'at-a-glance', section: 'lens' }),
  bestBet: Object.freeze({ page: 'at-a-glance', section: 'bestbet' }),
  fixed: Object.freeze({ page: 'reading-the-forecast', section: 'fixed' }),
  colour: Object.freeze({ page: 'reading-the-forecast', section: 'colour' }),
  fiery: Object.freeze({ page: 'reading-the-forecast', section: 'fiery' }),
  comingUpNew: Object.freeze({ page: 'coming-up', section: 'new' }),
  comingUpTopics: Object.freeze({ page: 'coming-up', section: 'topics' }),
  comingUpTides: Object.freeze({ page: 'coming-up', section: 'tides' }),
  mapWindow: Object.freeze({ page: 'map', section: 'window' }),
  mapFilters: Object.freeze({ page: 'map', section: 'filters' }),
  mapHotTopics: Object.freeze({ page: 'map', section: 'hot-topics' }),
  mapTide: Object.freeze({ page: 'map', section: 'tide' }),
  auroraAlerts: Object.freeze({ page: 'night-sky', section: 'alerts' }),
  auroraMap: Object.freeze({ page: 'night-sky', section: 'aurora-map' }),
  askTyped: Object.freeze({ page: 'ask-photocast', section: 'typed' }),
  subRegions: Object.freeze({ page: 'settings', section: 'sub-regions' }),
  settingsDrive: Object.freeze({ page: 'settings', section: 'drive' }),
  settingsRadius: Object.freeze({ page: 'settings', section: 'radius' }),
});

/**
 * A guide page's URL.
 *
 * @param {string} page a {@link GUIDE_PAGES} id
 * @param {?string} [hash] a section id, with or without the leading `#`
 * @param {{embed?: boolean}} [options] `embed` adds `?embed=1` (the iframe's form); omitted is the
 *        plain page the "Open in new tab" link goes to
 * @returns {string}
 */
export function guideUrl(page, hash = '', { embed = false } = {}) {
  const id = Object.prototype.hasOwnProperty.call(GUIDE_PAGES, page) ? page : 'index';
  const fragment = hash ? `#${String(hash).replace(/^#/, '')}` : '';
  return `${GUIDE_ORIGIN}/guide/${id}.html${embed ? '?embed=1' : ''}${fragment}`;
}

/**
 * The page id a `photocast-guide` message names. `guide.js` posts the last path segment
 * (`map.html`, or an empty string for the hub at `/guide/`), so `.html` is dropped and empty is the hub.
 *
 * @param {unknown} raw
 * @returns {?string} a {@link GUIDE_PAGES} id, or null when it is not one
 */
export function pageIdFromMessage(raw) {
  if (typeof raw !== 'string') return null;
  const id = raw.replace(/\.html$/, '') || 'index';
  return Object.prototype.hasOwnProperty.call(GUIDE_PAGES, id) ? id : null;
}

/** The accessible name of every presentation. The two-deep rule leaves a dialog carrying it out of its count. */
export const GUIDE_LABEL = 'PhotoCast guide';

/** The selector the two-deep rule counts: every layer that claims modality. */
export const MODAL_DIALOG_SELECTOR = '[role="dialog"][aria-modal="true"]';
