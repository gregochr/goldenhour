package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.WeatherData;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurvivorAtmosphereWriter} — the Stage B submission-time carrier write.
 *
 * <p>Verifies the readings captured per survivor, the surge-null inland case, latest-wins upsert,
 * and the no-op guards (flag off, HOURLY, null data). The repository is mocked; the real unique-key
 * upsert against the schema is proven by the integration slice.
 */
@ExtendWith(MockitoExtension.class)
class SurvivorAtmosphereWriterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final Instant FIXED = Instant.parse("2026-06-21T17:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long LOCATION_ID = 7L;

    @Mock
    private SurvivorAtmosphereRepository repository;

    private SurvivorAtmosphereWriter writer(boolean enabled) {
        return new SurvivorAtmosphereWriter(repository, CLOCK, enabled);
    }

    private static LocationEntity location() {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Cat Bells");
        return location;
    }

    private static WeatherData weather() {
        return new WeatherData(20000, new BigDecimal("3.2"), 180, BigDecimal.ZERO, 88, 1,
                new BigDecimal("120"), 9.0, 1015.0, null, 0.04, 850.0);
    }

    private static AerosolData aerosol() {
        return new AerosolData(new BigDecimal("12.00"), new BigDecimal("60.00"),
                new BigDecimal("0.42"), 600);
    }

    /** Inland survivor: weather + aerosol populated, no surge (tide null). */
    private static AtmosphericData inlandData() {
        return new AtmosphericData("Cat Bells", DATE.atTime(20, 30), SUNSET,
                null, weather(), aerosol(), null, null, null, null, null);
    }

    @Test
    @DisplayName("flag off → writes nothing (additive-table rollback path)")
    void flagOff_writesNothing() {
        writer(false).write(location(), DATE, SUNSET, inlandData());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("HOURLY event → writes nothing (wildlife comfort is never colour-evaluated)")
    void hourly_writesNothing() {
        writer(true).write(location(), DATE, TargetType.HOURLY, inlandData());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("null atmospheric data → writes nothing")
    void nullData_writesNothing() {
        writer(true).write(location(), DATE, SUNSET, null);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("inland survivor: captures aerosol/snow/freezing/humidity; surge_risk_level null")
    void inland_capturesReadings_noSurge() {
        when(repository.findByLocationIdAndEvaluationDateAndEventType(LOCATION_ID, DATE, SUNSET))
                .thenReturn(Optional.empty());

        writer(true).write(location(), DATE, SUNSET, inlandData());

        SurvivorAtmosphereEntity saved = captureSave();
        assertThat(saved.getLocation().getId()).isEqualTo(LOCATION_ID);
        assertThat(saved.getEvaluationDate()).isEqualTo(DATE);
        assertThat(saved.getEventType()).isEqualTo(SUNSET);
        assertThat(saved.getAerosolOpticalDepth()).isEqualByComparingTo("0.42");
        assertThat(saved.getDust()).isEqualByComparingTo("60.00");
        assertThat(saved.getPm25()).isEqualByComparingTo("12.00");
        assertThat(saved.getSnowDepthMetres()).isEqualTo(0.04);
        assertThat(saved.getFreezingLevelMetres()).isEqualTo(850.0);
        assertThat(saved.getHumidity()).isEqualTo(88);
        assertThat(saved.getSurgeRiskLevel()).isNull();
        assertThat(saved.getEvaluatedAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("coastal survivor: surge_risk_level carries the breakdown's risk level name")
    void coastal_capturesSurgeRiskLevel() {
        when(repository.findByLocationIdAndEvaluationDateAndEventType(LOCATION_ID, DATE, SUNSET))
                .thenReturn(Optional.empty());
        StormSurgeBreakdown surge = new StormSurgeBreakdown(0.3, 0.5, 0.8, 995.0, 18.0, 200.0,
                0.9, TideRiskLevel.HIGH, "Deep low + onshore gale");
        AtmosphericData coastal = inlandData().withSurge(surge, 5.4, 4.6);

        writer(true).write(location(), DATE, SUNSET, coastal);

        SurvivorAtmosphereEntity saved = captureSave();
        assertThat(saved.getSurgeRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("upsert: an existing row for the key is updated in place (latest submission wins)")
    void upsert_updatesExistingRow() {
        SurvivorAtmosphereEntity existing = new SurvivorAtmosphereEntity();
        existing.setLocation(location());
        existing.setEvaluationDate(DATE);
        existing.setEventType(SUNSET);
        existing.setDust(new BigDecimal("5.00"));     // a stale prior reading
        when(repository.findByLocationIdAndEvaluationDateAndEventType(LOCATION_ID, DATE, SUNSET))
                .thenReturn(Optional.of(existing));

        writer(true).write(location(), DATE, SUNSET, inlandData());

        verify(repository).save(eq(existing));        // same instance re-saved — an upsert
        assertThat(existing.getDust()).isEqualByComparingTo("60.00");
        assertThat(existing.getEvaluatedAt()).isEqualTo(FIXED);
    }

    private SurvivorAtmosphereEntity captureSave() {
        ArgumentCaptor<SurvivorAtmosphereEntity> captor =
                ArgumentCaptor.forClass(SurvivorAtmosphereEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
