// The Plan->Map door's producer side (overlay convergence, `docs/engineering/overlay-convergence-plan.md`
// section 3 C1): turns whatever a Plan-tab producer hands `onShowOnMap` into ONE normalised trigger,
// and a normalised trigger into the structured `source:'plan'` door `App.openMapTabFromPlan` sends -
// carrying every field the overlay's seven triggers carried. Pure; no React.
import { buildMapOverlay } from './mapOverlay.js';

/**
 * Turns whatever `onShowOnMap` was called with into a normalised trigger object for
 * {@link buildMapOverlay}. Extracted from `App.jsx`'s `handleShowOnMap` (found untested by
 * adversarial review, P3b) so the branch ORDER — the part that actually matters — is a unit,
 * not inline glue nobody can reach with a fixture.
 *
 * <h2>Order is the whole point</h2>
 *
 * <p>Every `else if` here is checked against the shape of `dateOrHandoff`, and it used to matter
 * that the `kind:'coming-up'` check (D8's map channel) ran BEFORE the generic `filterAction`-bearing
 * check that used to become `kind:'topic'` — both objects carried a `filterAction`, and getting the
 * order wrong would have silently turned a coastal/dark-sky action into a `kind:'topic'` trigger
 * instead. P6 (`docs/engineering/coming-up-plan.md` D7) deleted `kind:'topic'` and its only caller,
 * `HotTopicStrip`, along with it, so that hazard is gone rather than merely ordered around — the
 * history is kept here because the next new trigger kind should reason about ordering the same way.
 *
 * @param {*}        dateOrHandoff either a plain date string, or a handoff object naming its own
 *                                 `kind`/`filterAction`/`region`
 * @param {?string}  eventType    SUNRISE/SUNSET, for the plain-date-string call shapes
 * @param {?string}  locationName a specific location, for the location-drilldown call shape
 * @returns {object} a trigger for {@link buildMapOverlay}
 */
export function normalizeMapTrigger(dateOrHandoff, eventType, locationName = null) {
  // First, because it is the one caller that names its own kind. Without this branch the object
  // falls past `filterAction` and `region` into the final `else` and becomes an `event` trigger
  // whose `date` is the whole object — an overlay for a night that does not exist.
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'aurora') {
    return { kind: 'aurora', date: dateOrHandoff.date };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.kind === 'coming-up') {
    // The Coming up chronology's own map channel (D8) — named explicitly. It used to be checked
    // ahead of a generic `filterAction` branch that became `kind:'topic'`, since both shapes carried
    // a `filterAction`; P6 deleted that branch and its only caller (`HotTopicStrip`), so `coming-up`
    // is now the sole producer of a bare `filterAction` handoff.
    return {
      kind: 'coming-up',
      filterAction: dateOrHandoff.filterAction ?? null,
      darkSky: !!dateOrHandoff.darkSky,
      label: dateOrHandoff.label ?? null,
      date: dateOrHandoff.date,
    };
  }
  if (dateOrHandoff && typeof dateOrHandoff === 'object' && dateOrHandoff.region) {
    // A region trigger, optionally carrying a hot topic's qualifying locations + label so the
    // overlay opens to just those pins (elevated inversion spots, coastal tide spots, …).
    return {
      kind: 'region',
      region: dateOrHandoff.region,
      date: dateOrHandoff.date,
      eventType: dateOrHandoff.eventType,
      locationNames: dateOrHandoff.locationNames ?? null,
      label: dateOrHandoff.label ?? null,
      filterAction: dateOrHandoff.filterAction ?? null,
    };
  }
  if (locationName) {
    return { kind: 'location', locationName, date: dateOrHandoff, eventType };
  }
  return { kind: 'event', date: dateOrHandoff, eventType };
}

/**
 * The breadcrumb's headline for a trigger - the overlay header's own title and sub-line, which the
 * Map tab's `MapBreadcrumb` prints as `title · caption` ("Aurora tonight", "Tomorrow sunrise ·
 * 06:12"). Derived through {@link buildMapOverlay} so the two surfaces can never name a door
 * differently. The overlay's own `caption` (a "tap a pin" prompt) is deliberately NOT used: it is
 * an instruction to a modal's reader, and the breadcrumb names its lens by clauses instead.
 *
 * @param {object} trigger a normalised trigger ({@link normalizeMapTrigger})
 * @param {object} ctx     `buildMapOverlay`'s context ({locations, briefingScores, todayStr, tomorrowStr})
 * @returns {{title: ?string, caption: ?string}}
 */
export function doorHeadline(trigger, ctx) {
  const overlay = buildMapOverlay(trigger, ctx);
  return { title: overlay.title || null, caption: overlay.subLine || null };
}

/**
 * A normalised trigger as the structured door `App.openMapTabFromPlan` sends. The location,
 * region, event and filter resolution is `buildMapOverlay`'s own (the top-rated spot of a single
 * region, the exact spot set of a coming-up card), read off its `handoff`/`focus` rather than
 * re-decided here. A night trigger names its night (`night`), a spot-set trigger its names
 * (`focusNames`, a TEMPORARY named lens on the tab) and its points (`focusPoints`, to fit).
 * Fields that do not apply are ABSENT, not null, so a plain door's payload is unchanged. The
 * wrapper-supplied lens fields (`minRating`, `limitMinutes`) and `inPlace` ride through untouched.
 *
 * @param {object} trigger a normalised trigger ({@link normalizeMapTrigger})
 * @param {object} ctx     `buildMapOverlay`'s context
 * @returns {object} a door for `openMapTabFromPlan`
 */
export function doorFromTrigger(trigger, ctx) {
  const overlay = buildMapOverlay(trigger, ctx);
  const h = overlay.handoff || {};
  const { title, caption } = doorHeadline(trigger, ctx);
  const door = {
    date: trigger.date,
    targetType: h.eventType ?? null,
    // A REGION door keeps its region even when the overlay's resolution found a top spot to
    // open (`handoff` then carries only `locationName`): the Map tab frames the region AND selects
    // the spot, so an away region is not stranded outside scope behind one callout (C4).
    region: h.region ?? (trigger.kind === 'region' ? trigger.region ?? null : null),
    locationName: h.locationName ?? null,
    title,
    caption,
  };
  if (trigger.kind === 'aurora') {
    door.night = { kind: 'aurora', date: trigger.date };
  }
  if (h.filterAction) door.filterAction = h.filterAction;
  if (h.darkSky) door.darkSky = true;
  if (overlay.focus?.names?.length) door.focusNames = overlay.focus.names;
  if (overlay.focus?.points?.length) door.focusPoints = overlay.focus.points;
  // The wrapper-supplied lens fields ride through untouched (`WindowFirstShell.openMapTab` ->
  // `openMapDoor` spreads the Plan's rating floor and reach tier, and `inPlace`, onto whatever it is
  // handed, a trigger included): this function resolves the overlay's own fields and adds nothing of
  // its own to the lens. Copied when the trigger carries the key at all, otherwise ABSENT.
  // `from` (C4): the tab the door was pressed on, for the breadcrumb's return.
  ['minRating', 'limitMinutes', 'inPlace', 'from'].forEach((key) => {
    if (trigger[key] !== undefined) door[key] = trigger[key];
  });
  return door;
}
