package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BluebellHotTopicStrategy}.
 *
 * <p>The strategy reads the Claude {@code BLUEBELL} component scores (1–5) through the unified
 * {@link SurvivorSignalReader} (a non-null {@code scores().bluebell()} self-selects bluebell sites);
 * thresholds are HOT_TOPIC &ge; 3, EXPANDED &ge; 2, high-priority &ge; 4.
 */
@ExtendWith(MockitoExtension.class)
class BluebellHotTopicStrategyTest {

    /** A date within bluebell season. */
    private static final LocalDate IN_SEASON = LocalDate.of(2026, 4, 25);

    /** A date outside bluebell season. */
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 6, 15);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private BluebellHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new BluebellHotTopicStrategy(survivorSignalReader,
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"), freshness);
    }

    /** Keeps each given day's SUNRISE event ahead of the freshness cutoff. Stubbed per-test (only the
     * tests that supply bluebell rows reach the freshness filter), with the date + SUNRISE event
     * pinned; the location varies per row and is not the discriminator (the filter is per-day). */
    private void stubAhead(LocalDate... days) {
        for (LocalDate day : days) {
            when(freshness.isAhead(any(LocationEntity.class), eq(day), eq(TargetType.SUNRISE)))
                    .thenReturn(true);
        }
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("returns empty list when called outside bluebell season")
    void detect_outsideSeason_returnsEmpty() {
        List<HotTopic> topics = strategy.detect(OUT_OF_SEASON, OUT_OF_SEASON.plusDays(3));

        assertThat(topics).isEmpty();
        verifyNoInteractions(survivorSignalReader);
    }

    @Test
    @DisplayName("returns empty list when no bluebell-scored survivors exist")
    void detect_noBluebellSignals_returnsEmpty() {
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON.plusDays(2))).thenReturn(List.of());

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON.plusDays(2));

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("a bluebell row whose sunrise has passed is dropped")
    void detect_expiredEvent_dropped() {
        RegionEntity region = new RegionEntity();
        region.setName("Lake District");
        LocationEntity location = LocationEntity.builder()
                .id(1L).name("Rannerdale Knotts").lat(54.556).lon(-3.292)
                .locationType(Set.of(LocationType.BLUEBELL)).region(region).enabled(true).build();
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 4, "Misty and still")));
        when(freshness.isAhead(any(LocationEntity.class), eq(IN_SEASON), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        assertThat(strategy.detect(IN_SEASON, IN_SEASON)).isEmpty();
    }

    @Test
    @DisplayName("emits hot topic when best bluebell rating >= 3 during season")
    void detect_goodConditions_emitsHotTopic() {
        RegionEntity region = new RegionEntity();
        region.setName("Lake District");

        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Rannerdale Knotts")
                .lat(54.5560)
                .lon(-3.2920)
                .locationType(Set.of(LocationType.BLUEBELL, LocationType.LANDSCAPE))
                .region(region)
                .enabled(true)
                .build();

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 4, "Misty and still")));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("BLUEBELL");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.date()).isEqualTo(IN_SEASON);
        assertThat(topic.filterAction()).isEqualTo("BLUEBELL");
        assertThat(topic.regions()).containsExactly("Lake District");
    }

    @Test
    @DisplayName("returns empty list when best bluebell rating < 3")
    void detect_poorConditions_returnsEmpty() {
        LocationEntity location = simpleLocation(1L, "Test Location");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 2, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("excellent conditions (rating >= 4) get priority 1")
    void detect_excellentConditions_priority1() {
        LocationEntity location = simpleLocation(1L, "Rannerdale");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 5, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("workable conditions (rating 3) get priority 3")
    void detect_goodConditions_priority3() {
        LocationEntity location = simpleLocation(1L, "Allen Banks");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 3, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(3);
    }

    // ── Fact line (science showing) ──────────────────────────────────────────

    @Test
    @DisplayName("fact line shows the conditions rating + note, and NEVER a bloom/peak claim")
    void detect_factLine_conditionsAndNote_noBloomClaim() {
        LocationEntity location = simpleLocation(1L, "Rannerdale");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 5, "Misty and still")));
        stubAhead(IN_SEASON);

        HotTopic topic = strategy.detect(IN_SEASON, IN_SEASON).get(0);

        assertThat(factWithKey(topic, "conditions").value()).isEqualTo("5/5 · excellent");
        assertThat(topic.note())
                .isEqualTo("still, misty mornings diffuse the low sun — before wind and harsh light");
        // Phenology is modelled nowhere — no fabricated bloom percentage or "peak" claim.
        assertThat(topic.facts()).noneMatch(f -> f.value() != null
                && (f.value().contains("bloom") || f.value().contains("peak") || f.value().contains("%")));
        // A single qualifying site carries no breadth chip.
        assertThat(topic.facts()).hasSize(1);
    }

    @Test
    @DisplayName("breadth chip appears once two or more sites reach the 4+/5 tier")
    void detect_factLine_breadthChipWhenMultipleTopSites() {
        LocationEntity loc1 = regionLocation(1L, "Rannerdale", "Lake District");
        LocationEntity loc2 = regionLocation(2L, "Allen Banks", "Northumberland");
        LocationEntity loc3 = regionLocation(3L, "Middling Wood", "Lake District");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON)).thenReturn(List.of(
                bluebellSignal(loc1, IN_SEASON, 5, null),
                bluebellSignal(loc2, IN_SEASON, 4, null),
                bluebellSignal(loc3, IN_SEASON, 3, null)));   // below the 4+ tier — not counted
        stubAhead(IN_SEASON);

        HotTopic topic = strategy.detect(IN_SEASON, IN_SEASON).get(0);

        assertThat(topic.facts()).anyMatch(f -> "2 sites scoring 4+/5".equals(f.value()));
    }

    // ── HOT_TOPIC_THRESHOLD boundary ─────────────────────────────────────────

    @Test
    @DisplayName("rating 2 does NOT emit a hot topic (threshold is >= 3)")
    void detect_score2_doesNotEmit() {
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 2, null)));
        stubAhead(IN_SEASON);

        assertThat(strategy.detect(IN_SEASON, IN_SEASON)).isEmpty();
    }

    // ── Priority boundary tests ───────────────────────────────────────────────

    @Test
    @DisplayName("rating 3 gets priority 3 (boundary: < 4 threshold)")
    void detect_score3_priority3() {
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 3, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(3);
    }

    @Test
    @DisplayName("rating 4 gets priority 1 (boundary: >= 4 threshold)")
    void detect_score4_priority1() {
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 4, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).priority()).isEqualTo(1);
    }

    // ── MAX_REGIONS enforcement ───────────────────────────────────────────────

    @Test
    @DisplayName("region list is capped at 2 even when 3 qualifying regions exist")
    void detect_threeQualifyingRegions_cappedAt2() {
        LocationEntity loc1 = regionLocation(1L, "L1", "Northumberland");
        LocationEntity loc2 = regionLocation(2L, "L2", "Lake District");
        LocationEntity loc3 = regionLocation(3L, "L3", "North York Moors");

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(
                        bluebellSignal(loc1, IN_SEASON, 5, null),
                        bluebellSignal(loc2, IN_SEASON, 4, null),
                        bluebellSignal(loc3, IN_SEASON, 3, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).hasSize(2);
    }

    @Test
    @DisplayName("region with highest rating appears first in the list")
    void detect_multipleRegions_sortedByScoreDescending() {
        LocationEntity loc1 = regionLocation(1L, "L1", "Northumberland");
        LocationEntity loc2 = regionLocation(2L, "L2", "Lake District");

        // deliberately supply low-then-high to confirm sorting is by score, not input order.
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(
                        bluebellSignal(loc2, IN_SEASON, 3, null),
                        bluebellSignal(loc1, IN_SEASON, 5, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions().get(0)).isEqualTo("Northumberland");
    }

    // ── Day label tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("day label is 'today' when evaluation date equals fromDate")
    void detect_evaluationOnFromDate_detailContainsToday() {
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(location, IN_SEASON, 4, "Misty")));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics.get(0).detail()).contains("today");
    }

    @Test
    @DisplayName("day label is 'tomorrow' when evaluation date is fromDate + 1")
    void detect_evaluationOnFromDatePlusOne_detailContainsTomorrow() {
        LocalDate tomorrow = IN_SEASON.plusDays(1);
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, tomorrow))
                .thenReturn(List.of(bluebellSignal(location, tomorrow, 4, null)));
        stubAhead(tomorrow);

        List<HotTopic> topics = strategy.detect(IN_SEASON, tomorrow);

        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("day label is full weekday name for dates beyond tomorrow")
    void detect_evaluationBeyondTomorrow_detailContainsWeekdayName() {
        // IN_SEASON = 2026-04-25 (Saturday), plusDays(2) = 2026-04-27 (Monday)
        LocalDate monday = IN_SEASON.plusDays(2);
        LocationEntity location = simpleLocation(1L, "Test");
        when(survivorSignalReader.read(IN_SEASON, monday))
                .thenReturn(List.of(bluebellSignal(location, monday, 4, null)));
        stubAhead(monday);

        List<HotTopic> topics = strategy.detect(IN_SEASON, monday);

        assertThat(topics.get(0).detail()).contains("Monday");
    }

    // ── Read-model interaction ────────────────────────────────────────────────

    @Test
    @DisplayName("reads the survivor model over the requested date range")
    void detect_readsReaderWithDateRange() {
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON.plusDays(3))).thenReturn(List.of());

        strategy.detect(IN_SEASON, IN_SEASON.plusDays(3));

        verify(survivorSignalReader).read(IN_SEASON, IN_SEASON.plusDays(3));
    }

    // ── expandedDetail tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("expandedDetail populated with regionGroups when rating >= 2")
    void detect_expandedDetail_populatedWhenScoreGte2() {
        RegionEntity region = new RegionEntity();
        region.setName("Lake District");
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Rannerdale Knotts").lat(54.5).lon(-3.2)
                .region(region).bluebellExposure(BluebellExposure.WOODLAND).enabled(true).build();

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(loc, IN_SEASON, 4, "Misty and still")));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        ExpandedHotTopicDetail detail = topics.get(0).expandedDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.regionGroups()).hasSize(1);
        assertThat(detail.regionGroups().get(0).regionName()).isEqualTo("Lake District");
        assertThat(detail.regionGroups().get(0).locations()).hasSize(1);
    }

    @Test
    @DisplayName("region with rating below 2 is excluded from regionGroups")
    void detect_expandedDetail_scoreBelowThresholdExcluded() {
        LocationEntity loc1 = regionLocation(1L, "Rannerdale", "Lake District");
        LocationEntity loc2 = regionLocation(2L, "Allen Banks", "Northumberland");

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(
                        bluebellSignal(loc1, IN_SEASON, 4, null),
                        bluebellSignal(loc2, IN_SEASON, 1, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        ExpandedHotTopicDetail detail = topics.get(0).expandedDetail();
        assertThat(detail.regionGroups()).hasSize(1);
        assertThat(detail.regionGroups().get(0).regionName()).isEqualTo("Lake District");
    }

    @Test
    @DisplayName("locations sorted by rating descending within region")
    void detect_expandedDetail_locationsSortedByScoreDescending() {
        LocationEntity loc1 = regionLocation(1L, "Low Scorer", "Lake District");
        LocationEntity loc2 = regionLocation(2L, "High Scorer", "Lake District");

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(
                        bluebellSignal(loc1, IN_SEASON, 3, null),
                        bluebellSignal(loc2, IN_SEASON, 5, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        var locations = topics.get(0).expandedDetail().regionGroups().get(0).locations();
        assertThat(locations).hasSize(2);
        assertThat(locations.get(0).locationName()).isEqualTo("High Scorer");
        assertThat(locations.get(1).locationName()).isEqualTo("Low Scorer");
    }

    @Test
    @DisplayName("highest scoring location per region has 'Best' badge")
    void detect_expandedDetail_bestBadgeOnHighestScoringLocation() {
        LocationEntity loc1 = regionLocation(1L, "Second", "Lake District");
        LocationEntity loc2 = regionLocation(2L, "First", "Lake District");

        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(
                        bluebellSignal(loc1, IN_SEASON, 3, null),
                        bluebellSignal(loc2, IN_SEASON, 5, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        var locations = topics.get(0).expandedDetail().regionGroups().get(0).locations();
        assertThat(locations.get(0).badge()).isEqualTo("Best");
        assertThat(locations.get(1).badge()).isNull();
    }

    @Test
    @DisplayName("bluebellMetrics bestScore matches highest rating")
    void detect_expandedDetail_bluebellMetricsBestScoreCorrect() {
        LocationEntity loc = regionLocation(1L, "Test", "Lake District");
        when(survivorSignalReader.read(IN_SEASON, IN_SEASON))
                .thenReturn(List.of(bluebellSignal(loc, IN_SEASON, 5, null)));
        stubAhead(IN_SEASON);

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        var metrics = topics.get(0).expandedDetail().bluebellMetrics();
        assertThat(metrics.bestScore()).isEqualTo(5);
        assertThat(metrics.scoringLocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("qualityLabel correct for each rating (1-5 rubric)")
    void deriveQualityLabel_correctForEachRange() {
        assertThat(BluebellHotTopicStrategy.deriveQualityLabel(5)).isEqualTo("Excellent");
        assertThat(BluebellHotTopicStrategy.deriveQualityLabel(4)).isEqualTo("Good");
        assertThat(BluebellHotTopicStrategy.deriveQualityLabel(3)).isEqualTo("Fair");
        assertThat(BluebellHotTopicStrategy.deriveQualityLabel(2)).isEqualTo("Fair");
        assertThat(BluebellHotTopicStrategy.deriveQualityLabel(1)).isEqualTo("Fair");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static LocationEntity simpleLocation(long id, String name) {
        return LocationEntity.builder()
                .id(id).name(name).lat(54.0).lon(-3.0).enabled(true).build();
    }

    private static LocationEntity regionLocation(long id, String name, String regionName) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        return LocationEntity.builder()
                .id(id).name(name).lat(54.0).lon(-3.0).region(region).enabled(true).build();
    }

    private static SurvivorSignals bluebellSignal(LocationEntity location, LocalDate date,
            int score, String summary) {
        return new SurvivorSignals(location, date, TargetType.SUNRISE,
                new SurvivorSignals.Scores(null, score, summary), SurvivorSignals.Readings.EMPTY);
    }
}
