import {
  useCallback, useEffect, useMemo, useReducer, useRef, useState,
} from 'react';
import { getSettings, saveMapTideMode } from '../api/settingsApi.js';
import { setMode, getMode, resolveMode } from '../utils/scoreRamp.js';
import { createColourSaveQueue, keepColourSaveLineOpen, saveColourInTurn } from '../utils/colourSaveQueue.js';

/** Nothing on record yet: the home is not known, and neither counter has moved. */
export const INITIAL_RECORD = { settings: undefined, homeSettingsVersion: 0, driveTimesVersion: 0 };

/** The fields of a settings response the page keeps about the home. */
function homeFieldsOf(settings) {
  return {
    homePostcode: settings?.homePostcode ?? null,
    homeLatitude: settings?.homeLatitude ?? null,
    homeLongitude: settings?.homeLongitude ?? null,
    homePlaceName: settings?.homePlaceName ?? null,
    driveTimesCalculatedAt: settings?.driveTimesCalculatedAt ?? null,
  };
}

/** The server's own "has the home moved" test (`UserSettingsService.originMoved`): exact equality. */
function sameHome(a, b) {
  return a.homePostcode === b.homePostcode
    && a.homeLatitude === b.homeLatitude
    && a.homeLongitude === b.homeLongitude;
}

/**
 * Whether two drive-time stamps name the same instant. Compared as instants, not as strings: the
 * server hands a recalculation's stamp back from its clock — nanoseconds on Linux — and stores it
 * to the microsecond, so every later answer spells the same instant differently. The millisecond
 * of slack covers the database's rounding into the next millisecond; two real recalculations are
 * minutes apart at the least.
 */
function sameStamp(a, b) {
  if (a === b) return true;
  if (a == null || b == null) return false;
  const at = Date.parse(a);
  const bt = Date.parse(b);
  if (Number.isNaN(at) || Number.isNaN(bt)) return false;
  return Math.abs(at - bt) <= 1;
}

/**
 * Applies an answer to the record, moving a counter only where it differs from what is on record:
 * the page's own mount read (`read`), a settings answer from the dialog (`answer`), or a
 * recalculation's new stamp (`recalculated`). Exported for its own tests: the rules are the whole
 * of what makes a counter move.
 */
export function recordReducer(record, action) {
  const prev = record.settings;
  if (action.type === 'recalculated') {
    // The server measures from the home it has stored, which is the one on record once any save
    // has landed — never the settings dialog's own copy, which a save landing under the spinner
    // has overtaken. So only the stamp is taken, and only the drive-time counter moves.
    if (prev !== undefined && sameStamp(prev.driveTimesCalculatedAt, action.calculatedAt)) {
      return record;
    }
    return {
      ...record,
      settings: prev === undefined ? prev : { ...prev, driveTimesCalculatedAt: action.calculatedAt },
      driveTimesVersion: record.driveTimesVersion + 1,
    };
  }
  const next = homeFieldsOf(action.settings);
  if (action.type === 'read') return { ...record, settings: next };
  // An answer while nothing is on record — the mount read unanswered or failed — counts as a
  // change, so the reads keyed on the counters are asked again rather than left as they were.
  const moved = prev === undefined || !sameHome(prev, next);
  // A save does not geocode, so its response carries no place name; for the same home, the one
  // on record still names it.
  if (!moved && next.homePlaceName == null) next.homePlaceName = prev.homePlaceName;
  const drivesChanged = prev === undefined
    || !sameStamp(prev.driveTimesCalculatedAt, next.driveTimesCalculatedAt);
  if (!moved && !drivesChanged && prev.homePlaceName === next.homePlaceName) return record;
  return {
    settings: next,
    homeSettingsVersion: record.homeSettingsVersion + (moved ? 1 : 0),
    driveTimesVersion: record.driveTimesVersion + (drivesChanged ? 1 : 0),
  };
}

/**
 * The reader's own settings as this page knows them — the home, the Coming up last-seen date and
 * the map-colour preference — and the two counters the reads derived from the home key on.
 *
 * <p><b>One read, then the settings dialog's answers.</b> `GET /api/user/settings` is asked once,
 * when the page mounts. After that the page learns of a change only from answers the settings
 * dialog already holds, never from a read of its own: the dialog's read on opening
 * ({@code startSettingsRead}), a saved home's response ({@code homeSaved}), a recalculation's new
 * stamp ({@code driveTimesRecalculated}) and a saved colour's response ({@code colourSaved}). The
 * page used to read the settings twice — once in the Plan provider for the tick line's home and
 * the Coming up latch, once in `App` for the map's home and the colour — each on mount and on every
 * close of the dialog, and either could fail or land out of order on its own:
 * <ul>
 *   <li>The tick line's home, the map's HOME marker, reach rings and ⌂ control, and the Plan tab's
 *       home dot now read this one record, where the two reads could split them — one on the new
 *       home and one on the old, or one of them on none.</li>
 *   <li>A saved home is known the moment its save lands, because the answer is the save's own
 *       response. A read after it that failed used to blank the tick line's home.</li>
 *   <li>A saved colour reaches the ramp from its own response, when the save lands. It used to wait
 *       for the dialog to close, and a read there that failed left the old ramp until the next.</li>
 *   <li>Opening the dialog reconciles the page with the server, so a home or its drive times changed
 *       elsewhere — on another device, by the nightly drive-time job, or by a save whose response was
 *       lost — are picked up there. Closing the dialog used to do that by re-reading everything,
 *       whether or not anything had changed.</li>
 * </ul>
 *
 * <p><b>The newest ASKED answer wins, whichever lands last.</b> Each read is numbered when it is
 * made — the page's own, and each opening of the dialog — and a save is numbered when it lands,
 * since its answer is the server's state from then. An answer numbered below the newest one applied
 * is dropped. That keeps the mount read from overwriting anything the dialog has said (the dialog
 * cannot open before the page has mounted), a dialog closed before its read answered — the server
 * geocodes on every GET, so a read can be slow — from putting back what a later opening or save
 * has said, and a dialog reopened while a save was out from undoing the save with a read the server
 * answered first. It also settles a React StrictMode remount, whose two mount reads are numbered in
 * turn. This is the order rule a poll uses, and for the same reason: every read asks the same
 * question — what the settings are now — so the newest asked is the best answer there is.
 *
 * <p><b>The counters move only on a real change</b>, which is what makes it right for the reads
 * keyed on them to drop a request a newer move supersedes. {@code homeSettingsVersion} moves when
 * an answer's home differs from the one on record — postcode or coordinates, by the exact test the
 * server's `originMoved` makes — and {@code driveTimesVersion} when its drive-time stamp names a
 * different instant. The Plan provider's reach fetch keys on both, the masthead's light on the
 * first. So re-saving the same postcode moves neither, and a recalculation moves only the second:
 * the light, which it cannot change, is not asked again. An answer that arrives while nothing is on
 * record moves both, so opening the dialog after a failed page-load read retries the reads keyed
 * on them.
 *
 * <p><b>Three states for the home.</b> {@code undefined} is "not known" — the mount read
 * unanswered or failed, with no answer from the dialog since; {@code null} is the server saying no
 * postcode is saved; otherwise the home. {@code homePlace} and {@code homeCoords} keep them apart,
 * and the map's ⌂ control depends on it: it tells a reader with {@code null} to set a postcode,
 * which a failed read is no evidence for, and makes no claim at all while the home is unknown.
 *
 * <p><b>The last-seen date</b> comes from the mount read and from the Coming up tab's own writes
 * ({@code setComingUpLastSeenDate}, handed to the Plan provider). The dialog's read fills it only
 * while it is unknown and never overwrites it, so an answer read before a `Mark seen` landed cannot
 * put an older date back.
 *
 * <p><b>The Map tab's tide mode</b> (map-mobile-sheet-plan.md M4 — Auto/Always/Off, no UI yet)
 * copies the colour preference's shape exactly, but owns its OWN line of saves
 * ({@code colourSaveQueue.js}'s mechanics, reused unchanged — every export there already takes the
 * save function as a parameter, so nothing needed generalising) rather than one handed in from a
 * dialog: this phase has no settings control for it, so this hook is the line's only owner, held
 * open for as long as it is mounted — which is the same "one instance, owned by `App`" rule
 * `mapColourScale` follows, threaded to the map as a prop on the same route. {@code saveTideMode}
 * sets the mode at once (optimistic), then reverts to the last mode the SERVER is known to hold —
 * never a queued choice that was itself never sent, and never the pre-session value — if that
 * choice's own turn ends in failure. A choice already sent cannot be recalled, so a later failure
 * can only ever mean the LATEST choice failed; the line still guarantees the newest queued choice
 * is the one sent.
 *
 * @returns {{homePlace: (?string|undefined), homeCoords: (?{lat: number, lon: number}|undefined),
 *           comingUpLastSeenDate: (?string|undefined), setComingUpLastSeenDate: function,
 *           homeSettingsVersion: number, driveTimesVersion: number, mapColourScale: string,
 *           colourScaleDefaulted: boolean, startSettingsRead: function, homeSaved: function,
 *           driveTimesRecalculated: function, colourSaved: function,
 *           mapTideMode: ('auto'|'always'|'off'), saveTideMode: function}}
 */
export default function useReaderSettings() {
  const [record, dispatch] = useReducer(recordReducer, INITIAL_RECORD);
  const [comingUpLastSeenDate, setComingUpLastSeenDate] = useState(undefined);
  // The active scoreRamp mode, mirrored into state and handed to the Map pane as a genuine prop.
  // `MapView` is `React.memo`'d and its pane is never unmounted, so a mode switch needs a real prop
  // change to reach an already-alive instance — `setMode` alone only updates module state nothing
  // here is subscribed to. Read back via `getMode()` rather than duplicating its resolution rule.
  const [mapColourScale, setMapColourScale] = useState(getMode);
  // Whether the loaded `mapColourScale` was raw-null — never explicitly chosen, so this reader's
  // map just changed colour under them rather than reflecting a preference they picked themselves.
  // The one thing the Map tab's one-time notice needs and `mapColourScale` above cannot answer:
  // that mirrors the RESOLVED mode, and null resolves to the same `'temp'` an explicit choice does.
  const [colourScaleDefaulted, setColourScaleDefaulted] = useState(false);
  // The Map tab's persisted tide mode (map-mobile-sheet-plan.md M4). 'auto' is both the default
  // before the mount read answers and what a null (never-chosen) server value means.
  const [mapTideMode, setMapTideMode] = useState('auto');
  // The last mode the SERVER is known to hold — the rollback baseline `saveTideMode` reverts to on
  // a failed save. Never the pre-session value and never a choice that was itself queued but never
  // sent: only a landed save (the mount read, or a save's own success) moves it.
  const tideModeBaseline = useRef('auto');
  // The page's one line of tide-mode saves — see the class doc's paragraph on this. Reuses
  // `colourSaveQueue.js` wholesale rather than a sibling module: every export there already takes
  // the save function as a parameter, so nothing about it is colour-specific.
  const [tideSaveQueue] = useState(createColourSaveQueue);
  useEffect(() => keepColourSaveLineOpen(tideSaveQueue), [tideSaveQueue]);
  // The answers' order (see above): how many have been numbered, and the newest number applied.
  const order = useRef({ numbered: 0, applied: 0 });

  /** Numbers an answer as the newest so far. */
  const number = useCallback(() => {
    order.current.numbered += 1;
    return order.current.numbered;
  }, []);

  /** Applies an answer numbered {@code n}, unless a newer one has been applied: whether it may. */
  const claim = useCallback((n) => {
    if (n < order.current.applied) return false;
    order.current.applied = n;
    return true;
  }, []);

  /**
   * The one place a settings answer reaches the ramp, so Plan and Map can never disagree about
   * what a colour means (heat-scale-unification-plan.md, rule 1). `resolveMode` — not a raw pass to
   * `setMode` — is what makes a never-chosen `null` resolve to `DEFAULT_MODE` rather than to
   * `setMode`'s own `'verdict'` fallback.
   */
  const applyColour = useCallback((scale) => {
    setMode(resolveMode(scale));
    setMapColourScale(getMode());
    setColourScaleDefaulted(scale == null);
  }, []);

  useEffect(() => {
    const n = number();
    getSettings()
      .then((settings) => {
        if (!claim(n)) return;
        dispatch({ type: 'read', settings });
        setComingUpLastSeenDate(settings?.comingUpLastSeenDate ?? null);
        applyColour(settings?.mapColourScale);
        const tideMode = settings?.mapTideMode ?? 'auto';
        tideModeBaseline.current = tideMode;
        setMapTideMode(tideMode);
      })
      // Nothing is written: the home and the date stay unknown until the dialog answers, and the
      // ramp keeps the default it started with.
      .catch(() => {});
  }, [number, claim, applyColour]);

  /**
   * The settings dialog starts its read on opening: numbered now, and applied — the home, the date
   * if unknown, and the colour — through the reporter this returns, unless a newer answer has been
   * applied by the time it lands.
   */
  const startSettingsRead = useCallback(() => {
    const n = number();
    return (settings) => {
      if (!claim(n)) return;
      dispatch({ type: 'answer', settings });
      setComingUpLastSeenDate((prev) => (
        prev === undefined ? (settings?.comingUpLastSeenDate ?? null) : prev));
      applyColour(settings?.mapColourScale);
    };
  }, [number, claim, applyColour]);

  /** A saved home's response, named from the dialog's lookup. */
  const homeSaved = useCallback((settings) => {
    claim(number());
    dispatch({ type: 'answer', settings });
  }, [number, claim]);

  /**
   * A recalculation's new stamp, for the home on record. Numbered as it lands, like a save. No read
   * can be out to be ordered against it today — the dialog cannot be closed under a recalculation's
   * spinner, so no later opening is reading while it runs — but the order should not rest on that.
   */
  const driveTimesRecalculated = useCallback((calculatedAt) => {
    claim(number());
    dispatch({ type: 'recalculated', calculatedAt });
  }, [number, claim]);

  /** A saved colour, from its own response. */
  const colourSaved = useCallback((scale) => {
    claim(number());
    applyColour(scale);
  }, [number, claim, applyColour]);

  /**
   * Saves a chosen Map tab tide mode (map-mobile-sheet-plan.md M4 task 4), through this hook's own
   * line of tide-mode saves — one save in flight at a time, a newer choice queued behind an
   * older, not-yet-started one superseding it (`colourSaveQueue.js`'s rules, reused unchanged).
   *
   * <p>Sets the mode at once — the newest choice is always what the reader sees while its save is
   * out — and is <b>numbered when it lands</b>, like every other save here (the class doc's rule),
   * so a mount read that lands after the save cannot put an older mode back, while a save pressed
   * BEFORE the mount read lands does not outrank it. ⚠️ The first cut claimed the order at press
   * time, the one participant that did: a press during a slow {@code getSettings()} made that
   * whole read fail its own {@code claim} — home, colour and last-seen date dropped with the tide
   * mode — found by M4's review. Reverts to
   * {@code tideModeBaseline.current} (the last mode the SERVER is known to hold) if this choice's
   * own turn ends in {@code 'failed'}; does nothing on {@code 'superseded'} or {@code 'ended'},
   * since a newer choice's own call already owns what is shown.
   *
   * @param {'auto'|'always'|'off'} mode the reader's choice
   * @returns {Promise<'saved'|'failed'|'superseded'|'ended'>} settles when this choice's turn ends
   */
  const saveTideMode = useCallback(async (mode) => {
    setMapTideMode(mode);
    const outcome = await saveColourInTurn(tideSaveQueue, mode, {
      save: saveMapTideMode,
      onSaved: (updated, savedMode) => {
        const landed = updated?.mapTideMode ?? savedMode;
        tideModeBaseline.current = landed;
        // Numbered as it lands (see the doc above): the newest answer now, so it outranks any read
        // that landed between the press and this — and is shown again in case one did.
        claim(number());
        setMapTideMode(landed);
      },
    });
    if (outcome === 'failed') {
      setMapTideMode(tideModeBaseline.current);
    }
    return outcome;
  }, [number, claim, tideSaveQueue]);

  const { settings } = record;
  const known = settings !== undefined;
  const lat = settings?.homeLatitude;
  const lon = settings?.homeLongitude;
  // Memoised on the coordinates, so an answer that leaves them alone keeps the object — the map
  // is `React.memo`'d and its label and pin layers repaint on this object's identity.
  const homeCoords = useMemo(() => {
    if (!known) return undefined;
    return lat != null && lon != null ? { lat, lon } : null;
  }, [known, lat, lon]);
  const homePlace = known ? (settings.homePlaceName || settings.homePostcode || null) : undefined;

  return {
    homePlace,
    homeCoords,
    comingUpLastSeenDate,
    setComingUpLastSeenDate,
    homeSettingsVersion: record.homeSettingsVersion,
    driveTimesVersion: record.driveTimesVersion,
    mapColourScale,
    colourScaleDefaulted,
    startSettingsRead,
    homeSaved,
    driveTimesRecalculated,
    colourSaved,
    mapTideMode,
    saveTideMode,
  };
}
