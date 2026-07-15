package com.gregochr.goldenhour.service;

/**
 * The single definition of the clear/overcast cloud boundary shared across the night topics.
 *
 * <p>"Clear" must mean the same thing everywhere it is counted, otherwise a location could count as
 * clear for the meteor pill while being overcast for aurora triage. This class is that one
 * definition, used by:
 *
 * <ul>
 *   <li>the meteor, NLC and aurora "clear at X of Y dark-sky locations" clarity counts
 *       ({@link MeteorClarityService}, {@link NlcClarityService},
 *       {@link BriefingAuroraSummaryBuilder});</li>
 *   <li>the aurora weather triage, which passes a location when <em>any</em> hour in its look-ahead
 *       window is clear ({@code service.aurora.WeatherTriageService});</li>
 *   <li>the samplers' fail-open default ({@link NorthwardTransectSampler},
 *       {@link OverheadCloudSampler}): a cloud fetch that fails, or an hour missing from the
 *       response, assumes {@link #OVERCAST_PERCENT}, so a fetch problem quietly suppresses a
 *       "clear" count rather than inventing a clear sky.</li>
 * </ul>
 *
 * <p>Stateless — {@link #isClear(int)} is a pure function of its argument.
 */
public final class CloudScoringRules {

    /** Total cloud (%) at or above which a sampled sky no longer counts as clear. */
    public static final int OVERCAST_PERCENT = 75;

    private CloudScoringRules() {
    }

    /**
     * Whether a sampled sky counts as clear.
     *
     * @param cloudPercent total cloud cover (%)
     * @return true when the sky counts as clear
     */
    public static boolean isClear(int cloudPercent) {
        return cloudPercent < OVERCAST_PERCENT;
    }
}
