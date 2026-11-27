package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.service.EvaluationViewService.LatestDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fields the unscored-reason card adds (docs/engineering/unscored-reason-card-plan.md C1): the serve-time
 * {@code BriefingSlot.disposition} stamp (never on the wire), {@code BriefingRegion.fog} and
 * {@code BriefingWindow.coverageReason}/{@code fog} (NON_NULL), and that every wither carries them.
 */
class UnscoredReasonFieldsTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 28, 7, 0);
    private static final LatestDisposition STAMP =
            new LatestDisposition(DispositionCategory.SKIPPED_TRIAGED, false, true);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static BriefingSlot slot() {
        return new BriefingSlot("Durham", AT, Verdict.GO, null, BriefingSlot.TideInfo.NONE, List.of());
    }

    private static BriefingRegion blankedRegion(Boolean fog) {
        return new BriefingRegion("North East", Verdict.GO, "s", List.of(), List.of(), 10.0, 9.0, 3.0, 1, null, null,
                DisplayVerdict.STAND_DOWN, 0, "Fog forecast", null, null, null, null, null, null,
                CoverageReason.STOOD_DOWN, null, fog);
    }

    private static BriefingWindow window() {
        return new BriefingWindow(AT, DisplayVerdict.STAND_DOWN, null, null, null, List.of(), null);
    }

    @Test
    @DisplayName("a slot stamped with a disposition carries it through every wither and never serialises it")
    void slotDispositionIsAStampAndNeverOnTheWire() throws Exception {
        BriefingSlot stamped = slot().withDisposition(STAMP);

        assertThat(slot().disposition()).isNull();
        assertThat(stamped.disposition()).isEqualTo(STAMP);
        assertThat(stamped.withTide(null).disposition()).isEqualTo(STAMP);
        assertThat(stamped.withEclipse(null).disposition()).isEqualTo(STAMP);
        assertThat(stamped.withFlags(List.of("x")).disposition()).isEqualTo(STAMP);
        assertThat(stamped.withReachStamps(true, true).disposition()).isEqualTo(STAMP);
        assertThat(stamped.withNeighbourOutlier(Boolean.TRUE).disposition()).isEqualTo(STAMP);
        assertThat(stamped.withClaudeScores(4, 70, 60, "why").disposition()).isEqualTo(STAMP);
        assertThat(stamped.withDisposition(null).disposition()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(stamped)).has("disposition")).isFalse();
    }

    @Test
    @DisplayName("the 20-component slot form defaults the disposition stamp to null")
    void twentyComponentFormDefaultsTheStamp() {
        BriefingSlot twenty = new BriefingSlot(1L, "Durham", AT, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), null, null, null, null, null, DisplayVerdict.UNRATED, null, false, null, null, null,
                true, true);

        assertThat(twenty.forced()).isTrue();
        assertThat(twenty.triaged()).isTrue();
        assertThat(twenty.disposition()).isNull();
    }

    @Test
    @DisplayName("a region's fog is served true and omitted when null, and every wither carries it")
    void regionFogIsNonNullAndCarried() throws Exception {
        BriefingRegion fog = blankedRegion(Boolean.TRUE);

        assertThat(mapper.readTree(mapper.writeValueAsString(fog)).get("fog").asBoolean()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(blankedRegion(null))).has("fog")).isFalse();
        assertThat(fog.withMeanRating(3.0).fog()).isTrue();
        assertThat(fog.withBestRating(4).fog()).isTrue();
        assertThat(fog.withConfidence(Confidence.LOW).fog()).isTrue();
        assertThat(fog.withGloss("h", "d").fog()).isTrue();
        assertThat(fog.withSlots(List.of()).fog()).isTrue();
        assertThat(fog.withPicksBestRating(3).fog()).isTrue();
        assertThat(fog.withSampleSufficient(true).fog()).isTrue();
        assertThat(fog.withForcedSample(true).fog()).isTrue();
        assertThat(fog.withMeanRatingDelta(0.5).fog()).isTrue();
    }

    @Test
    @DisplayName("the 22-component region form defaults fog to null")
    void twentyTwoComponentRegionFormDefaultsFog() {
        BriefingRegion twentyTwo = new BriefingRegion("North East", Verdict.GO, "s", List.of(), List.of(), 10.0, 9.0,
                3.0, 1, null, null, DisplayVerdict.STAND_DOWN, 0, null, null, null, null, null, null, null,
                CoverageReason.STOOD_DOWN, 3);

        assertThat(twentyTwo.fog()).isNull();
        assertThat(twentyTwo.picksBestRating()).isEqualTo(3);
        assertThat(twentyTwo.coverageReason()).isEqualTo(CoverageReason.STOOD_DOWN);
    }

    @Test
    @DisplayName("a window's coverage reason and fog are served by name and omitted when null")
    void windowReasonIsNonNull() throws Exception {
        BriefingWindow reasoned = window().withCoverage(CoverageReason.NOT_ATTEMPTED, Boolean.TRUE);

        JsonNode tree = mapper.readTree(mapper.writeValueAsString(reasoned));
        assertThat(tree.get("coverageReason").asText()).isEqualTo("NOT_ATTEMPTED");
        assertThat(tree.get("fog").asBoolean()).isTrue();
        JsonNode bare = mapper.readTree(mapper.writeValueAsString(window()));
        assertThat(bare.has("coverageReason")).isFalse();
        assertThat(bare.has("fog")).isFalse();
    }

    @Test
    @DisplayName("every window wither carries the coverage reason and fog")
    void everyWindowWitherCarriesTheReason() {
        BriefingWindow reasoned = window().withCoverage(CoverageReason.NOT_ATTEMPTED, Boolean.TRUE);

        List<BriefingWindow> copies = List.of(reasoned.withTide(null), reasoned.withTideFacts(null),
                reasoned.withBadges(List.of()), reasoned.withBestRating(null), reasoned.withPick(null),
                reasoned.withWarnings(null, null));

        for (BriefingWindow copy : copies) {
            assertThat(copy.coverageReason()).isEqualTo(CoverageReason.NOT_ATTEMPTED);
            assertThat(copy.fog()).isTrue();
        }
        assertThat(window().coverageReason()).isNull();
    }
}
