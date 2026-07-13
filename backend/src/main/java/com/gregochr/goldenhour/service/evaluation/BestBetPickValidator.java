package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.BestBet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates parsed best-bet picks against the known-good event IDs, region names, and day names
 * derived from the rollup, discarding invalid picks and re-ranking the survivors.
 *
 * <p>Stateless — all methods are pure functions of their arguments. Extracted from
 * {@code BriefingBestBetAdvisor} so the validation rules can be reasoned about and tested in
 * isolation. Logs under the {@link BriefingBestBetAdvisor} category so rejection diagnostics stay
 * grouped with the advisor's own output.
 */
public final class BestBetPickValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    /** All English day names, used for narrative date validation. */
    private static final List<String> ALL_DAY_NAMES = List.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private BestBetPickValidator() {
    }

    /**
     * Validates picks against the known-good event IDs, region names, and day names,
     * discards any invalid picks, and re-ranks the survivors.
     *
     * <p>A pick is invalid if its {@code event} is not in {@code validEvents} (unless null),
     * its {@code region} is not in {@code validRegions} (unless null or aurora event), or
     * its narrative text references a day name outside the forecast window.
     * If all picks fail validation the list is empty and the caller falls back to the
     * mechanical headline.
     *
     * @param picks        parsed picks from Claude
     * @param validEvents  event IDs present in the rollup input
     * @param validRegions region names present in the rollup input
     * @param validDayNames day names present in the forecast window
     * @return validated, re-ranked list (may be empty)
     */
    public static List<BestBet> validateAndFilterPicks(List<BestBet> picks,
            Set<String> validEvents, Set<String> validRegions, Set<String> validDayNames) {
        List<BestBet> valid = new ArrayList<>();
        for (BestBet pick : picks) {
            if (isPickValid(pick, validEvents, validRegions, validDayNames)) {
                valid.add(pick);
            } else {
                LOG.warn("Best bet pick #{} failed validation — discarding", pick.rank());
            }
        }
        List<BestBet> reranked = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            BestBet p = valid.get(i);
            reranked.add(new BestBet(i + 1, p.headline(), p.detail(),
                    p.event(), p.region(), p.confidence(), p.nearestDriveMinutes(),
                    p.dayName(), p.eventType(), p.eventTime(),
                    p.relationship(), p.differsBy()));
        }
        if (valid.size() < picks.size()) {
            LOG.warn("Best bet validation: {}/{} picks passed", valid.size(), picks.size());
        }
        return List.copyOf(reranked);
    }

    private static boolean isPickValid(BestBet pick, Set<String> validEvents,
            Set<String> validRegions, Set<String> validDayNames) {
        // Stay-home pick (both null) is always valid
        if (pick.event() == null && pick.region() == null) {
            return true;
        }
        if (pick.event() != null && !validEvents.contains(pick.event())) {
            LOG.warn("Best bet pick rejected: event '{}' not in validEvents", pick.event());
            return false;
        }
        // Every non-null region — including an aurora pick's — must be a known region.
        // Aurora events now carry a data-derived region (added to validRegions when present),
        // so an improvised region (e.g. a hallucinated dark-sky region) is rejected. A null
        // region remains valid: the region-agnostic aurora case.
        if (pick.region() != null && !validRegions.contains(pick.region())) {
            LOG.warn("Best bet pick rejected: region '{}' not in validRegions", pick.region());
            return false;
        }
        String narrative = (pick.headline() == null ? "" : pick.headline())
                + " " + (pick.detail() == null ? "" : pick.detail());
        if (narrativeReferencesInvalidDayName(narrative, validDayNames)) {
            LOG.warn("Best bet pick #{} narrative references day outside forecast window", pick.rank());
            return false;
        }
        return true;
    }

    private static boolean narrativeReferencesInvalidDayName(String text, Set<String> validDayNames) {
        if (validDayNames.isEmpty()) {
            return false;
        }
        for (String day : ALL_DAY_NAMES) {
            if (text.contains(day) && !validDayNames.contains(day)) {
                return true;
            }
        }
        return false;
    }
}
