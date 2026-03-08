package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.TideData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastDataAugmentor}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDataAugmentorTest {

    @Mock
    private OpenMeteoService openMeteoService;

    @Mock
    private TideService tideService;

    @InjectMocks
    private ForecastDataAugmentor augmentor;

    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 21, 20, 47);

    @Test
    @DisplayName("augmentWithDirectionalCloud() adds directional data when fetch succeeds")
    void augmentWithDirectionalCloud_success_addsDirectionalData() {
        AtmosphericData base = TestAtmosphericData.defaults();
        DirectionalCloudData directional = new DirectionalCloudData(10, 20, 30, 40, 50, 60);
        when(openMeteoService.fetchDirectionalCloudData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn(directional);

        AtmosphericData result = augmentor.augmentWithDirectionalCloud(
                base, 54.77, -1.57, 250, EVENT_TIME, null);

        assertThat(result.directionalCloud()).isEqualTo(directional);
    }

    @Test
    @DisplayName("augmentWithDirectionalCloud() returns original data when fetch returns null")
    void augmentWithDirectionalCloud_fetchReturnsNull_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();
        when(openMeteoService.fetchDirectionalCloudData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn(null);

        AtmosphericData result = augmentor.augmentWithDirectionalCloud(
                base, 54.77, -1.57, 250, EVENT_TIME, null);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithTideData() adds tide fields for coastal location")
    void augmentWithTideData_coastal_addsTideFields() {
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("4.5"), lowTide, new BigDecimal("1.2"));
        when(tideService.deriveTideData(1L, EVENT_TIME)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH));

        assertThat(result.tide()).isNotNull();
        assertThat(result.tide().tideState()).isEqualTo(TideState.HIGH);
        assertThat(result.tide().tideAligned()).isTrue();
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data for inland location (empty tide types)")
    void augmentWithTideData_inland_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of());

        assertThat(result).isSameAs(base);
        verify(tideService, never()).deriveTideData(any(), any());
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when tide types is null")
    void augmentWithTideData_nullTideTypes_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, null);

        assertThat(result).isSameAs(base);
        verify(tideService, never()).deriveTideData(any(), any());
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when location ID is null")
    void augmentWithTideData_nullLocationId_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, null, EVENT_TIME, Set.of(TideType.HIGH));

        assertThat(result).isSameAs(base);
        verify(tideService, never()).deriveTideData(any(), any());
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when no tide extremes found")
    void augmentWithTideData_noTideData_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();
        when(tideService.deriveTideData(1L, EVENT_TIME)).thenReturn(Optional.empty());

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH));

        assertThat(result).isSameAs(base);
    }
}
