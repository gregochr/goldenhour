package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The single, complete set of tide facts derived for one coastal location at one solar event.
 *
 * <p>This is the one return type of {@link com.gregochr.goldenhour.service.TideFactDeriver} — the
 * single seam through which all tide-fact derivation flows. Both consumers adapt their own existing
 * presentation type from this record: the scoring path maps it to {@link TideSnapshot} (collapsing
 * the two statistical booleans into {@link #statisticalSize()}), and the hot-topic / briefing path
 * maps it to {@code BriefingSlotBuilder.TideResult} (applying its own high-tide alignment gate
 * around the two booleans). Neither consumer derives tide facts itself.
 *
 * <p>The two statistical signals are carried as <em>independent raw booleans</em> rather than as the
 * collapsed {@link TideStatisticalSize} enum, because the briefing path needs both flags
 * independently and the enum's P95-first collapse is only lossless when {@code p95 >=
 * springThreshold} — which is not guaranteed. Carrying both booleans makes both consumers'
 * reconstructions exact regardless of threshold ordering.
 *
 * <p>{@code widenedAligned} is the second alignment flag the scoring path's {@code TideContext}
 * needs (its 3★ "imperfect but workable tide" band). It is derived from the <em>same</em> fetched
 * tide curve as {@code tideAligned} — only the ± window differs — so it is produced here in the one
 * seam rather than by a second fetch. The briefing path simply ignores it.
 *
 * @param tideState                  tide state at the solar event
 * @param nextHighTideTime           time of the next high tide
 * @param nextHighTideHeightMetres   height of the next high tide, or {@code null} if unavailable
 * @param nextLowTideTime            time of the next low tide
 * @param nextLowTideHeightMetres    height of the next low tide, or {@code null} if unavailable
 * @param tideAligned                whether the tide aligns with the location's preference within
 *                                   the tight golden/blue-hour window
 * @param widenedAligned             whether the tide aligns within the tight window widened by 60
 *                                   minutes each edge (the scoring 3★ band); the briefing path
 *                                   ignores this
 * @param nearestHighTideTime        time of the nearest high tide to the solar event
 * @param nearestLowTideTime         time of the nearest low tide to the solar event
 * @param lunarTideType              lunar classification (king / spring / regular) for the date
 * @param lunarPhase                 moon phase name for the date
 * @param moonAtPerigee              whether the moon is at perigee on the date, or {@code null}
 * @param heightAboveP95             true if the next high tide exceeds the historical P95 height
 *                                   (the king-tide statistical signal)
 * @param heightAboveSpringThreshold true if the next high tide exceeds the spring-tide threshold
 *                                   (the spring-tide statistical signal)
 */
public record TideDerivation(
        TideState tideState,
        LocalDateTime nextHighTideTime,
        BigDecimal nextHighTideHeightMetres,
        LocalDateTime nextLowTideTime,
        BigDecimal nextLowTideHeightMetres,
        boolean tideAligned,
        boolean widenedAligned,
        LocalDateTime nearestHighTideTime,
        LocalDateTime nearestLowTideTime,
        LunarTideType lunarTideType,
        String lunarPhase,
        Boolean moonAtPerigee,
        boolean heightAboveP95,
        boolean heightAboveSpringThreshold) {

    /**
     * Collapses the two independent statistical booleans into the scoring path's
     * {@link TideStatisticalSize} enum, P95 taking priority — exactly mirroring the historical
     * {@code ForecastDataAugmentor.classifyStatisticalSize} logic.
     *
     * @return {@link TideStatisticalSize#EXTRA_EXTRA_HIGH} if above P95,
     *     {@link TideStatisticalSize#EXTRA_HIGH} if above the spring threshold (but not P95),
     *     or {@code null} if neither
     */
    public TideStatisticalSize statisticalSize() {
        if (heightAboveP95) {
            return TideStatisticalSize.EXTRA_EXTRA_HIGH;
        }
        if (heightAboveSpringThreshold) {
            return TideStatisticalSize.EXTRA_HIGH;
        }
        return null;
    }
}
