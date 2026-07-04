package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HotTopicEventEnricher}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HotTopicEventEnricherTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 4); // summer — BST is UTC+1

    @Mock
    private SolarService solarService;
    @Mock
    private LocationRepository locationRepository;

    private HotTopicEventEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new HotTopicEventEnricher(solarService, locationRepository);
        LocationEntity dales = LocationEntity.builder()
                .id(1L).name("Malham").lat(54.06).lon(-2.15)
                .region(RegionEntity.builder().id(1L).name("Yorkshire Dales").build())
                .enabled(true).build();
        lenient().when(locationRepository.findAllByEnabledTrueOrderByNameAsc())
                .thenReturn(List.of(dales));
        // 03:43 UTC → 04:43 London (BST); 20:41 UTC → 21:41 London; 21:47 UTC → 22:47 London.
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(DATE)))
                .thenReturn(LocalDateTime.of(2026, 7, 4, 3, 43));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), eq(DATE)))
                .thenReturn(LocalDateTime.of(2026, 7, 4, 20, 41));
        lenient().when(solarService.civilDuskUtc(anyDouble(), anyDouble(), eq(DATE)))
                .thenReturn(LocalDateTime.of(2026, 7, 4, 21, 47));
    }

    private HotTopic topic(String type, ExpandedHotTopicDetail expanded) {
        return new HotTopic(type, type, "detail", DATE, 2, null,
                List.of("Yorkshire Dales"), "desc", expanded);
    }

    private HotTopic enrichOne(HotTopic input) {
        return enricher.enrich(List.of(input)).get(0);
    }

    @Test
    @DisplayName("INVERSION → SUNRISE with the London-local sunrise time")
    void inversion_sunrise() {
        HotTopic result = enrichOne(topic("INVERSION", null));
        assertThat(result.eventType()).isEqualTo("SUNRISE");
        assertThat(result.eventTime()).isEqualTo("04:43");
    }

    @Test
    @DisplayName("a two-day run of the same type gets one distinct event time per date")
    void twoDayRun_distinctPerDayTimes() {
        LocalDate d2 = DATE.plusDays(1);
        // Sunrise shifts a minute later the next morning: 03:43 UTC → 04:43, 03:44 UTC → 04:44.
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), eq(d2)))
                .thenReturn(LocalDateTime.of(2026, 7, 5, 3, 44));

        HotTopic day1 = new HotTopic("INVERSION", "Cloud inversion", "detail", DATE, 2, null,
                List.of("Yorkshire Dales"), "desc", null);
        HotTopic day2 = new HotTopic("INVERSION", "Cloud inversion", "detail", d2, 2, null,
                List.of("Yorkshire Dales"), "desc", null);

        List<HotTopic> result = enricher.enrich(List.of(day1, day2));

        assertThat(result).extracting(HotTopic::eventType)
                .containsExactly("SUNRISE", "SUNRISE");
        assertThat(result).extracting(HotTopic::eventTime)
                .containsExactly("04:43", "04:44");
    }

    @Test
    @DisplayName("DUST → SUNSET with the London-local sunset time")
    void dust_sunset() {
        HotTopic result = enrichOne(topic("DUST", null));
        assertThat(result.eventType()).isEqualTo("SUNSET");
        assertThat(result.eventTime()).isEqualTo("21:41");
    }

    @Test
    @DisplayName("AURORA → NIGHT with the civil-dusk window start")
    void aurora_night() {
        HotTopic result = enrichOne(topic("AURORA", null));
        assertThat(result.eventType()).isEqualTo("NIGHT");
        assertThat(result.eventTime()).isEqualTo("22:47");
    }

    @Test
    @DisplayName("KING_TIDE follows the tide's sunrise alignment")
    void kingTide_sunriseAligned() {
        var tide = new ExpandedHotTopicDetail(null, null,
                new ExpandedHotTopicDetail.TideMetrics("King", "Full", 3, 1));
        HotTopic result = enrichOne(topic("KING_TIDE", tide));
        assertThat(result.eventType()).isEqualTo("SUNRISE");
        assertThat(result.eventTime()).isEqualTo("04:43");
    }

    @Test
    @DisplayName("SPRING_TIDE follows the tide's sunset alignment when sunset dominates")
    void springTide_sunsetAligned() {
        var tide = new ExpandedHotTopicDetail(null, null,
                new ExpandedHotTopicDetail.TideMetrics("Spring", "New", 0, 2));
        HotTopic result = enrichOne(topic("SPRING_TIDE", tide));
        assertThat(result.eventType()).isEqualTo("SUNSET");
    }

    @Test
    @DisplayName("tide with no alignment gets no event")
    void tide_noAlignment_noEvent() {
        var tide = new ExpandedHotTopicDetail(null, null,
                new ExpandedHotTopicDetail.TideMetrics("Spring", "New", 0, 0));
        HotTopic result = enrichOne(topic("SPRING_TIDE", tide));
        assertThat(result.eventType()).isNull();
        assertThat(result.eventTime()).isNull();
    }

    @Test
    @DisplayName("a type with no solar anchor (STORM_SURGE) passes through unchanged")
    void stormSurge_noEvent() {
        HotTopic result = enrichOne(topic("STORM_SURGE", null));
        assertThat(result.eventType()).isNull();
        assertThat(result.eventTime()).isNull();
    }

    @Test
    @DisplayName("a topic with a null date is left untouched")
    void nullDate_untouched() {
        HotTopic dateless = new HotTopic("INVERSION", "Cloud inversion", "detail",
                null, 2, null, List.of("Yorkshire Dales"), "desc", null);
        HotTopic result = enrichOne(dateless);
        assertThat(result.eventType()).isNull();
    }

    @Test
    @DisplayName("an already-set event is preserved")
    void existingEvent_preserved() {
        HotTopic pre = topic("INVERSION", null).withEvent("SUNSET", "20:00");
        HotTopic result = enrichOne(pre);
        assertThat(result.eventType()).isEqualTo("SUNSET");
        assertThat(result.eventTime()).isEqualTo("20:00");
    }

    @Test
    @DisplayName("falls back to a UK-centre time when no region location resolves")
    void noRegionLocation_usesFallback() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        HotTopic result = enrichOne(topic("INVERSION", null));
        assertThat(result.eventType()).isEqualTo("SUNRISE");
        assertThat(result.eventTime()).isEqualTo("04:43"); // solar stub is location-agnostic here
    }

    @Test
    @DisplayName("empty and null input lists are handled")
    void emptyAndNull_handled() {
        assertThat(enricher.enrich(List.of())).isEmpty();
        assertThat(enricher.enrich(null)).isEmpty();
    }

    // ── Qualifying-location backfill ──────────────────────────────────────────

    @Test
    @DisplayName("survivor-provided locations are preserved, not overwritten by the backfill")
    void existingLocations_preserved() {
        HotTopic pre = topic("INVERSION", null).withLocations(List.of("Malham"));
        HotTopic result = enrichOne(pre);
        assertThat(result.locationNames()).containsExactly("Malham");
    }

    @Test
    @DisplayName("KING_TIDE backfills only the coastal locations in its regions")
    void kingTide_backfillsCoastalInRegion() {
        LocationEntity coast = LocationEntity.builder()
                .id(2L).name("Bamburgh").lat(55.6).lon(-1.7)
                .region(RegionEntity.builder().id(2L).name("Northumberland").build())
                .enabled(true).build();
        LocationEntity elsewhere = LocationEntity.builder()
                .id(3L).name("Malham").lat(54.06).lon(-2.15)
                .region(RegionEntity.builder().id(1L).name("Yorkshire Dales").build())
                .enabled(true).build();
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(coast, elsewhere));

        HotTopic result = enrichOne(new HotTopic("KING_TIDE", "King tide", "detail", DATE, 1, null,
                List.of("Northumberland"), "desc", null));

        assertThat(result.locationNames()).containsExactly("Bamburgh");
    }

    @Test
    @DisplayName("AURORA backfills the dark-sky locations in its regions")
    void aurora_backfillsDarkSkyInRegion() {
        LocationEntity kielder = LocationEntity.builder()
                .id(4L).name("Kielder").lat(55.2).lon(-2.5)
                .region(RegionEntity.builder().id(2L).name("Northumberland").build())
                .enabled(true).build();
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(List.of(kielder));

        HotTopic result = enrichOne(new HotTopic("AURORA", "Aurora possible", "detail", DATE, 3, null,
                List.of("Northumberland"), "desc", null));

        assertThat(result.locationNames()).containsExactly("Kielder");
    }
}
