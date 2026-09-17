import { useCallback, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { DRIVE_TIME_TIERS } from './FiltersPopover.jsx';
import { watchDeparture } from '../../utils/watchDeparture.js';

/**
 * The plain word for an EV row's kind, mirroring the design bundle's `e.name.toLowerCase()`
 * (`map-tab-v2.js:521`) — never `ForecastTypeSelector.EVENT_TYPE_LABELS`, whose strings carry an
 * emoji prefix meant for a toggle bar, not a sentence.
 */
const KIND_WORD = {
  SUNRISE: 'sunrise',
  SUNSET: 'sunset',
  ASTRO: 'astro',
  AURORA: 'aurora',
};

/**
 * A door's landing strip (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 3) — mounted by
 * `MapView` ABOVE the map frame, outside the Leaflet container entirely (§4 #3: the increment's
 * "anything drawn over a field must be seeded as an obstacle" rule is honoured vacuously here,
 * because nothing is drawn over anything). Tab-only: the overlay never carries a `planHandoff` at
 * all, so this never mounts there.
 *
 * <h2>Every carrying clause is a live claim, never a stored one</h2>
 *
 * <p>Plan §5 rule 3 and §2's own "a true statement, derived, never stored": each clause below is
 * recomputed from `MapView`'s CURRENT filter/scope/origin state against what the door carried, not
 * from the door's payload alone. The prototype prints the URL's facts regardless of what the reader
 * has since changed on the map — an honest-claim defect this component exists not to repeat. Move
 * the floor after landing and the `★` clause disappears on the next render; the others, untouched,
 * stay.
 *
 * <p>The origin clause is the one exception to "compares against what was carried" — the payload
 * never carries an origin at all (§2: origin is shared state, not copied), so its only test is
 * "is one in force right now", regardless of which one.
 *
 * <h2>`clear` is four separate resets, not one</h2>
 *
 * <p>Four callback props rather than one `onClear`, so the caller's own tests can pin the ORDER —
 * rating, then reach, then scope, then origin, mirroring the prototype's `crumbclr`
 * (`map-tab-v2.js:526`) exactly. Subjects and dark-sky are deliberately absent: the prototype never
 * clears them and this door never carried them (plan §5 decision 1 names only rating/reach/region/
 * location as carried facts).
 *
 * <h2>⚠️ `clear` takes itself away, so it hands the reader's focus to the strip</h2>
 *
 * <p>A press on `clear` usually ends every clause, so the carrying span unmounts with `clear`
 * inside it while `clear` holds focus. Focus then fell to {@code <body>} (measured in Chromium 151,
 * WebKit 26.5 and Firefox 153, under StrictMode): outside `MapView`'s pane-scoped
 * {@code onKeyDown}, so {@code Escape} stopped reaching the pane, and no ring showed where the
 * reader was. Tab itself was spared: each engine starts the next Tab from the removed button's
 * place (in WebKit, Option+Tab is what reaches buttons).
 *
 * <p>So the strip is a place focus can be PUT ({@code tabIndex={-1}}, never a Tab stop), and a
 * `clear` that leaves while it HOLDS focus hands that focus to it. The record is made in `clear`'s
 * ref cleanup by `utils/watchDeparture.js`, whose doc says why a ref cleanup and never focus
 * events, and what an owner must do with the record. Focus goes to the strip and not to `← Plan`,
 * the one control that survives: a key that reaches `← Plan` leaves the Map tab, and one reaches it
 * straight after the press whenever Enter is held down, where the strip has nothing to activate
 * (measured with the handoff aimed at `← Plan` instead: the first key repeat of a held Enter
 * pressed it, in all three engines). The strip's text also shows where the reader is, now without
 * the clauses the press removed. That confirmation is visual only: focus lands on a node named
 * "Where you came from" before and after, with no description or live region (Chromium's
 * accessibility tree). The price is one Tab stop, in all three engines: the next Tab goes to
 * `← Plan`, the strip's first control, and Shift+Tab to whatever precedes the strip, where each
 * engine had gone on from {@code <body>} as though `clear` were still there.
 *
 * <p>A clause can survive the press. `clear` resets the floor to the map's own default (3★), so a
 * door that carried 3★+ still matches: the same button stays, and so does the reader. Nothing moves
 * them, because only a node that leaves makes a record. And a passive effect later in the commit
 * that removes `clear` can still move the reader on, which is that effect's own call: the
 * drilldown's window panel takes focus as it mounts, which it does when `clear` scopes the open
 * region's row away.
 *
 * <p>⚠️ No gate on how the press arrived. Chromium and Firefox focus a button a mouse presses, so a
 * pointer press hands the strip focus too. No ring shows at the press; a later key (Escape among
 * them) draws it in Chromium and WebKit, not Firefox, and it stays until focus moves on, much as
 * Leaflet's own map container does after a click. The tells for "that was a mouse"
 * ({@code detail} and {@code pointerType}, and likely {@code :focus-visible}) read the same for a
 * screen-reader or voice-control press — `MastheadTickLine`'s class comment has the evidence — so a
 * gate on any of them would put exactly those readers back on {@code <body>}. WebKit does not focus
 * a button a mouse presses: its press focuses the nearest focusable ancestor instead, which is now
 * the strip, so that reader lands here with no handoff at all. So does a click on the strip's own
 * text, in every engine, where it used to send focus to {@code <body>}. After a pointer lands on
 * the strip, Tab restarts at `← Plan` in Chromium and Firefox, where a click on the text used to
 * let Tab carry on from the click.
 *
 * <p>⚠️ No guard for a foreign modal over the pane, or for a hidden pane, unlike
 * `hooks/useRowFocusRescue.js` — a decision, not an oversight, and a test pins it. That hook
 * records a row from focus events and keeps the record after focus has gone, so it can act while
 * the reader's attention is inside the four-day sheet. This record exists only for a `clear` that
 * held focus at the moment it left, so the reader was on this strip, not in a sheet: a reader who
 * Tabbed out of an open sheet onto `clear` and pressed it stays here, and the sheet's own restore,
 * when it later closes, finds them somewhere real and leaves them there. A hidden pane holds no
 * focus in a browser (measured: a focused `clear` loses it when its tab panel is hidden), and the
 * pane is hidden only by a tab switch, every route to which starts from a press on some control
 * other than `clear`.
 *
 * <p>The watch is on `clear` alone because the carrying span holds no other control. A control
 * added beside it needs watching too, or its own departure strands the reader where `clear`'s used
 * to.
 *
 * <h2>Accessible-name traps this file has to avoid</h2>
 *
 * <p>`← Plan`'s arrow is `aria-hidden` (the increment's own instruction, plan task 3) — the
 * accessible name is "Plan" alone, the plain trailing text node. Every sibling run that has to read
 * as ONE space-separated sentence uses a literal `{' '}` text node between elements, and the `/`
 * and `·` separators are bare text — both so the boundary is stated in the DOM, where jsdom (which
 * computes no layout, so every span reads as inline to it) can see it. JSX drops whitespace-only
 * text that contains a line break, so between tags on separate lines a space has to be written
 * explicitly.
 *
 * <p>⚠️ <b>Most of these are defensive in a browser, and one is not.</b> The separators between the
 * breadcrumb's own pieces sit between flex items (`.wf-map-breadcrumb` and
 * `.wf-map-breadcrumb-carrying` are `display: flex`), which every engine spaces itself: removing
 * them changed no accessible name (measured 2026-09-11 by Playwright's accessible-name algorithm
 * over Chromium's, WebKit's and Firefox's layout; Chromium's native accessibility tree agreed on
 * 2026-09-14). The one inside
 * `.wf-map-breadcrumb-window` — between `<b>{dayLabel}</b>` and the kind word — is
 * <b>load-bearing</b>: those are plain inline content, so without it the text read aloud is
 * "Tonightsunset". It changes no accessible NAME only because the `<nav>` takes its name from
 * `aria-label`. (The audit missed it: its capture could not isolate a separator followed directly
 * by text, which merges into that text when a DOM is re-parsed. The rule predicts it.) This note
 * also used to say a pseudo-element could not do the job and that accname
 * trims each element's whitespace — neither holds in a browser: generated content IS in the name,
 * and only jsdom's polyfill trims. `WindowFirstComingUpHandoff`'s class doc has the rule.
 *
 * @param {object} props
 * @param {{region: ?string, minRating: ?number, limitMinutes: ?number}} props.carried the door's
 *        payload, or rather the three fields of it this component ever prints
 * @param {?{dayLabel: string, eventType: string}} props.activeRow the EV row `MapView` currently
 *        shows as "now showing" (`activeMapEvent`) — null when the map has no row to name yet, in
 *        which case the window clause is omitted rather than printed empty
 * @param {?{baseName: string}} props.origin the origin in force, or null at home
 * @param {number} props.minStars the map's CURRENT floor — compared against `carried.minRating`,
 *        never read as the carried value itself
 * @param {number} props.driveTimeFilter the map's CURRENT reach tier in minutes (0 = Any) —
 *        compared against `carried.limitMinutes`
 * @param {?string} props.regionInForce the name of the region the map's own jump-fit override is
 *        CURRENTLY framing (`MapView`'s `jumpFitOverride.regionName`), or null when no jump stands
 * @param {Function} props.onBack `← Plan` — `App.jsx`'s `returnToPlan`
 * @param {Function} props.onClearRating resets the floor to the map's own default
 * @param {Function} props.onClearReach resets the reach tier to Any
 * @param {Function} props.onClearScope resets scope to My area (`MapView.resetToMyArea`)
 * @param {Function} props.onClearOrigin resets the shared origin to home
 */
export default function MapBreadcrumb({
  carried, activeRow = null, origin = null, minStars, driveTimeFilter, regionInForce = null,
  onBack, onClearRating, onClearReach, onClearScope, onClearOrigin,
}) {
  // The strip, where `clear` hands the reader's focus when it takes itself away (class comment).
  const stripRef = useRef(null);
  // `clear`, if it left while holding focus: recorded by its ref cleanup, spent by the effect
  // below.
  const departed = useRef(null);
  const trackClear = useCallback(
    (node) => (node ? watchDeparture(departed, node) : undefined),
    [],
  );
  // Every commit. The record is spent first, whether or not this commit acts on it, and it acts
  // only while focus is still nowhere: nothing in that commit should have placed it, but if
  // something did, it stays (`watchDeparture`'s rules). ⚠️ A LAYOUT effect, so the landing belongs
  // to the commit that removed `clear`: every layout effect after it in that commit, a later
  // sibling's or an ancestor's, reads the strip rather than `<body>`, and so does the passive
  // restore of a dialog closing in that commit, which then leaves the reader here, not on its
  // opener.
  useLayoutEffect(() => {
    if (!departed.current) return;
    departed.current = null;
    const focused = document.activeElement;
    const nowhere = !focused || focused === document.body || focused === document.documentElement;
    if (nowhere) stripRef.current?.focus();
  });

  const clauses = [];
  if (origin) clauses.push(`drive times from ${origin.baseName}`);
  if (carried.minRating != null && minStars === carried.minRating) {
    clauses.push(`${carried.minRating}★+`);
  }
  if (carried.limitMinutes != null && driveTimeFilter === carried.limitMinutes) {
    const tier = DRIVE_TIME_TIERS.find(([minutes]) => minutes === carried.limitMinutes);
    if (tier) clauses.push(`within ${tier[1]}`);
  }
  if (carried.region && regionInForce === carried.region) {
    clauses.push(carried.region);
  }

  function handleClear() {
    onClearRating();
    onClearReach();
    onClearScope();
    onClearOrigin();
  }

  return (
    <nav
      ref={stripRef}
      // Focusable, never a Tab stop: the landing for a focused `clear` that takes itself away.
      tabIndex={-1}
      aria-label="Where you came from"
      className="wf-map-breadcrumb"
      data-testid="wf-map-breadcrumb"
    >
      <button
        type="button"
        className="wf-map-breadcrumb-back"
        onClick={onBack}
        data-testid="wf-map-breadcrumb-back"
      >
        <span aria-hidden="true">{'← '}</span>
        Plan
      </button>
      {activeRow && (
        <>
          {' '}
          <span className="wf-map-breadcrumb-sep">/</span>
          {' '}
          <span className="wf-map-breadcrumb-window" data-testid="wf-map-breadcrumb-window">
            <b>{activeRow.dayLabel}</b>
            {' '}
            {KIND_WORD[activeRow.eventType] ?? activeRow.eventType?.toLowerCase()}
          </span>
        </>
      )}
      {clauses.length > 0 && (
        <>
          {' '}
          <span className="wf-map-breadcrumb-carrying" data-testid="wf-map-breadcrumb-carrying">
            {'carrying '}
            {clauses.join(' · ')}
            {' '}
            <button
              type="button"
              ref={trackClear}
              className="wf-map-breadcrumb-clear"
              onClick={handleClear}
              data-testid="wf-map-breadcrumb-clear"
            >
              clear
            </button>
          </span>
        </>
      )}
    </nav>
  );
}

MapBreadcrumb.propTypes = {
  carried: PropTypes.shape({
    region: PropTypes.string,
    minRating: PropTypes.number,
    limitMinutes: PropTypes.number,
  }).isRequired,
  activeRow: PropTypes.shape({
    dayLabel: PropTypes.string,
    eventType: PropTypes.string,
  }),
  origin: PropTypes.shape({
    baseName: PropTypes.string,
  }),
  minStars: PropTypes.number.isRequired,
  driveTimeFilter: PropTypes.number.isRequired,
  regionInForce: PropTypes.string,
  onBack: PropTypes.func.isRequired,
  onClearRating: PropTypes.func.isRequired,
  onClearReach: PropTypes.func.isRequired,
  onClearScope: PropTypes.func.isRequired,
  onClearOrigin: PropTypes.func.isRequired,
};
