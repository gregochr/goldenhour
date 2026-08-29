package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.StormSurgeDetails;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TopicDailyLogEntity;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TopicDailyLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TopicDailyLogJob}.
 *
 * <p>{@link #job} exposes only {@code runScheduled()}, so every test exercises all eight topics —
 * the {@code @BeforeEach} default-stubs every source to an empty/absent answer so an
 * out-of-scope topic contributes no rows, and each test overrides only the source(s) it is
 * pinning a behaviour against.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TopicDailyLogJob")
class TopicDailyLogJobTest {

    /** 04:40 UTC on 6 March — GMT in London (before the BST changeover), so no calendar drift. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-03-06T04:40:00Z"), ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = LocalDate.of(2027, 3, 5);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @Mock
    private ForecastScoreRepository forecastScoreRepository;

    @Mock
    private SurvivorAtmosphereRepository survivorAtmosphereRepository;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private TideService tideService;

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private AuroraForecastResultRepository auroraForecastResultRepository;

    @Mock
    private NlcClarityService nlcClarityService;

    @Mock
    private RegionService regionService;

    @Mock
    private TopicDailyLogRepository topicDailyLogRepository;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private TopicDailyLogJob job;

    @BeforeEach
    void setUp() {
        lenient().when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(any(), anyCollection()))
                .thenReturn(List.of());
        lenient().when(forecastScoreRepository.findComponentsByType(anyLong(), any(), any()))
                .thenReturn(List.of());
        lenient().when(survivorAtmosphereRepository.findInDateRange(any(), any())).thenReturn(List.of());
        lenient().when(locationRepository.findCoastalLocations()).thenReturn(List.of());
        lenient().when(auroraForecastResultRepository.findByForecastDateFetchingLocation(any()))
                .thenReturn(List.of());
        lenient().when(nlcClarityService.isNlcSeason(any())).thenReturn(false);
        lenient().when(regionService.findAll()).thenReturn(List.of());
        // Default to a non-perigean date (SPRING label) unless a test says otherwise.
        lenient().when(lunarPhaseService.nearestSyzygyIsPerigean(any())).thenReturn(false);

        job = new TopicDailyLogJob(forecastEvaluationRepository, forecastScoreRepository,
                survivorAtmosphereRepository, tideExtremeRepository, tideService, lunarPhaseService,
                locationRepository, auroraForecastResultRepository, nlcClarityService, regionService,
                topicDailyLogRepository, dynamicSchedulerService, CLOCK);
    }

    private static RegionEntity region(long id) {
        return RegionEntity.builder().id(id).name("Region " + id).build();
    }

    private static LocationEntity locationWithRegion(long id, RegionEntity region) {
        return LocationEntity.builder().id(id).name("Loc " + id).region(region).build();
    }

    private List<TopicDailyLogEntity> savedRowsOfType(String topicType) {
        ArgumentCaptor<TopicDailyLogEntity> captor = ArgumentCaptor.forClass(TopicDailyLogEntity.class);
        verify(topicDailyLogRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().filter(e -> e.getTopicType().equals(topicType)).toList();
    }

    // ---------------------------------------------------------------- registration

    @Test
    @DisplayName("registers the sweep itself against the V151-seeded job key — running it is not a no-op")
    void registerJob_registersUnderSeededKey() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(regionService.findAll()).thenReturn(List.of(region));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);

        job.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(dynamicSchedulerService).registerJobTarget(eq("topic_daily_log"), target.capture());
        target.getValue().run();

        assertThat(savedRowsOfType("NLC")).hasSize(1);
    }

    // ---------------------------------------------------------------- DUST / STORM_SURGE

    @Test
    @DisplayName("DUST fires when AOD clears the elevated threshold and records the max AOD seen")
    void dust_firesOnElevatedAod() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        ForecastEvaluationEntity row1 = ForecastEvaluationEntity.builder()
                .location(location).aerosolOpticalDepth(new BigDecimal("0.35")).build();
        ForecastEvaluationEntity row2 = ForecastEvaluationEntity.builder()
                .location(location).aerosolOpticalDepth(new BigDecimal("0.55")).build();
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenReturn(List.of(row1, row2));

        job.runScheduled();

        List<TopicDailyLogEntity> dust = savedRowsOfType("DUST");
        assertThat(dust).hasSize(1);
        assertThat(dust.get(0).isPresent()).isTrue();
        assertThat(dust.get(0).getRegionId()).isEqualTo(1L);
        assertThat(dust.get(0).getIntensity()).isEqualByComparingTo("0.55");
        assertThat(dust.get(0).getLandedOnWindow()).isTrue();
    }

    @Test
    @DisplayName("DUST still logs a present=false row for a region that was measured but not elevated")
    void dust_belowThreshold_logsFalsePresence() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                .location(location).aerosolOpticalDepth(new BigDecimal("0.10")).build();
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenReturn(List.of(row));

        job.runScheduled();

        List<TopicDailyLogEntity> dust = savedRowsOfType("DUST");
        assertThat(dust).hasSize(1);
        assertThat(dust.get(0).isPresent()).isFalse();
        assertThat(dust.get(0).getLandedOnWindow()).isNull();
    }

    @Test
    @DisplayName("a row whose location has no region contributes nothing")
    void dust_locationWithNoRegion_isSkipped() {
        LocationEntity location = locationWithRegion(10L, null);
        ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                .location(location).aerosolOpticalDepth(new BigDecimal("0.9")).build();
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenReturn(List.of(row));

        job.runScheduled();

        assertThat(savedRowsOfType("DUST")).isEmpty();
    }

    @Test
    @DisplayName("STORM_SURGE fires on a HIGH risk row and is silent for an inland (surge-null) row")
    void stormSurge_firesOnHighRisk_inlandContributesNothing() {
        RegionEntity coastalRegion = region(1L);
        RegionEntity inlandRegion = region(2L);
        LocationEntity coastal = locationWithRegion(10L, coastalRegion);
        LocationEntity inland = locationWithRegion(20L, inlandRegion);
        ForecastEvaluationEntity coastalRow = ForecastEvaluationEntity.builder()
                .location(coastal)
                .surge(StormSurgeDetails.builder().riskLevel("HIGH").totalMetres(0.72).build())
                .build();
        ForecastEvaluationEntity inlandRow = ForecastEvaluationEntity.builder()
                .location(inland).surge(null).build();
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenReturn(List.of(coastalRow, inlandRow));

        job.runScheduled();

        List<TopicDailyLogEntity> surge = savedRowsOfType("STORM_SURGE");
        assertThat(surge).hasSize(1);
        assertThat(surge.get(0).getRegionId()).isEqualTo(1L);
        assertThat(surge.get(0).isPresent()).isTrue();
        assertThat(surge.get(0).getIntensity()).isEqualByComparingTo("0.72");
    }

    @Test
    @DisplayName("STORM_SURGE below HIGH is logged present=false, not omitted")
    void stormSurge_moderateRisk_logsFalsePresence() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        ForecastEvaluationEntity row = ForecastEvaluationEntity.builder()
                .location(location)
                .surge(StormSurgeDetails.builder().riskLevel("MODERATE").totalMetres(0.4).build())
                .build();
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenReturn(List.of(row));

        job.runScheduled();

        List<TopicDailyLogEntity> surge = savedRowsOfType("STORM_SURGE");
        assertThat(surge).hasSize(1);
        assertThat(surge.get(0).isPresent()).isFalse();
    }

    // ---------------------------------------------------------------- INVERSION

    @Test
    @DisplayName("INVERSION fires at the STRONG band on a SUNRISE row and ignores a SUNSET row entirely")
    void inversion_firesOnStrongSunriseOnly() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        ForecastScoreEntity sunrise = new ForecastScoreEntity();
        sunrise.setLocation(location);
        sunrise.setEventType(TargetType.SUNRISE);
        sunrise.setScore(9);
        ForecastScoreEntity sunset = new ForecastScoreEntity();
        sunset.setLocation(location);
        sunset.setEventType(TargetType.SUNSET);
        sunset.setScore(10);
        when(forecastScoreRepository.findComponentsByType(anyLong(), eq(YESTERDAY), eq(YESTERDAY)))
                .thenReturn(List.of(sunrise, sunset));

        job.runScheduled();

        List<TopicDailyLogEntity> inversion = savedRowsOfType("INVERSION");
        assertThat(inversion).hasSize(1);
        assertThat(inversion.get(0).isPresent()).isTrue();
        // The SUNSET row's score of 10 must not leak into the max even though it is the higher figure.
        assertThat(inversion.get(0).getIntensity()).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("a MODERATE sunrise score (below STRONG) logs present=false")
    void inversion_moderateScore_logsFalsePresence() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        ForecastScoreEntity sunrise = new ForecastScoreEntity();
        sunrise.setLocation(location);
        sunrise.setEventType(TargetType.SUNRISE);
        sunrise.setScore(7);
        when(forecastScoreRepository.findComponentsByType(anyLong(), eq(YESTERDAY), eq(YESTERDAY)))
                .thenReturn(List.of(sunrise));

        job.runScheduled();

        List<TopicDailyLogEntity> inversion = savedRowsOfType("INVERSION");
        assertThat(inversion).hasSize(1);
        assertThat(inversion.get(0).isPresent()).isFalse();
    }

    // ---------------------------------------------------------------- SNOW

    @Test
    @DisplayName("SNOW fires at or above the 2 cm lying-snow threshold")
    void snow_firesAtThreshold() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        SurvivorAtmosphereEntity row = new SurvivorAtmosphereEntity();
        row.setLocation(location);
        row.setSnowDepthMetres(0.03);
        when(survivorAtmosphereRepository.findInDateRange(YESTERDAY, YESTERDAY)).thenReturn(List.of(row));

        job.runScheduled();

        List<TopicDailyLogEntity> snow = savedRowsOfType("SNOW");
        assertThat(snow).hasSize(1);
        assertThat(snow.get(0).isPresent()).isTrue();
        assertThat(snow.get(0).getIntensity()).isEqualByComparingTo("0.03");
    }

    @Test
    @DisplayName("a trace below the lying-snow threshold logs present=false")
    void snow_belowThreshold_logsFalsePresence() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        SurvivorAtmosphereEntity row = new SurvivorAtmosphereEntity();
        row.setLocation(location);
        row.setSnowDepthMetres(0.01);
        when(survivorAtmosphereRepository.findInDateRange(YESTERDAY, YESTERDAY)).thenReturn(List.of(row));

        job.runScheduled();

        assertThat(savedRowsOfType("SNOW").get(0).isPresent()).isFalse();
    }

    // ---------------------------------------------------------------- SPRING_TIDE / KING_TIDE

    private static TideStats stats(BigDecimal springThreshold) {
        return new TideStats(null, null, null, null, 700L, null, null, null, null, 0L, null, springThreshold, null, 0L);
    }

    @Test
    @DisplayName("a qualifying high water on a perigean date logs KING_TIDE, never SPRING_TIDE")
    void tides_perigeanDate_firesKingTideOnly() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 5, 6, 0))
                        .heightMetres(new BigDecimal("5.5"))
                        .build()));
        when(tideService.getTideStats(10L)).thenReturn(Optional.of(stats(new BigDecimal("4.0"))));
        when(lunarPhaseService.nearestSyzygyIsPerigean(YESTERDAY)).thenReturn(true);

        job.runScheduled();

        List<TopicDailyLogEntity> king = savedRowsOfType("KING_TIDE");
        assertThat(king).hasSize(1);
        assertThat(king.get(0).isPresent()).isTrue();
        assertThat(king.get(0).getIntensity()).isEqualByComparingTo("5.5");
        assertThat(savedRowsOfType("SPRING_TIDE")).isEmpty();
    }

    @Test
    @DisplayName("a qualifying high water on a non-perigean date logs SPRING_TIDE, never KING_TIDE")
    void tides_nonPerigeanDate_firesSpringTideOnly() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 5, 6, 0))
                        .heightMetres(new BigDecimal("4.5"))
                        .build()));
        when(tideService.getTideStats(10L)).thenReturn(Optional.of(stats(new BigDecimal("4.0"))));
        when(lunarPhaseService.nearestSyzygyIsPerigean(YESTERDAY)).thenReturn(false);

        job.runScheduled();

        assertThat(savedRowsOfType("SPRING_TIDE").get(0).isPresent()).isTrue();
        assertThat(savedRowsOfType("KING_TIDE")).isEmpty();
    }

    @Test
    @DisplayName("a huge non-perigean tide stays SPRING_TIDE — never OR the height test into the "
            + "king label (backend/AGENTS.md §2)")
    void tides_hugeButNonPerigean_staysSpringNeverKing() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        // A height that would clear even a demanding P95-style threshold, on a date the moon does
        // NOT call perigean — the exact "August 2026" scenario CLAUDE.md/AGENTS.md record: a big
        // spring tide clearing P95 without being perigean must never print as KING.
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 5, 6, 0))
                        .heightMetres(new BigDecimal("9.9"))
                        .build()));
        when(tideService.getTideStats(10L)).thenReturn(Optional.of(stats(new BigDecimal("4.0"))));
        when(lunarPhaseService.nearestSyzygyIsPerigean(YESTERDAY)).thenReturn(false);

        job.runScheduled();

        assertThat(savedRowsOfType("SPRING_TIDE").get(0).isPresent()).isTrue();
        assertThat(savedRowsOfType("KING_TIDE")).isEmpty();
    }

    @Test
    @DisplayName("a high water at or below the spring threshold produces no row for either type")
    void tides_belowSpringThreshold_producesNoRow() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 5, 6, 0))
                        .heightMetres(new BigDecimal("3.5"))
                        .build()));
        when(tideService.getTideStats(10L)).thenReturn(Optional.of(stats(new BigDecimal("4.0"))));

        job.runScheduled();

        assertThat(savedRowsOfType("SPRING_TIDE")).isEmpty();
        assertThat(savedRowsOfType("KING_TIDE")).isEmpty();
    }

    @Test
    @DisplayName("an extreme whose London-local date is not the requested day is excluded even though "
            + "the repository returned it")
    void tides_extremeOnAdjacentLocalDay_isExcluded() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        // 00:15 UTC on 6 March is 00:15 London (GMT, before the BST changeover) — the day AFTER
        // YESTERDAY (5 March). The repository is mocked to hand this back regardless of the real
        // query window, so this pins the job's own defensive `local.equals(date)` filter — the one
        // piece of `logTides` that isn't a verbatim copy of `TideSizeIndex`'s query construction.
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 6, 0, 15))
                        .heightMetres(new BigDecimal("6.0"))
                        .build()));
        when(tideService.getTideStats(10L))
                .thenReturn(Optional.of(stats(new BigDecimal("4.0"))));

        job.runScheduled();

        assertThat(savedRowsOfType("SPRING_TIDE")).isEmpty();
        assertThat(savedRowsOfType("KING_TIDE")).isEmpty();
    }

    @Test
    @DisplayName("a location with no usable stats yet contributes no row")
    void tides_noUsableStats_producesNothing() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(location));
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(TideExtremeEntity.builder()
                        .locationId(10L)
                        .type(TideExtremeType.HIGH)
                        .eventTime(LocalDateTime.of(2027, 3, 5, 6, 0))
                        .heightMetres(new BigDecimal("5.5"))
                        .build()));
        when(tideService.getTideStats(10L)).thenReturn(Optional.empty());

        job.runScheduled();

        assertThat(savedRowsOfType("SPRING_TIDE")).isEmpty();
        assertThat(savedRowsOfType("KING_TIDE")).isEmpty();
    }

    // ---------------------------------------------------------------- AURORA

    @Test
    @DisplayName("AURORA fires on an alert-worthy stored result and is silent when no result was ever stored")
    void aurora_firesOnAlertWorthyResult_silentOtherwise() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        AuroraForecastResultEntity result = AuroraForecastResultEntity.builder()
                .location(location).forecastDate(YESTERDAY).alertLevel("MODERATE").stars(4).build();
        when(auroraForecastResultRepository.findByForecastDateFetchingLocation(YESTERDAY))
                .thenReturn(List.of(result));

        job.runScheduled();

        List<TopicDailyLogEntity> aurora = savedRowsOfType("AURORA");
        assertThat(aurora).hasSize(1);
        assertThat(aurora.get(0).isPresent()).isTrue();
        assertThat(aurora.get(0).getIntensity()).isNull();
        assertThat(aurora.get(0).getLandedOnWindow()).isNull();
    }

    @Test
    @DisplayName("a QUIET stored result logs present=false rather than being omitted")
    void aurora_quietResult_logsFalsePresence() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        AuroraForecastResultEntity result = AuroraForecastResultEntity.builder()
                .location(location).forecastDate(YESTERDAY).alertLevel("QUIET").stars(1).build();
        when(auroraForecastResultRepository.findByForecastDateFetchingLocation(YESTERDAY))
                .thenReturn(List.of(result));

        job.runScheduled();

        assertThat(savedRowsOfType("AURORA").get(0).isPresent()).isFalse();
    }

    @Test
    @DisplayName("an unrecognised alert level string does not crash the run and reads as not-worthy")
    void aurora_garbageAlertLevel_doesNotCrash() {
        RegionEntity region = region(1L);
        LocationEntity location = locationWithRegion(10L, region);
        AuroraForecastResultEntity result = AuroraForecastResultEntity.builder()
                .location(location).forecastDate(YESTERDAY).alertLevel("NOT_A_LEVEL").stars(1).build();
        when(auroraForecastResultRepository.findByForecastDateFetchingLocation(YESTERDAY))
                .thenReturn(List.of(result));

        job.runScheduled();

        assertThat(savedRowsOfType("AURORA").get(0).isPresent()).isFalse();
    }

    // ---------------------------------------------------------------- NLC

    @Test
    @DisplayName("NLC logs the same season answer for every region on the roster")
    void nlc_logsUniformlyAcrossRegions() {
        RegionEntity lakes = region(1L);
        RegionEntity peak = region(2L);
        when(regionService.findAll()).thenReturn(List.of(lakes, peak));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);

        job.runScheduled();

        List<TopicDailyLogEntity> nlc = savedRowsOfType("NLC");
        assertThat(nlc).hasSize(2);
        assertThat(nlc).allSatisfy(row -> assertThat(row.isPresent()).isTrue());
        assertThat(nlc).allSatisfy(row -> assertThat(row.getIntensity()).isNull());
    }

    @Test
    @DisplayName("out of season, NLC still logs present=false rather than nothing")
    void nlc_outOfSeason_logsFalsePresence() {
        when(regionService.findAll()).thenReturn(List.of(region(1L)));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(false);

        job.runScheduled();

        assertThat(savedRowsOfType("NLC").get(0).isPresent()).isFalse();
    }

    // ---------------------------------------------------------------- cross-cutting

    @Test
    @DisplayName("an already-logged key is skipped without a second write")
    void persist_existingRow_isSkipped() {
        when(regionService.findAll()).thenReturn(List.of(region(1L)));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);
        when(topicDailyLogRepository.existsByTopicTypeAndLogDateAndRegionId("NLC", YESTERDAY, 1L))
                .thenReturn(true);

        job.runScheduled();

        assertThat(savedRowsOfType("NLC")).isEmpty();
    }

    @Test
    @DisplayName("a unique-constraint collision on one region (a concurrent run winning the race) "
            + "does not abort the rest of that topic's regions, and does not propagate")
    void persist_uniqueConstraintCollision_doesNotAbandonOtherRegions() {
        when(regionService.findAll()).thenReturn(List.of(region(1L), region(2L)));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);
        when(topicDailyLogRepository.save(argThat(e -> e != null && e.getRegionId() == 1L)))
                .thenThrow(new DataIntegrityViolationException("uq_topic_daily_log"));

        // Reaching this line without an exception already proves the collision was caught, not
        // propagated up through runScheduled(); the verify below confirms the loop still reached
        // the region after the one that collided.
        job.runScheduled();

        verify(topicDailyLogRepository).save(
                argThat(e -> e != null && "NLC".equals(e.getTopicType()) && e.getRegionId() == 2L));
    }

    @Test
    @DisplayName("one topic's source failing does not stop the others from being logged")
    void oneTopicFailing_doesNotAbandonTheRest() {
        when(forecastEvaluationRepository.findByTargetDateAndTargetTypeIn(eq(YESTERDAY), anyCollection()))
                .thenThrow(new IllegalStateException("DB unavailable"));
        when(regionService.findAll()).thenReturn(List.of(region(1L)));
        when(nlcClarityService.isNlcSeason(YESTERDAY)).thenReturn(true);

        job.runScheduled();

        assertThat(savedRowsOfType("NLC")).hasSize(1);
    }
}
