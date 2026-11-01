package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;
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
 * V210: {@link SlotAtmosphereWriter} writes {@code coastal_mist_band} and {@code coastal_mist_scored} on EVERY
 * write: the band name and true when the data carries a coastal mist result (any band, NONE included), null and
 * false when it does not (switch off, inland, mist_prone), so no stale value survives on an existing row.
 */
@ExtendWith(MockitoExtension.class)
class SlotAtmosphereWriterCoastalMistTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final long LOCATION_ID = 8L;

    @Mock
    private SlotAtmosphereRepository repository;

    private SlotAtmosphereWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SlotAtmosphereWriter(repository,
                Clock.fixed(Instant.parse("2026-11-02T05:00:00Z"), ZoneOffset.UTC), true);
    }

    private static LocationEntity location() {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Boulmer");
        return location;
    }

    private static AtmosphericData slot() {
        return TestAtmosphericData.builder().targetType(TargetType.SUNRISE).build();
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
    @DisplayName("a LIGHT result writes the band name and scored = true")
    void lightIsWritten() {
        SlotAtmosphereEntity saved = write(null,
                slot().withCoastalMist(new CoastalMistResult(CoastalMistBand.LIGHT, 7_000, 0.8, 30)));

        assertThat(saved.getCoastalMistBand()).isEqualTo("LIGHT");
        assertThat(saved.isCoastalMistScored()).isTrue();
    }

    @Test
    @DisplayName("a THICK result is stored too, for tuning")
    void thickIsWritten() {
        SlotAtmosphereEntity saved = write(null,
                slot().withCoastalMist(new CoastalMistResult(CoastalMistBand.THICK, 2_000, 0.1, 90)));

        assertThat(saved.getCoastalMistBand()).isEqualTo("THICK");
        assertThat(saved.isCoastalMistScored()).isTrue();
    }

    @Test
    @DisplayName("a NONE result is a scored row with the band NONE, not a null")
    void noneIsWrittenAsScored() {
        SlotAtmosphereEntity saved = write(null,
                slot().withCoastalMist(new CoastalMistResult(CoastalMistBand.NONE, 25_000, 3.0, 10)));

        assertThat(saved.getCoastalMistBand()).isEqualTo("NONE");
        assertThat(saved.isCoastalMistScored()).isTrue();
    }

    @Test
    @DisplayName("no carrier (switch off, inland, mist_prone) writes a null band and scored = false")
    void noCarrierWritesNullAndFalse() {
        SlotAtmosphereEntity saved = write(null, slot());

        assertThat(saved.getCoastalMistBand()).isNull();
        assertThat(saved.isCoastalMistScored()).isFalse();
    }

    @Test
    @DisplayName("an existing scored row is cleared when the next write has no carrier (switch turned off)")
    void existingRowIsClearedNotLeftStale() {
        SlotAtmosphereEntity existing = new SlotAtmosphereEntity();
        existing.setCoastalMistBand("LIGHT");
        existing.setCoastalMistScored(true);

        SlotAtmosphereEntity saved = write(existing, slot());

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getCoastalMistBand()).isNull();
        assertThat(saved.isCoastalMistScored()).isFalse();
    }

    @Test
    @DisplayName("V211: the low cloud and precipitation the check read are written with the band")
    void lowCloudAndPrecipitationAreWritten() {
        SlotAtmosphereEntity saved = write(null,
                slot().withCoastalMist(new CoastalMistResult(CoastalMistBand.LIGHT, 7_000, 0.8, 30, 0.0)));

        assertThat(saved.getCoastalMistLowCloudPct()).isEqualTo(30);
        assertThat(saved.getCoastalMistPrecipMm()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("V211: an existing row's readings are cleared when the next write has no carrier")
    void lowCloudAndPrecipitationAreClearedNotLeftStale() {
        SlotAtmosphereEntity existing = new SlotAtmosphereEntity();
        existing.setCoastalMistLowCloudPct(30);
        existing.setCoastalMistPrecipMm(0.0);

        SlotAtmosphereEntity saved = write(existing, slot());

        assertThat(saved.getCoastalMistLowCloudPct()).isNull();
        assertThat(saved.getCoastalMistPrecipMm()).isNull();
    }

    @Test
    @DisplayName("an existing row is overwritten by the latest band")
    void existingRowIsOverwritten() {
        SlotAtmosphereEntity existing = new SlotAtmosphereEntity();
        existing.setCoastalMistBand("LIGHT");
        existing.setCoastalMistScored(true);

        SlotAtmosphereEntity saved = write(existing,
                slot().withCoastalMist(new CoastalMistResult(CoastalMistBand.THICK, 3_000, 0.2, 40)));

        assertThat(saved.getCoastalMistBand()).isEqualTo("THICK");
        assertThat(saved.isCoastalMistScored()).isTrue();
    }
}
