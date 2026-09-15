import {
  useCallback, useEffect, useMemo, useReducer, useRef, useState,
} from 'react';
import { getSettings } from '../api/settingsApi.js';
import { setMode, getMode, resolveMode } from '../utils/scoreRamp.js';

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
 * Applies the page's own mount read (`read`) or one of the settings dialog's answers (`answer`) to
 * the record, moving a counter only where the answer differs from what is on record. Exported for
 * its own tests: the rules are the whole of what makes a counter move.
 */
export function recordReducer(record, action) {
  const next = homeFieldsOf(action.settings);
  if (action.type === 'read') return { ...record, settings: next };
  const prev = record.settings;
  // An answer while nothing is on record — the mount read unanswered or failed — counts as a
  // change, so the reads keyed on the counters are asked again rather than left as they were.
  const moved = prev === undefined || !sameHome(prev, next);
  // A save does not geocode, so its response carries no place name; for the same home, the one
  // on record still names it.
  if (!moved && next.homePlaceName == null) next.homePlaceName = prev.homePlaceName;
  const drivesChanged = prev === undefined
    || prev.driveTimesCalculatedAt !== next.driveTimesCalculatedAt;
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
 * when the page mounts. After that every change reaches the page as an answer the settings dialog
 * already holds, never as a read of its own: the dialog's read on opening ({@code settingsRead}),
 * a saved home's response ({@code homeSaved}, which also carries a drive-time recalculation's new
 * stamp) and a saved colour ({@code colourSaved}). The page used to read the settings twice — once
 * for the tick line's home in the Plan provider, once here for the map's — and again after every
 * save, and each of those reads could fail or land out of order on its own:
 * <ul>
 *   <li>The tick line, the map's HOME marker, reach rings and ⌂ control, and the Plan tab's home
 *       dot now read this one record, so they cannot name two homes, or one home and none.</li>
 *   <li>A saved home is known the moment its save lands, because the answer is the save's own
 *       response. A follow-up read that failed used to empty it — and unmount the ⌂ that had
 *       opened the dialog, so closing the dialog dropped keyboard focus to the page.</li>
 *   <li>A saved colour reaches the ramp from its own response. A follow-up read that failed left
 *       every surface on the old ramp while the dialog showed the new one, with no way to retry.</li>
 *   <li>Opening the dialog reconciles the page with the server, so a home changed elsewhere — on
 *       another device, by the nightly drive-time job, or by a save whose response was lost — is
 *       picked up there. Closing the dialog used to do that by re-reading everything, whether or
 *       not anything had changed.</li>
 * </ul>
 *
 * <p><b>The mount read never overwrites an answer.</b> The dialog cannot open before the page has
 * mounted, so its answers are newer than the mount read, which is superseded the moment one
 * arrives, however late it lands — the same rule an effect cleanup applies to a request a newer one
 * supersedes. The read also carries a cleanup, for a React StrictMode remount.
 *
 * <p><b>The counters move only on a real change</b>, which is what makes it right for the reads
 * keyed on them to drop a request a newer move supersedes. {@code homeSettingsVersion} moves when
 * an answer's home differs from the one on record — postcode or coordinates, by the exact test the
 * server's `originMoved` makes — and {@code driveTimesVersion} when its drive-time stamp does. The
 * Plan provider's reach fetch keys on both, the masthead's light on the first. So re-saving the
 * same postcode moves neither, and a recalculation moves only the second: the light, which it
 * cannot change, is not asked again. An answer that arrives while nothing is on record moves both,
 * so opening the dialog after a failed page-load read retries the reads keyed on them.
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
 * @returns {{homePlace: (?string|undefined), homeCoords: (?{lat: number, lon: number}|undefined),
 *           comingUpLastSeenDate: (?string|undefined), setComingUpLastSeenDate: function,
 *           homeSettingsVersion: number, driveTimesVersion: number, mapColourScale: string,
 *           colourScaleDefaulted: boolean, settingsRead: function, homeSaved: function,
 *           colourSaved: function}}
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
  // Stops the mount read writing, once an answer from the dialog has arrived.
  const supersedeMountRead = useRef(null);

  /**
   * The one place a settings answer reaches the ramp, so Plan and Map can never disagree about
   * what a colour means (heat-scale-unification-plan.md, rule 1). `resolveMode` — not a raw pass to
   * `setMode` — is what makes a never-chosen `null` resolve to `DEFAULT_MODE` rather than to
   * `setMode`'s own `'verdict'` fallback. The ramp is set before the mirrored state, so the render
   * that state causes already paints with it.
   */
  const applyColour = useCallback((scale) => {
    setMode(resolveMode(scale));
    setMapColourScale(getMode());
    setColourScaleDefaulted(scale == null);
  }, []);

  useEffect(() => {
    let superseded = false;
    supersedeMountRead.current = () => { superseded = true; };
    getSettings()
      .then((settings) => {
        if (superseded) return;
        dispatch({ type: 'read', settings });
        setComingUpLastSeenDate(settings?.comingUpLastSeenDate ?? null);
        applyColour(settings?.mapColourScale);
      })
      // Nothing is written: the home and the date stay unknown until the dialog answers, and the
      // ramp keeps the default it started with.
      .catch(() => {});
    return () => { superseded = true; };
  }, [applyColour]);

  /** The settings dialog's own read on opening: the home, the date if unknown, and the colour. */
  const settingsRead = useCallback((settings) => {
    supersedeMountRead.current?.();
    dispatch({ type: 'answer', settings });
    setComingUpLastSeenDate((prev) => (
      prev === undefined ? (settings?.comingUpLastSeenDate ?? null) : prev));
    applyColour(settings?.mapColourScale);
  }, [applyColour]);

  /** A saved home's response, or the dialog's settings with a recalculation's new stamp. */
  const homeSaved = useCallback((settings) => {
    supersedeMountRead.current?.();
    dispatch({ type: 'answer', settings });
  }, []);

  /** A saved colour, from its own response. */
  const colourSaved = useCallback((scale) => {
    supersedeMountRead.current?.();
    applyColour(scale);
  }, [applyColour]);

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
    settingsRead,
    homeSaved,
    colourSaved,
  };
}
