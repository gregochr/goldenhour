package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for the "record conditions for every place" change (Phase 1, owner decision
 * 2026-09-30), wiring the REAL {@link SlotAtmosphereWriter}, the REAL {@link
 * SlotSignalReader} and a REAL hot-topic strategy together (only the two JPA repositories are
 * mocked). Every other test touching these classes mocks the reader or the writer individually;
 * this class exists because the interesting claim — "a place a Claude call never reached still
 * shows a hot-topic chip, and the missing {@code forecast_score} component reads as null, not
 * zero, and does not throw" — is a claim about how the writer, the join and a strategy agree with
 * each other, not about any one of them in isolation.
 */
@ExtendWith(MockitoExtension.class)
class RecordConditionsForEveryPlaceIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDate WINDOW_END = DATE.plusDays(3);
    private static final Clock CLOCK =
            Clock.fixed(DATE.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock
    private SlotAtmosphereRepository slotAtmosphereRepository;
    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SolarEventFreshness freshness;
    @Mock
    private DustFactsBuilder dustFactsBuilder;

    private SlotAtmosphereWriter writer;
    private SlotSignalReader reader;

    @BeforeEach
    void setUp() {
        writer = new SlotAtmosphereWriter(slotAtmosphereRepository, CLOCK, true);
        reader = new SlotSignalReader(forecastScoreRepository, slotAtmosphereRepository);
    }

    private static LocationEntity location(long id, String name) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland Coast");
        loc.setRegion(region);
        return loc;
    }

    private void stubEmptyForecastScores() {
        when(forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), DATE, WINDOW_END)).thenReturn(List.of());
        when(forecastScoreRepository.findComponentsByType(
                ForecastType.BLUEBELL.getId(), DATE, WINDOW_END)).thenReturn(List.of());
    }

    @Test
    @DisplayName("a dust chip fires for a TRIAGED-OUT place with dust above threshold, through "
            + "the real writer, the real reader and the real strategy — this slot has NO "
            + "forecast_score row at all, since a triaged candidate never reaches Claude")
    void dustFires_forTriagedOutPlace_endToEnd() {
        LocationEntity loc = location(1L, "Bamburgh Castle");
        // Dust above threshold, PM2.5 low enough to rule out smoke — the same atmospheric
        // snapshot a TRIAGED candidate carries (ForecastService.fetchWeatherAndTriage returns the
        // full snapshot even on a triage verdict, never a null one).
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName(loc.getName())
                .targetType(TargetType.SUNSET)
                .aod(new BigDecimal("0.50"))
                .pm25(new BigDecimal("10.00"))
                .build();

        // Step 1: the write ForecastTaskCollector now makes for EVERY candidate that fetched
        // weather, regardless of the triage verdict.
        when(slotAtmosphereRepository.findByLocationIdAndEvaluationDateAndEventType(
                1L, DATE, TargetType.SUNSET)).thenReturn(Optional.empty());
        writer.write(loc, DATE, TargetType.SUNSET, data);
        ArgumentCaptor<SlotAtmosphereEntity> saved =
                ArgumentCaptor.forClass(SlotAtmosphereEntity.class);
        verify(slotAtmosphereRepository).save(saved.capture());
        SlotAtmosphereEntity storedRow = saved.getValue();
        assertThat(storedRow.getAerosolOpticalDepth()).isEqualByComparingTo("0.50");

        // Step 2: the reader sees the written row, and NO forecast_score rows at all — this slot
        // was triaged out and never reached Claude.
        stubEmptyForecastScores();
        when(slotAtmosphereRepository.findInDateRange(DATE, WINDOW_END))
                .thenReturn(List.of(storedRow));

        // Step 3: the real strategy fires off the real reader's join.
        DustHotTopicStrategy strategy =
                new DustHotTopicStrategy(reader, freshness, dustFactsBuilder);
        when(freshness.isAhead(any(), any(), any())).thenReturn(true);
        when(dustFactsBuilder.attach(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<HotTopic> topics = strategy.detect(DATE, WINDOW_END);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("DUST");
        assertThat(topics.get(0).regions()).containsExactly("Northumberland Coast");
    }

    @Test
    @DisplayName("a slot with a slot_atmosphere reading but NO forecast_score row reads as a "
            + "null inversion component, not zero — InversionHotTopicStrategy does not fire on it "
            + "and does not throw")
    void inversionComponentAbsent_readsAsNullNotZero_noExceptionThroughRealStrategy() {
        LocationEntity loc = location(2L, "Cat Bells");
        SlotAtmosphereEntity reading = new SlotAtmosphereEntity();
        reading.setLocation(loc);
        reading.setEvaluationDate(DATE);
        reading.setEventType(TargetType.SUNRISE);
        reading.setSnowDepthMetres(0.05);

        stubEmptyForecastScores();
        when(slotAtmosphereRepository.findInDateRange(DATE, WINDOW_END))
                .thenReturn(List.of(reading));

        InversionHotTopicStrategy strategy = new InversionHotTopicStrategy(reader, freshness);

        List<HotTopic> topics = strategy.detect(DATE, WINDOW_END);

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("readings and a component on the SAME key both survive the reader's join — "
            + "inversion fires off the READINGS score (Phase 2, V158), proving the join does not "
            + "let one sub-record crowd out the other even though Claude's Scores echo differs")
    void readingsAndComponent_sameKey_bothSurviveTheJoin() {
        LocationEntity loc = location(3L, "Great Gable");
        // Claude's own echo (Scores.inversion) — deliberately a DIFFERENT value from the
        // calculator's reading below, to prove the strategy reads the readings side and the two
        // sub-records are never confused with each other.
        ForecastScoreEntity score = new ForecastScoreEntity();
        score.setForecastType(ForecastType.INVERSION);
        score.setLocation(loc);
        score.setEvaluationDate(DATE);
        score.setEventType(TargetType.SUNRISE);
        score.setScore(7);
        score.setSummary("MODERATE");
        score.setEvaluatedAt(Instant.now(CLOCK));

        SlotAtmosphereEntity reading = new SlotAtmosphereEntity();
        reading.setLocation(loc);
        reading.setEvaluationDate(DATE);
        reading.setEventType(TargetType.SUNRISE);
        reading.setDust(new BigDecimal("55.00"));
        reading.setInversionScore(9.0);
        // V158 round 4: a fresh row must be marked scored, or effectiveInversionScore() treats
        // it as pre-column and falls back to Claude's (here, disagreeing) echo instead.
        reading.setInversionScored(true);

        when(forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), DATE, WINDOW_END)).thenReturn(List.of(score));
        when(forecastScoreRepository.findComponentsByType(
                ForecastType.BLUEBELL.getId(), DATE, WINDOW_END)).thenReturn(List.of());
        when(slotAtmosphereRepository.findInDateRange(DATE, WINDOW_END))
                .thenReturn(List.of(reading));
        when(freshness.isAhead(any(), any(), any())).thenReturn(true);

        // Sanity: the reader itself folds both surfaces into ONE composite for this key, with
        // both sub-records populated (never one crowding out the other) — Scores carries Claude's
        // MODERATE echo, Readings carries the calculator's STRONG score, and both survive intact.
        assertThat(reader.read(DATE, WINDOW_END)).hasSize(1);
        assertThat(reader.read(DATE, WINDOW_END).get(0).scores().inversion()).isEqualTo(7);
        assertThat(reader.read(DATE, WINDOW_END).get(0).readings().inversionScore()).isEqualTo(9.0);
        assertThat(reader.read(DATE, WINDOW_END).get(0).readings().dust())
                .isEqualByComparingTo("55.00");

        InversionHotTopicStrategy strategy = new InversionHotTopicStrategy(reader, freshness);

        List<HotTopic> topics = strategy.detect(DATE, WINDOW_END);

        // Fires off the READINGS score (9), never the Scores echo (7) — Phase 2 moved this
        // detector off Claude's echo entirely.
        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).facts()).anySatisfy(
                fact -> assertThat(fact.value()).contains("9/10"));
    }
}
