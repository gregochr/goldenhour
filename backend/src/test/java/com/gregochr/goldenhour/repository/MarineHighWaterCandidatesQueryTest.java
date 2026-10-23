package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.model.BigSeasCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database test (H2, schema from the entities) of
 * {@link MarineHighWaterRepository#findBigSeasCandidates}: the one big-seas query returns only the
 * rows of a current, enabled structure whose sample is fresh and whose high water is in range.
 */
@DataJpaTest
class MarineHighWaterCandidatesQueryTest {

    private static final LocalDateTime HW = LocalDateTime.of(2026, 12, 10, 12, 0);
    private static final Instant FRESH = Instant.parse("2026-12-10T05:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-12-09T06:00:00Z");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 12, 10, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 15, 0, 0);

    @Autowired
    private MarineHighWaterRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private LocationEntity location(String name, boolean enabled, CoastalStructure structure, Integer faces) {
        return entityManager.persist(LocationEntity.builder().name(name).lat(54.9).lon(-1.3).enabled(enabled)
                .coastalStructure(structure).structureFacesDeg(faces).structureLabel(name + " label")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
    }

    private void row(LocationEntity loc, LocalDateTime hw, Instant evaluatedAt) {
        entityManager.persist(MarineHighWaterEntity.builder().location(loc).hwTime(hw)
                .hwHeightMetres(new BigDecimal("5.100")).windowStart(hw.minusMinutes(90))
                .windowEnd(hw.plusMinutes(90)).peakTime(hw).waveHeightMetres(4.2).wavePeriodSeconds(9.5)
                .waveDirectionDegrees(80).evaluatedAt(evaluatedAt).build());
    }

    @Test
    @DisplayName("an enabled structure's fresh in-range row comes back with its structure fields joined in")
    void returnsTheJoinedProjection() {
        LocationEntity roker = location("Roker", true, CoastalStructure.PIER, 90);
        row(roker, HW, FRESH);
        entityManager.flush();

        List<BigSeasCandidate> found = repository.findBigSeasCandidates(SINCE, FROM, TO);

        assertThat(found).hasSize(1);
        BigSeasCandidate c = found.getFirst();
        assertThat(c.locationId()).isEqualTo(roker.getId());
        assertThat(c.locationName()).isEqualTo("Roker");
        assertThat(c.structure()).isEqualTo(CoastalStructure.PIER);
        assertThat(c.label()).isEqualTo("Roker label");
        assertThat(c.facesDeg()).isEqualTo(90);
        assertThat(c.lat()).isEqualTo(54.9);
        assertThat(c.hwTime()).isEqualTo(HW);
        assertThat(c.windowStart()).isEqualTo(HW.minusMinutes(90));
        assertThat(c.windowEnd()).isEqualTo(HW.plusMinutes(90));
        assertThat(c.waveHeightM()).isEqualTo(4.2);
        assertThat(c.wavePeriodS()).isEqualTo(9.5);
        assertThat(c.waveDirectionDeg()).isEqualTo(80);
    }

    @Test
    @DisplayName("a stale sample, a disabled location and an un-set structure or bearing are all ignored")
    void ignoresStaleDisabledAndUnsetRows() {
        row(location("Stale", true, CoastalStructure.PIER, 90), HW, SINCE.minusSeconds(1));
        row(location("Disabled", false, CoastalStructure.PIER, 90), HW, FRESH);
        row(location("NoStructure", true, null, 90), HW, FRESH);
        row(location("NoBearing", true, CoastalStructure.BREAKWATER, null), HW, FRESH);
        row(location("Kept", true, CoastalStructure.LIGHTHOUSE, 45), HW, SINCE);
        entityManager.flush();

        List<BigSeasCandidate> found = repository.findBigSeasCandidates(SINCE, FROM, TO);

        assertThat(found).extracting(BigSeasCandidate::locationName).containsExactly("Kept");
    }

    @Test
    @DisplayName("the high-water range is inclusive at the start and exclusive at the end")
    void rangeIsHalfOpen() {
        LocationEntity loc = location("Roker", true, CoastalStructure.PIER, 90);
        row(loc, FROM.minusSeconds(1), FRESH);
        row(loc, FROM, FRESH);
        row(loc, TO.minusSeconds(1), FRESH);
        row(loc, TO, FRESH);
        entityManager.flush();

        List<BigSeasCandidate> found = repository.findBigSeasCandidates(SINCE, FROM, TO);

        assertThat(found).extracting(BigSeasCandidate::hwTime)
                .containsExactlyInAnyOrder(FROM, TO.minusSeconds(1));
    }
}
