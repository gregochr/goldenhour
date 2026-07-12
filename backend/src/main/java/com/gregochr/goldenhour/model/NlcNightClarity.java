package com.gregochr.goldenhour.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Cached result of the nightly noctilucent-cloud (NLC) clarity scan: which upcoming nights have
 * at least one dark-sky location with a clear enough sky to see NLC on the northern horizon.
 *
 * <p>Populated during the daily-briefing run from weather already fetched for the briefing, so
 * the {@code NlcHotTopicStrategy} can gate the seasonal reminder on real cloud cover without
 * making any external API call. An empty {@link #clearNights} list means no viable night was
 * found in the window — the topic is then suppressed rather than shown as calendar wallpaper.
 *
 * @param clearNights nights with a clear dark-sky viewing chance, ascending by date; never null
 */
public record NlcNightClarity(List<ClearNight> clearNights) {

    /** Defensive compact constructor. */
    public NlcNightClarity {
        clearNights = List.copyOf(clearNights);
    }

    /**
     * Whether any night in the window has a clear dark-sky viewing chance.
     *
     * @return true when at least one clear night was found
     */
    public boolean hasClearNight() {
        return !clearNights.isEmpty();
    }

    /**
     * A single night with a clear NLC viewing chance.
     *
     * @param date               the evening's date (the night runs from this evening into the
     *                           following morning)
     * @param clearLocationCount number of dark-sky locations forecast clear that night
     * @param totalDarkSkyCount  total dark-sky locations scanned that night (the denominator for
     *                           "clear at X of Y" — how widespread the clear northern horizon is)
     * @param regions            distinct regions containing a clear dark-sky location; never null
     * @param eveningWindow      NLC twilight window low in the NW after dusk for a representative
     *                           clear location; may be null when that geometry does not exist
     * @param morningWindow      NLC twilight window low in the NE before dawn; may be null
     */
    public record ClearNight(LocalDate date, int clearLocationCount, int totalDarkSkyCount,
            List<String> regions, NlcWindow eveningWindow, NlcWindow morningWindow) {

        /** Defensive compact constructor. */
        public ClearNight {
            regions = List.copyOf(regions);
        }
    }
}
