package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V202: {@link SlotAtmosphereWriter} writes {@code inversion_score_v1} and {@code inversion_score_v2_raw} on
 * EVERY write, and writes null for both when v2 did not compute, so a cleared valley floor or a switched-off
 * v2 leaves no stale value on an existing row.
 */
@ExtendWith(MockitoExtension.class)
class SlotAtmosphereWriterInversionV2Test {

    private static final LocalDate DATE = LocalDate.of(2025, 10, 15);
    private static final long LOCATION_ID = 7L;

    @Mock
    private SlotAtmosphereRepository repository;

    private SlotAtmosphereWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SlotAtmosphereWriter(repository,
                Clock.fixed(Instant.parse("2025-10-15T05:00:00Z"), ZoneOffset.UTC), true);
    }

    private static LocationEntity location() {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Cat Bells");
        return location;
    }

    private static AtmosphericData slot() {
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE).inversionScore(7.0).build();
    }

    private static InversionV2Result scored(Double old) {
        return new InversionV2Result(null, old, 8.0, 7.0, InversionRegime.SURFACE_BASED, 100.0, 450.0, 2.0,
                100, 450, InversionClearance.EDGE, "edge(8),raise(7)", true);
    }

    private SlotAtmosphereEntity write(SlotAtmosphereEntity existing, AtmosphericData data) {
        when(repository.findByLocationIdAndEvaluationDateAndEventType(LOCATION_ID, DATE, TargetType.SUNRISE))
                .thenReturn(Optional.ofNullable(existing));
        writer.write(location(), DATE, TargetType.SUNRISE, data);
        ArgumentCaptor<SlotAtmosphereEntity> captor = ArgumentCaptor.forClass(SlotAtmosphereEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a slot v2 scored records the old score, v2's raw score and the stored score")
    void recordsBothScores() {
        SlotAtmosphereEntity saved = write(null, slot().withInversionV2(scored(6.0)));

        assertThat(saved.getInversionScore()).isEqualTo(7.0);
        assertThat(saved.getInversionScoreV1()).isEqualTo(6.0);
        assertThat(saved.getInversionScoreV2Raw()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("v2 scored but the old calculator could not: v1 is null, v2 raw is set")
    void oldNullStillRecordsRaw() {
        SlotAtmosphereEntity saved = write(null, slot().withInversionV2(scored(null)));

        assertThat(saved.getInversionScoreV1()).isNull();
        assertThat(saved.getInversionScoreV2Raw()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("no carrier: both columns are written null")
    void noCarrierWritesNull() {
        SlotAtmosphereEntity saved = write(null, slot());

        assertThat(saved.getInversionScoreV1()).isNull();
        assertThat(saved.getInversionScoreV2Raw()).isNull();
        assertThat(saved.getInversionScore()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("a profile-only carrier (v2 did not compute) writes null")
    void profileOnlyCarrierWritesNull() {
        SlotAtmosphereEntity saved = write(null,
                slot().withInversionV2(InversionV2Result.ofProfile(null)));

        assertThat(saved.getInversionScoreV1()).isNull();
        assertThat(saved.getInversionScoreV2Raw()).isNull();
    }

    @Test
    @DisplayName("an existing v2-era row is cleared when the next write has no v2 result (cleared floor, switch off)")
    void existingRowIsClearedNotLeftStale() {
        SlotAtmosphereEntity existing = new SlotAtmosphereEntity();
        existing.setInversionScoreV1(6.0);
        existing.setInversionScoreV2Raw(8.0);

        SlotAtmosphereEntity saved = write(existing, slot());

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getInversionScoreV1()).isNull();
        assertThat(saved.getInversionScoreV2Raw()).isNull();
    }
}
