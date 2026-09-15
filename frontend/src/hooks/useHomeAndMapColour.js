import { useEffect, useState } from 'react';
import { getSettings } from '../api/settingsApi.js';
import { setMode, getMode, resolveMode } from '../utils/scoreRamp.js';

/** No answer yet, or none standing: the home is not known, for no home counter. */
const UNKNOWN_HOME = { coords: undefined, forVersion: undefined };

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
 * <p><b>{@code homeCoords} has three states, and the third is the point.</b> {@code undefined} is
 * "not answered yet, or no answer stands since the last home save"; {@code null} is the server
 * saying no postcode is saved; an object is the home. They draw the same — nothing — but the map's
 * ⌂ control tells a reader with {@code null} to go and set a postcode, and a dropped request is no
 * evidence they have not: {@code undefined} makes no such claim, the same rule the provider's
 * {@code homePlace} and {@code useTodaysLight} keep.
 *
 * <p>A failure after a home save therefore empties the home back to {@code undefined} rather than
 * leaving the answer from before it standing: after a move, that answer would draw the marker and
 * rings on the old house (an owner decision, taken 2026-09-15). A recalculation's failed read
 * empties it too — the counter cannot tell the two saves apart, and the tick line's own read does
 * the same. A COLOUR save's failed read does not: it asked about a home that has not changed since
 * the answer on screen, and the provider asks nothing on a colour save, so emptying the map's home
 * would leave the tick line naming one the map had dropped. Hence the home is kept with the home
 * counter it answers. The guard covers the {@code .catch} too, so a SUPERSEDED read's failure
 * cannot empty a home the newest read has already drawn. The colour preference is left where it is
 * on any failure — the ramp has to paint with something, and the last loaded choice is the
 * reader's own.
 *
 * @param {number} [homeSettingsVersion] bumped by {@code App} on each home save
 * @param {number} [mapColourVersion] bumped by {@code App} on each map-colour save
 * @returns {{homeCoords: (?{lat: number, lon: number}|undefined), mapColourScale: string,
 *           colourScaleDefaulted: boolean}}
 */
export default function useHomeAndMapColour(homeSettingsVersion = 0, mapColourVersion = 0) {
  // The home, and the home counter it answers. Unknown (`undefined`) until the read answers — not
  // `null`, which the ⌂ control reads as "no postcode saved" and answers with a prompt to set one.
  // See the three states above.
  const [home, setHome] = useState(UNKNOWN_HOME);
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
        setHome({
          coords: s?.homeLatitude != null && s?.homeLongitude != null
            ? { lat: s.homeLatitude, lon: s.homeLongitude }
            : null,
          forVersion: homeSettingsVersion,
        });
        // The one place the loaded preference reaches the ramp, so Plan and Map can never
        // disagree about what a colour means (heat-scale-unification-plan.md, rule 1).
        // `resolveMode` — not a raw pass to `setMode` — is what makes a never-chosen `null`
        // resolve to `DEFAULT_MODE` rather than to `setMode`'s own `'verdict'` fallback.
        setMode(resolveMode(s?.mapColourScale));
        setMapColourScale(getMode());
        setColourScaleDefaulted(s?.mapColourScale == null);
      })
      .catch(() => {
        if (cancelled) return;
        // Emptied only if the home has been saved since the answer on screen; a colour save's
        // failure leaves it (see above).
        setHome((prev) => (prev.forVersion === homeSettingsVersion ? prev : UNKNOWN_HOME));
      });
    return () => { cancelled = true; };
  }, [homeSettingsVersion, mapColourVersion]);

  return { homeCoords: home.coords, mapColourScale, colourScaleDefaulted };
}
