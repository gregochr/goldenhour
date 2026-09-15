package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AuroraForecastResultWriter}.
 */
@ExtendWith(MockitoExtension.class)
class AuroraForecastResultWriterTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 7, 15);

    @Mock
    private AuroraForecastResultRepository resultRepository;

    private AuroraForecastResultWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AuroraForecastResultWriter(resultRepository);
    }

    @Test
    @DisplayName("a real run deletes the night's rows (real and simulated alike) before inserting")
    void replaceNightResults_real_deletesEverythingThenSaves() {
        AuroraForecastResultEntity entity = AuroraForecastResultEntity.builder()
                .forecastDate(NIGHT)
                .stars(4)
                .source("claude")
                .build();

        writer.replaceNightResults(NIGHT, List.of(entity), false);

        InOrder order = inOrder(resultRepository);
        order.verify(resultRepository).deleteByForecastDateIn(List.of(NIGHT));
        order.verify(resultRepository).saveAll(List.of(entity));
        verify(resultRepository, never()).deleteByForecastDateAndSimulatedTrue(NIGHT);
    }

    @Test
    @DisplayName("a real run with no results still clears the night without inserting anything")
    void replaceNightResults_real_emptyList_deletesOnly() {
        writer.replaceNightResults(NIGHT, List.of(), false);

        verify(resultRepository).deleteByForecastDateIn(List.of(NIGHT));
        verify(resultRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("a simulated run deletes only that night's simulated rows before inserting — "
            + "never the unfiltered delete that would also remove real results")
    void replaceNightResults_simulated_deletesOnlySimulatedThenSaves() {
        AuroraForecastResultEntity entity = AuroraForecastResultEntity.builder()
                .forecastDate(NIGHT)
                .stars(4)
                .source("claude")
                .simulated(true)
                .build();

        writer.replaceNightResults(NIGHT, List.of(entity), true);

        InOrder order = inOrder(resultRepository);
        order.verify(resultRepository).deleteByForecastDateAndSimulatedTrue(NIGHT);
        order.verify(resultRepository).saveAll(List.of(entity));
        verify(resultRepository, never()).deleteByForecastDateIn(anyList());
    }

    @Test
    @DisplayName("a simulated run with no results still clears its own simulated rows only")
    void replaceNightResults_simulated_emptyList_deletesOnlySimulated() {
        writer.replaceNightResults(NIGHT, List.of(), true);

        verify(resultRepository).deleteByForecastDateAndSimulatedTrue(NIGHT);
        verify(resultRepository, never()).saveAll(anyList());
        verify(resultRepository, never()).deleteByForecastDateIn(anyList());
    }
}
