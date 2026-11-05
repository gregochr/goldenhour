package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TopicDailyLogEntity;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TopicDailyLogRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Data-quality edges of {@link TopicDailyLogJob}: readings whose location has no region (or a region with no id)
 * are skipped by every topic, incomplete readings are skipped, and a topic's outcome does not depend on the others.
 */
class TopicDailyLogJobMissingDataTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-03-06T04:40:00Z"), ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = LocalDate.of(2027, 3, 5);
    private static final List<TargetType> EVENTS = List.of(TargetType.SUNRISE, TargetType.SUNSET);

    private ForecastEvaluationRepository forecastEvaluationRepository;
    private SlotAtmosphereRepository slotAtmosphereRepository;
    private TideExtremeRepository tideExtremeRepository;
    private TideService tideService;
    private LunarPhaseService lunarPhaseService;
    private LocationRepository locationRepository;
    private AuroraForecastResultRepository auroraForecastResultRepository;
    private NlcClarityService nlcClarityService;
    private RegionService regionService;
    private TopicDailyLogRepository topicDailyLogRepository;
    private TopicDailyLogJob job;

    private final RegionEntity real = RegionEntity.builder().id(1L).name("Northumberland").build();
    private final RegionEntity noId = RegionEntity.builder().name("Unsaved").build();

    @BeforeEach
    void setUp() {
        forecastEvaluationRepository = mock(ForecastEvaluationRepository.class);
        slotAtmosphereRepository = mock(SlotAtmosphereRepository.class);
        tideExtremeRepository = mock(TideExtremeRepository.class);
        tideService = mock(TideService.class);
        lunarPhaseService = mock(LunarPhaseService.class);
        locationRepository = mock(LocationRepository.class);
        auroraForecastResultRepository = mock(AuroraForecastResultRepository.class);
        nlcClarityService = mock(NlcClarityService.class);
        regionService = mock(RegionService.class);
        topicDailyLogRepository = mock(TopicDailyLogRepository.class);
        job = new TopicDailyLogJob(forecastEvaluationRepository, slotAtmosphereRepository, tideExtremeRepository,
                tideService, lunarPhaseService, locationRepository, auroraForecastResultRepository,
                nlcClarityService, regionService, topicDailyLogRepository, mock(DynamicSchedulerService.class),
                CLOCK);
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(YESTERDAY, EVENTS)).thenReturn(List.of());
        when(slotAtmosphereRepository.findInDateRange(YESTERDAY, YESTERDAY)).thenReturn(List.of());
        when(locationRepository.findCoastalLocations()).thenReturn(List.of());
        when(auroraForecastResultRepository.findByForecastDateAndSimulatedFalseFetchingLocation(YESTERDAY))
                .thenReturn(List.of());
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(false);
        when(regionService.findAll()).thenReturn(List.of());
    }

    private LocationEntity located(Long id, RegionEntity region) {
        return LocationEntity.builder().id(id).name("Loc " + id).region(region).build();
    }

    private List<TopicDailyLogEntity> saved(String topicType) {
        ArgumentCaptor<TopicDailyLogEntity> captor = ArgumentCaptor.forClass(TopicDailyLogEntity.class);
        verify(topicDailyLogRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().filter(e -> e.getTopicType().equals(topicType)).toList();
    }

    private SlotAtmosphereEntity slot(LocationEntity location, TargetType event) {
        SlotAtmosphereEntity row = new SlotAtmosphereEntity();
        row.setLocation(location);
        row.setEvaluationDate(YESTERDAY);
        row.setEventType(event);
        return row;
    }

    @Test
    @DisplayName("dust and storm-surge rows with no region, or a region without an id, are not logged; "
            + "a surge with no total still logs its region")
    void dustAndSurge_rowsWithoutRegion_skipped() {
        ForecastEvaluationEntity orphan = new ForecastEvaluationEntity();
        orphan.setLocation(located(10L, null));
        ForecastEvaluationEntity unsaved = new ForecastEvaluationEntity();
        unsaved.setLocation(located(11L, noId));
        ForecastEvaluationEntity noLocation = new ForecastEvaluationEntity();
        ForecastEvaluationEntity ok = new ForecastEvaluationEntity();
        ok.setLocation(located(12L, real));
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(YESTERDAY, EVENTS))
                .thenReturn(List.of(orphan, unsaved, noLocation, ok));

        job.runScheduled();

        List<TopicDailyLogEntity> dust = saved(TopicDailyLogJob.TYPE_DUST);
        assertThat(dust).hasSize(1);
        assertThat(dust.get(0).getRegionId()).isEqualTo(1L);
        assertThat(dust.get(0).isPresent()).isFalse();
    }

    @Test
    @DisplayName("inversion, mist and snow readings with no region are skipped; incomplete mist readings too")
    void slotTopics_rowsWithoutRegion_skipped() {
        SlotAtmosphereEntity inversionOrphan = slot(located(10L, null), TargetType.SUNRISE);
        inversionOrphan.setInversionScored(true);
        inversionOrphan.setInversionScore(9.0);
        SlotAtmosphereEntity inversionOk = slot(located(12L, real), TargetType.SUNRISE);
        inversionOk.setInversionScored(true);
        inversionOk.setInversionScore(9.0);
        SlotAtmosphereEntity inversionNoId = slot(located(13L, noId), TargetType.SUNRISE);
        inversionNoId.setInversionScored(true);
        inversionNoId.setInversionScore(9.0);
        SlotAtmosphereEntity inversionUnscored = slot(located(14L, real), TargetType.SUNRISE);

        SlotAtmosphereEntity mistOrphan = slot(located(10L, null), TargetType.SUNSET);
        mistOrphan.setMistScored(true);
        mistOrphan.setMistScore(7.0);
        mistOrphan.setMistPotential("LIKELY");
        SlotAtmosphereEntity mistNoScore = slot(located(15L, real), TargetType.SUNSET);
        mistNoScore.setMistScored(true);
        mistNoScore.setMistPotential("LIKELY");
        SlotAtmosphereEntity mistNoPotential = slot(located(16L, real), TargetType.SUNSET);
        mistNoPotential.setMistScored(true);
        mistNoPotential.setMistScore(7.0);
        SlotAtmosphereEntity mistNoId = slot(located(17L, noId), TargetType.SUNSET);
        mistNoId.setMistScored(true);
        mistNoId.setMistScore(7.0);
        mistNoId.setMistPotential("LIKELY");
        SlotAtmosphereEntity mistOk = slot(located(18L, real), TargetType.SUNSET);
        mistOk.setMistScored(true);
        mistOk.setMistScore(7.0);
        mistOk.setMistPotential("LIKELY");

        SlotAtmosphereEntity snowOrphan = slot(located(10L, null), TargetType.SUNRISE);
        snowOrphan.setSnowDepthMetres(0.5);
        SlotAtmosphereEntity snowNoId = slot(located(19L, noId), TargetType.SUNRISE);
        snowNoId.setSnowDepthMetres(0.5);
        when(slotAtmosphereRepository.findInDateRange(YESTERDAY, YESTERDAY)).thenReturn(List.of(
                inversionOrphan, inversionOk, inversionNoId, inversionUnscored, mistOrphan, mistNoScore,
                mistNoPotential, mistNoId, mistOk, snowOrphan, snowNoId));

        job.runScheduled();

        assertThat(saved(TopicDailyLogJob.TYPE_INVERSION)).hasSize(1);
        assertThat(saved(TopicDailyLogJob.TYPE_INVERSION).get(0).getRegionId()).isEqualTo(1L);
        assertThat(saved(TopicDailyLogJob.TYPE_INVERSION).get(0).isPresent()).isTrue();
        assertThat(saved(TopicDailyLogJob.TYPE_MIST)).hasSize(1);
        assertThat(saved(TopicDailyLogJob.TYPE_MIST).get(0).getRegionId()).isEqualTo(1L);
        assertThat(saved(TopicDailyLogJob.TYPE_MIST).get(0).isPresent()).isTrue();
        // Snow takes every row with a region whatever its event type; the two region-less rows add nothing.
        assertThat(saved(TopicDailyLogJob.TYPE_SNOW)).hasSize(1);
        assertThat(saved(TopicDailyLogJob.TYPE_SNOW).get(0).getRegionId()).isEqualTo(1L);
        assertThat(saved(TopicDailyLogJob.TYPE_SNOW).get(0).isPresent()).isFalse();
    }

    @Test
    @DisplayName("tide readings with no time or height, a region-less location, or no spring threshold are skipped")
    void tides_incompleteReadings_skipped() {
        LocationEntity withRegion = located(20L, real);
        LocationEntity noRegion = located(21L, null);
        LocationEntity noThreshold = located(22L, RegionEntity.builder().id(2L).name("Cumbria").build());
        LocationEntity noStats = located(23L, RegionEntity.builder().id(3L).name("Durham").build());
        LocationEntity unsaved = LocationEntity.builder().name("No id").region(real).build();
        when(locationRepository.findCoastalLocations())
                .thenReturn(List.of(withRegion, noRegion, noThreshold, noStats, unsaved));
        LocalDateTime inDay = YESTERDAY.atTime(6, 0);
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                Set.of(20L, 21L, 22L, 23L), TideExtremeType.HIGH, YESTERDAY.atStartOfDay(),
                YESTERDAY.plusDays(1).atStartOfDay())).thenReturn(List.of(
                        tideExtreme(20L, inDay, null),
                        tideExtreme(20L, null, "5.0"),
                        tideExtreme(21L, inDay, "5.0"),
                        tideExtreme(22L, inDay, "5.0"),
                        tideExtreme(23L, inDay, "5.0"),
                        tideExtreme(20L, YESTERDAY.plusDays(1).atTime(6, 0), "9.0"),
                        tideExtreme(20L, inDay, "4.5")));
        when(tideService.getTideStats(20L)).thenReturn(Optional.of(stats("4.0")));
        when(tideService.getTideStats(22L)).thenReturn(Optional.of(stats(null)));
        when(tideService.getTideStats(23L)).thenReturn(Optional.empty());
        when(lunarPhaseService.nearestSyzygyIsPerigean(YESTERDAY)).thenReturn(false);

        job.runScheduled();

        List<TopicDailyLogEntity> spring = saved(TopicDailyLogJob.TYPE_SPRING_TIDE);
        assertThat(spring).hasSize(1);
        assertThat(spring.get(0).getRegionId()).isEqualTo(1L);
        assertThat(spring.get(0).getIntensity()).isEqualByComparingTo(new BigDecimal("4.5"));
    }

    private static TideExtremeEntity tideExtreme(long locationId, LocalDateTime time, String metres) {
        return TideExtremeEntity.builder().locationId(locationId).eventTime(time)
                .heightMetres(metres == null ? null : new BigDecimal(metres)).type(TideExtremeType.HIGH).build();
    }

    private static TideStats stats(String springThreshold) {
        return new TideStats(null, null, null, null, 900L, null, null, null, null, 0L, null,
                springThreshold == null ? null : new BigDecimal(springThreshold), null, 0L);
    }

    @Test
    @DisplayName("aurora rows with no region are skipped, and an unknown or missing alert level is not alert-worthy")
    void aurora_rowsWithoutRegion_skipped_andUnknownLevelNotAlertWorthy() {
        AuroraForecastResultEntity orphan = auroraRow(located(10L, null), "RED");
        AuroraForecastResultEntity unsaved = auroraRow(located(11L, noId), "RED");
        AuroraForecastResultEntity noLevel = auroraRow(located(12L, real), null);
        AuroraForecastResultEntity unknownLevel = auroraRow(located(13L, real), "PURPLE");
        when(auroraForecastResultRepository.findByForecastDateAndSimulatedFalseFetchingLocation(YESTERDAY))
                .thenReturn(List.of(orphan, unsaved, noLevel, unknownLevel));

        job.runScheduled();

        List<TopicDailyLogEntity> aurora = saved(TopicDailyLogJob.TYPE_AURORA);
        assertThat(aurora).hasSize(1);
        assertThat(aurora.get(0).getRegionId()).isEqualTo(1L);
        assertThat(aurora.get(0).isPresent()).isFalse();
    }

    private static AuroraForecastResultEntity auroraRow(LocationEntity location, String level) {
        return AuroraForecastResultEntity.builder().location(location).alertLevel(level).build();
    }

    @Test
    @DisplayName("NLC logs only regions that have an id")
    void nlc_regionsWithoutId_skipped() {
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);
        when(regionService.findAll()).thenReturn(List.of(noId, real));

        job.runScheduled();

        List<TopicDailyLogEntity> nlc = saved(TopicDailyLogJob.TYPE_NLC);
        assertThat(nlc).hasSize(1);
        assertThat(nlc.get(0).getRegionId()).isEqualTo(1L);
        assertThat(nlc.get(0).isPresent()).isTrue();
    }
}
