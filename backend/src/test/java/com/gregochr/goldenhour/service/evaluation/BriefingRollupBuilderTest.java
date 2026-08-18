package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateCoverage;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The region rollup the best-bet advisor is prompted with, and the coverage its picks are gated on.
 *
 * <p>The class had no tests of its own. These cover the split the prompt node makes between a
 * <em>coverage</em> question (how many locations here were evaluated — a wood counts, it was) and a
 * <em>sky</em> question (what is this region's colour average — a wood must not, its verdict runs
 * on inverted polarity). Both figures are read off the region's already-enriched slots, so there is
 * no name join to go wrong.
 */
@ExtendWith(MockitoExtension.class)
class BriefingRollupBuilderTest {

    /** A fixed clock, well away from any real "today" so no fixture depends on when it runs. */
    private static final LocalDate DATE = LocalDate.of(2027, 4, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(DATE, LocalTime.of(3, 0));
    private static final LocalDateTime SUNRISE = LocalDateTime.of(DATE, LocalTime.of(5, 30));
    private static final String REGION = "North East";

    @Mock private TravelDayService travelDayService;
    @Mock private BriefingEvaluationService briefingEvaluationService;
    @Mock private StabilitySnapshotProvider stabilitySnapshotProvider;
    @Mock private AuroraStateCache auroraStateCache;
    @Mock private AuroraRegionSelector auroraRegionSelector;

    private final ObjectMapper mapper = new ObjectMapper();
    private BriefingRollupBuilder builder;
    private List<BriefingDay> days;

    @BeforeEach
    void setUp() {
        builder = new BriefingRollupBuilder(mapper,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                travelDayService, briefingEvaluationService, stabilitySnapshotProvider,
                auroraStateCache, auroraRegionSelector);
    }

    /** A location in the region: its name, whether it is a wood, and its cached rating (or null). */
    private record Spot(String name, boolean canopy, Integer rating) { }

    private static Spot sky(String name, Integer rating) {
        return new Spot(name, false, rating);
    }

    private static Spot wood(String name, Integer rating) {
        return new Spot(name, true, rating);
    }

    /**
     * One sunrise day, one region holding the given spots — canopy flags on the slots, ratings in
     * the score cache, which is how production carries them: the cache has no canopy flag, so the
     * two only meet in the builder.
     */
    private void given(Spot... spots) {
        List<BriefingSlot> slots = Arrays.stream(spots)
                .map(spot -> spot.canopy()
                        ? BriefingSlot.canopySlot(spot.name(), SUNRISE, Verdict.GO, null,
                                List.of(), null)
                        : new BriefingSlot(spot.name(), SUNRISE, Verdict.GO, null,
                                BriefingSlot.TideInfo.NONE, List.of(), null))
                .toList();
        BriefingRegion region = new BriefingRegion(REGION, Verdict.GO, "summary", List.of(),
                slots, 12.0, 11.0, 4.0, 3, null, null);
        days = List.of(new BriefingDay(DATE, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of()))));

        Map<String, BriefingEvaluationResult> cached = new LinkedHashMap<>();
        Arrays.stream(spots).filter(spot -> spot.rating() != null).forEach(spot ->
                cached.put(spot.name(), new BriefingEvaluationResult(
                        spot.name(), spot.rating(), 60, 60, "Conditions.")));
        // eq(), not any(): the cache key is the one argument a refactor of this method would get
        // wrong, and a matcher that accepts anything cannot fail when it does.
        when(briefingEvaluationService.getCachedScores(eq(REGION), eq(DATE), eq(TargetType.SUNRISE)))
                .thenReturn(cached);
    }

    /** The region node the advisor is prompted with. */
    private JsonNode regionNode() throws Exception {
        JsonNode root = mapper.readTree(builder.buildRollupJson(days, NOW).json());
        return root.get("events").get(0).get("regions").get(0);
    }

    /** The single coverage entry the advisor's evidence gates read. */
    private CandidateCoverage coverage() throws Exception {
        return builder.buildRollupJson(days, NOW).coverageByKey().values().iterator().next();
    }

    @Test
    @DisplayName("A rated wood is counted as evaluated, but does not lift the sky average")
    void aRatedWoodIsCountedButDoesNotLiftTheSkyAverage() throws Exception {
        // The whole fix in one fixture. {5, 2, 2} averages 3.0 and clears the 3.0 Also-Good floor;
        // the sky alone is 2.0 and does not. The counts move the other way on purpose: the wood
        // genuinely WAS evaluated, the prompt defines claudeRatedCount that way, and its sibling
        // totalLocations counts it too — so both figures stay over one population.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", 2), sky("Alnmouth", 2));

        JsonNode region = regionNode();

        assertThat(region.get("claudeAverageRating").asDouble()).isEqualTo(2.0);
        assertThat(region.get("claudeRatedCount").asInt()).isEqualTo(3);
        assertThat(region.get("claudeHighRatedCount").asInt()).isEqualTo(1);
        assertThat(region.get("claudeMediumRatedCount").asInt()).isZero();
    }

    @Test
    @DisplayName("A mid-band wood is counted in the medium bucket and still leaves the sky alone")
    void aMediumBandWoodIsCountedAndStillLeavesTheSkyAlone() throws Exception {
        // The other side of every band. The wood sits at 3 so it lands in mediumRated rather than
        // highRated, and it would DRAG the average (5,5 -> 4.3) rather than lift it — the filter
        // has to be about which question is asked, not about protecting the number's direction.
        given(wood("Bluebell Wood", 3), sky("Bamburgh", 5), sky("Alnmouth", 5));

        JsonNode region = regionNode();

        assertThat(region.get("claudeAverageRating").asDouble()).isEqualTo(5.0);
        assertThat(region.get("claudeRatedCount").asInt()).isEqualTo(3);
        assertThat(region.get("claudeHighRatedCount").asInt()).isEqualTo(2);
        assertThat(region.get("claudeMediumRatedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("An all-canopy region averages its woods rather than falling silent")
    void anAllCanopyRegionAveragesItsWoods() throws Exception {
        // The fallback half of BriefingSlot.votingSlots, exercised through the real method — a
        // region with nothing else genuinely has a forecast, and dropping every slot would report
        // it as unevaluated, which is a different and false statement. Starves the SLOT LIST.
        given(wood("Bluebell Wood", 4), wood("Hollow Copse", 4));

        JsonNode region = regionNode();

        assertThat(region.get("claudeAverageRating").asDouble()).isEqualTo(4.0);
        assertThat(region.get("claudeRatedCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A region whose only rated slot is a wood reports coverage but no sky average")
    void aWoodOnlyRatedRegionReportsCoverageButNoSkyAverage() throws Exception {
        // Starves the RATINGS while a sky slot is present, which is the other way the fallback can
        // be reached and the pair the all-canopy test needs: neither alone pins the guard. The
        // average is OMITTED, not zeroed — a 0.0 here would be a claim about the sky that nothing
        // measured, and it would sink the region below every floor as if it had been assessed.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", null));

        JsonNode region = regionNode();

        assertThat(region.get("claudeRatedCount").asInt()).isEqualTo(1);
        assertThat(region.has("claudeAverageRating")).isFalse();
    }

    @Test
    @DisplayName("A region with nothing rated carries no Claude block at all")
    void anUnratedRegionCarriesNoClaudeBlockAtAll() throws Exception {
        // Absent, not zero. The prompt falls back to verdict-only data, and a fabricated
        // "claudeRatedCount: 0, claudeAverageRating: 0.0" would read as an assessment that
        // returned nothing rather than as one that never ran.
        given(wood("Bluebell Wood", null), sky("Bamburgh", null));

        JsonNode region = regionNode();

        assertThat(region.has("claudeRatedCount")).isFalse();
        assertThat(region.has("claudeAverageRating")).isFalse();
    }

    @Test
    @DisplayName("The coverage gate reads the same two numbers the node carries, in the same order")
    void coverageMirrorsTheNodeSoReplayReproducesIt() throws Exception {
        // Two output channels, and they must not drift: BestBetRanker.dropUnevaluatedPicks and
        // isHeadlineEligible read the COUNT from CandidateCoverage, while reconstructRollup rebuilds
        // that same record by parsing claudeRatedCount and claudeAverageRating back out of a stored
        // rollup. A split basis between the two would make a replayed run gate differently from the
        // live one it is meant to reproduce.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", 2), sky("Alnmouth", 2));

        JsonNode region = regionNode();

        assertThat(coverage()).isEqualTo(new CandidateCoverage(3, 0, 2.0));
        assertThat(coverage().claudeRatedCount()).isEqualTo(region.get("claudeRatedCount").asInt());
        assertThat(coverage().claudeAverageRating())
                .isEqualTo(region.get("claudeAverageRating").asDouble());
    }

    @Test
    @DisplayName("A wood-only rated region still reports zero sky average to the gates")
    void aWoodOnlyRatedRegionReportsZeroSkyAverageToTheGates() throws Exception {
        // The coverage-channel half of the wood-only case. The count survives, so the pick is not
        // dropped as unevaluated — deliberately: dropUnevaluatedPicks asks a coverage question and
        // this region genuinely was covered. What it no longer carries is a sky average that could
        // clear a floor.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", null));

        assertThat(coverage()).isEqualTo(new CandidateCoverage(1, 0, 0.0));
    }

    @Test
    @DisplayName("A cached score whose location no slot claims counts as sky — documented, not ideal")
    void anUnmatchedCachedNameCountsAsSky() throws Exception {
        // Pins the fail-open join, with a wood present so the canopy axis is held constant and the
        // filter is doing real work rather than being a structural no-op. This is the residual the
        // method javadoc records: the cache outlives the roster, so a location since disabled or
        // renamed still answers under a name no slot claims, and an unmatched name is treated as
        // sky. Asserted so it cannot change silently in either direction — closing it means reading
        // slot ratings instead, which a separate pinned contract currently forbids.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", 2), new Spot("Departed Point", false, 4));

        // 2 and 4 average to 3.0; the wood's 5 is excluded, the orphan's 4 is not.
        assertThat(regionNode().get("claudeAverageRating").asDouble()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("claudeRatedCount stays over the same population as totalLocations")
    void theCountStaysOverTheSamePopulationAsTotalLocations() throws Exception {
        // Pins the design decision, because the tempting "fix" is to make the counts sky-only too.
        // That would leave Claude able to form claudeRatedCount / totalLocations from two different
        // populations and read it as a coverage gap that does not exist, and it would silently
        // rebase BestBetRanker.MIN_HEADLINE_CLAUDE_COVERAGE, a constant calibrated on the old one.
        given(wood("Bluebell Wood", 5), sky("Bamburgh", 4), sky("Alnmouth", 4));

        JsonNode region = regionNode();

        assertThat(region.get("claudeRatedCount").asInt())
                .isEqualTo(region.get("totalLocations").asInt())
                .isEqualTo(3);
    }
}
