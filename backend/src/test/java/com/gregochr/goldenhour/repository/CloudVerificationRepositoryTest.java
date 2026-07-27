package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CloudApproachDetails;
import com.gregochr.goldenhour.entity.CloudVerificationEntity;
import com.gregochr.goldenhour.entity.DirectionalCloudDetails;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudVerificationPair;
import com.gregochr.goldenhour.model.VerificationCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link CloudVerificationRepository}.
 *
 * <p>These exist mainly to execute the JPQL: both queries project through {@code @Embeddable}
 * paths ({@code e.directionalCloud.solarLow}, {@code e.cloudApproach.upwindDistanceKm}) into
 * record constructors, and a wrong path or arity compiles fine and only fails at runtime.
 */
@DataJpaTest
class CloudVerificationRepositoryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Autowired
    private CloudVerificationRepository repository;

    @Autowired
    private ForecastEvaluationRepository evaluationRepository;

    @Autowired
    private LocationRepository locationRepository;

    private LocationEntity durham;

    @BeforeEach
    void setUp() {
        durham = locationRepository.save(LocationEntity.builder()
                .name("Durham UK")
                .lat(54.7753)
                .lon(-1.5849)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build());
    }

    @Test
    @DisplayName("findUnverified returns evaluations that have directional data and no verification")
    void findUnverified_returnsUnverifiedCandidates() {
        ForecastEvaluationEntity evaluation = evaluationRepository.save(evaluation(DATE, 30, true));

        List<VerificationCandidate> candidates =
                repository.findUnverified(DATE.plusDays(1), Limit.of(10));

        assertThat(candidates).hasSize(1);
        VerificationCandidate candidate = candidates.getFirst();
        assertThat(candidate.evaluationId()).isEqualTo(evaluation.getId());
        assertThat(candidate.lat()).isEqualTo(54.7753);
        assertThat(candidate.azimuthDeg()).isEqualTo(250);
        assertThat(candidate.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("findUnverified excludes already-verified evaluations, so backfill is resumable")
    void findUnverified_excludesVerified() {
        ForecastEvaluationEntity evaluation = evaluationRepository.save(evaluation(DATE, 30, true));
        repository.save(verification(evaluation.getId(), 80, 55, 40));

        assertThat(repository.findUnverified(DATE.plusDays(1), Limit.of(10))).isEmpty();
    }

    @Test
    @DisplayName("findUnverified excludes rows past the cutoff and rows without directional data")
    void findUnverified_excludesIneligibleRows() {
        // Too recent for the archive.
        evaluationRepository.save(evaluation(DATE.plusDays(10), 30, true));
        // No directional reading to compare against.
        evaluationRepository.save(evaluation(DATE, null, true));

        assertThat(repository.findUnverified(DATE.plusDays(1), Limit.of(10))).isEmpty();
    }

    @Test
    @DisplayName("findUnverified honours the limit so one pass is bounded")
    void findUnverified_honoursLimit() {
        evaluationRepository.save(evaluation(DATE, 30, true));
        evaluationRepository.save(evaluation(DATE.minusDays(1), 40, true));
        evaluationRepository.save(evaluation(DATE.minusDays(2), 50, true));

        assertThat(repository.findUnverified(DATE.plusDays(1), Limit.of(2))).hasSize(2);
    }

    @Test
    @DisplayName("deleteBlankVerifications frees masked evaluations without touching real ones")
    void deleteBlankVerifications_returnsEvaluationsToTheCandidatePool() {
        ForecastEvaluationEntity blank = evaluationRepository.save(evaluation(DATE, 30, true));
        ForecastEvaluationEntity real =
                evaluationRepository.save(evaluation(DATE.minusDays(1), 40, true));
        // A row written during an outage: it records an attempt but carries no observation, and
        // because candidate selection is an anti-join it masks its evaluation permanently.
        repository.save(verification(blank.getId(), null, null, null));
        repository.save(verification(real.getId(), 80, 55, 40));

        assertThat(repository.findUnverified(DATE.plusDays(1), Limit.of(10))).isEmpty();

        assertThat(repository.deleteBlankVerifications()).isEqualTo(1);

        // The blank one is a candidate again; the real one stays verified.
        assertThat(repository.findUnverified(DATE.plusDays(1), Limit.of(10)))
                .extracting(VerificationCandidate::evaluationId)
                .containsExactly(blank.getId());
    }

    @Test
    @DisplayName("findVerifiedPairs projects both cloud claims and both veto triggers")
    void findVerifiedPairs_projectsClaimsAndTriggers() {
        ForecastEvaluationEntity evaluation = evaluationRepository.save(evaluation(DATE, 30, true));
        repository.save(verification(evaluation.getId(), 80, 55, 40));

        List<CloudVerificationPair> pairs = repository.findVerifiedPairs(DATE, DATE);

        assertThat(pairs).hasSize(1);
        CloudVerificationPair pair = pairs.getFirst();
        assertThat(pair.locationName()).isEqualTo("Durham UK");
        // Gap: forecast said 30% low cloud at the horizon, the archive says 80% — it closed.
        assertThat(pair.forecastGapLow()).isEqualTo(30);
        assertThat(pair.observedGapLow()).isEqualTo(80);
        assertThat(pair.gapError()).isEqualTo(-50);
        // Canvas: forecast 60/40 (stronger 60) vs observed 55/40 (stronger 55).
        assertThat(pair.canvasError()).isEqualTo(5);
        // Veto triggers came through the embeddable path.
        assertThat(pair.solarTrendBuilding()).isTrue();
        assertThat(pair.upwindCurrentLowCloud()).isEqualTo(70);
        assertThat(pair.vetoFired()).isTrue();
        assertThat(pair.upwindDistanceKm()).isEqualTo(200);
        assertThat(pair.upwindCapped()).isTrue();
        // 250 solar azimuth vs 240 wind-from = aligned flow.
        assertThat(pair.windSunAngle()).isEqualTo(10);
    }

    @Test
    @DisplayName("findVerifiedPairs and countVerifiedInWindow bound on the target date")
    void findVerifiedPairs_respectsWindow() {
        ForecastEvaluationEntity inside = evaluationRepository.save(evaluation(DATE, 30, true));
        repository.save(verification(inside.getId(), 80, 55, 40));
        ForecastEvaluationEntity outside =
                evaluationRepository.save(evaluation(DATE.plusDays(20), 30, true));
        repository.save(verification(outside.getId(), 10, 55, 40));

        assertThat(repository.findVerifiedPairs(DATE, DATE)).hasSize(1);
        assertThat(repository.countVerifiedInWindow(DATE, DATE)).isEqualTo(1);
        assertThat(repository.findVerifiedPairs(DATE, DATE.plusDays(30))).hasSize(2);
        assertThat(repository.countVerifiedInWindow(DATE, DATE.plusDays(30))).isEqualTo(2);
    }

    private ForecastEvaluationEntity evaluation(LocalDate date, Integer solarLow,
            boolean building) {
        return ForecastEvaluationEntity.builder()
                .locationLat(new BigDecimal("54.775300"))
                .locationLon(new BigDecimal("-1.584900"))
                .location(durham)
                .targetDate(date)
                .targetType(TargetType.SUNSET)
                .forecastRunAt(date.atTime(1, 0))
                .daysAhead(0)
                .solarEventTime(date.atTime(21, 37))
                .azimuthDeg(250)
                .windDirection(240)
                .midCloud(60)
                .highCloud(40)
                .rating(2)
                .evaluationModel(EvaluationModel.HAIKU)
                .directionalCloud(DirectionalCloudDetails.builder()
                        .solarLow(solarLow)
                        .build())
                .cloudApproach(CloudApproachDetails.builder()
                        .solarTrendBuilding(building)
                        .upwindCurrentLowCloud(70)
                        .upwindDistanceKm(200)
                        .build())
                .summary("Test evaluation.")
                .build();
    }

    private CloudVerificationEntity verification(Long evaluationId, Integer horizonLow,
            Integer observerMid, Integer observerHigh) {
        return CloudVerificationEntity.builder()
                .forecastEvaluationId(evaluationId)
                .horizonSampleLat(54.0)
                .horizonSampleLon(-3.0)
                .horizonLowCloud(horizonLow)
                .observerMidCloud(observerMid)
                .observerHighCloud(observerHigh)
                .observedAt(LocalDateTime.of(2026, 5, 10, 21, 0))
                .verifiedAt(LocalDateTime.of(2026, 5, 17, 9, 0))
                .build();
    }
}
