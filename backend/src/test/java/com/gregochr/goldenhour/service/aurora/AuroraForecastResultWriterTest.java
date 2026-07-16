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
    @DisplayName("replaceNightResults deletes the night's rows before inserting the new ones")
    void replaceNightResults_deletesThenSaves() {
        AuroraForecastResultEntity entity = AuroraForecastResultEntity.builder()
                .forecastDate(NIGHT)
                .stars(4)
                .source("claude")
                .build();

        writer.replaceNightResults(NIGHT, List.of(entity));

        InOrder order = inOrder(resultRepository);
        order.verify(resultRepository).deleteByForecastDateIn(List.of(NIGHT));
        order.verify(resultRepository).saveAll(List.of(entity));
    }

    @Test
    @DisplayName("an empty list clears the night without inserting anything")
    void replaceNightResults_emptyList_deletesOnly() {
        writer.replaceNightResults(NIGHT, List.of());

        verify(resultRepository).deleteByForecastDateIn(List.of(NIGHT));
        verify(resultRepository, never()).saveAll(anyList());
    }
}
