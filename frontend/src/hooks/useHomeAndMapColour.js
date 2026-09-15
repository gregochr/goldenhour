import { useEffect, useState } from 'react';
import { getSettings } from '../api/settingsApi.js';
import { setMode, getMode, resolveMode } from '../utils/scoreRamp.js';

/**
 * `App`'s own read of the caller's settings: the home's coordinates, for the map's HOME marker,
 * reach rings and ⌂ control, and the map-colour preference, for the ramp every heat surface paints
 * with.
 *
 * <p><b>Asked again only when one of the two may have changed</b> — on a home save
 * ({@code homeSettingsVersion}: a new postcode, or a drive-time recalculation) or a colour save
 * ({@code mapColourVersion}) — and never on a close of the settings dialog alone. It carries the
 * same effect cleanup as the Plan provider's reach and settings fetches, which key on the first of
 * those counters: a request a newer save has superseded writes nothing, wherever it lands. It used to
 * be asked on mount and again on every close, unguarded, so its answer could land after the
 * provider's newer ones and leave the marker and rings on the old home beside the tick line's new
 * one. Dropping a superseded answer is only right while every move of a counter is a real change,
 * which is why neither moves on a close. What that costs: a read that failed at page load is retried
 * by the next save or a reload, no longer by closing the dialog.
 *
 * <p>A failure writes nothing — the settings are optional here; the block they feed just stays
 * hidden — so the {@code .catch} has nothing for the flag to guard. One that ever learns to write
 * needs the same check the provider's settings catch carries.
 *
 * @param {number} [homeSettingsVersion] bumped by {@code App} on each home save
 * @param {number} [mapColourVersion] bumped by {@code App} on each map-colour save
 * @returns {{homeCoords: ?{lat: number, lon: number}, mapColourScale: string,
 *           colourScaleDefaulted: boolean}}
 */
export default function useHomeAndMapColour(homeSettingsVersion = 0, mapColourVersion = 0) {
  // Null until the read answers, and null when no postcode is saved: the consumers treat both as
  // "no home to draw".
  const [homeCoords, setHomeCoords] = useState(null);
  // The active scoreRamp mode, mirrored into state and handed to the Map pane as a genuine prop.
  // `MapView` is `React.memo`'d and its pane is never unmounted, so a mode switch made in Settings
  // needs a real prop change to reach an already-alive instance — `setMode` alone only updates
  // module state nothing here is subscribed to. Read back via `getMode()` rather than duplicating
  // its 'temp'-or-'verdict' resolution rule.
  const [mapColourScale, setMapColourScale] = useState(getMode);
  // Whether the loaded `mapColourScale` was raw-null — never explicitly chosen, so this reader's
  // map just changed colour under them rather than reflecting a preference they picked themselves.
  // The one thing the Map tab's one-time notice needs and `mapColourScale` above cannot answer:
  // that mirrors the RESOLVED mode, and null resolves to the same `'temp'` an explicit choice does.
  const [colourScaleDefaulted, setColourScaleDefaulted] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getSettings()
      .then((s) => {
        if (cancelled) return;
        setHomeCoords(
          s?.homeLatitude != null && s?.homeLongitude != null
            ? { lat: s.homeLatitude, lon: s.homeLongitude }
            : null,
        );
        // The one place the loaded preference reaches the ramp, so Plan and Map can never
        // disagree about what a colour means (heat-scale-unification-plan.md, rule 1).
        // `resolveMode` — not a raw pass to `setMode` — is what makes a never-chosen `null`
        // resolve to `DEFAULT_MODE` rather than to `setMode`'s own `'verdict'` fallback.
        setMode(resolveMode(s?.mapColourScale));
        setMapColourScale(getMode());
        setColourScaleDefaulted(s?.mapColourScale == null);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [homeSettingsVersion, mapColourVersion]);

  return { homeCoords, mapColourScale, colourScaleDefaulted };
}
