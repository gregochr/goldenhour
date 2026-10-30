package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.model.CyclePlaceEvidence.Lane;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Test fixtures that build {@link CyclePlaceEvidence} for the outcome-resolver and
 * location-failure tests. These were static factories on the record itself until nothing in
 * {@code src/main} called them.
 */
public final class CyclePlaceEvidenceFixtures {

    private CyclePlaceEvidenceFixtures() {
    }

    /**
     * Evidence for a place the pipeline triaged away.
     *
     * @return evidence with only {@code triaged} set
     */
    public static CyclePlaceEvidence triagedOnly() {
        return new CyclePlaceEvidence(true, false, Set.of(), Set.of());
    }

    /**
     * Evidence for a place that scored in the given lanes and failed in none.
     *
     * @param lanes the lanes with a usable result
     * @return the evidence
     */
    public static CyclePlaceEvidence scoredIn(Lane... lanes) {
        return new CyclePlaceEvidence(false, false, setOf(lanes), Set.of());
    }

    /**
     * Evidence for a place whose results failed in the given lanes and succeeded in none.
     *
     * @param lanes the lanes with a failed result
     * @return the evidence
     */
    public static CyclePlaceEvidence failedIn(Lane... lanes) {
        return new CyclePlaceEvidence(false, false, Set.of(), setOf(lanes));
    }

    /**
     * Evidence for a place whose collection threw and that has nothing else recorded.
     *
     * @return the evidence
     */
    public static CyclePlaceEvidence collectionError() {
        return new CyclePlaceEvidence(false, true, Set.of(), Set.of());
    }

    private static Set<Lane> setOf(Lane... lanes) {
        Set<Lane> set = EnumSet.noneOf(Lane.class);
        set.addAll(Arrays.asList(lanes));
        return set;
    }
}
