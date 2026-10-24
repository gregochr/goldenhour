package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.repository.MarineHighWaterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link MarineHighWaterWriter}: delete the range first, then insert, in one
 * transaction (the bulk delete must reach the database before the inserts, or an unchanged
 * {@code hw_time} would hit the UNIQUE key).
 */
@ExtendWith(MockitoExtension.class)
class MarineHighWaterWriterTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 12, 9, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 14, 0, 0);

    @Mock
    private MarineHighWaterRepository repository;

    @Test
    @DisplayName("deletes the location's range, then saves the fresh rows, in that order")
    void replace_deletesThenInserts() {
        MarineHighWaterWriter writer = new MarineHighWaterWriter(repository);
        List<MarineHighWaterEntity> rows = List.of(
                MarineHighWaterEntity.builder().hwTime(LocalDateTime.of(2026, 12, 10, 12, 0)).build());

        writer.replace(7L, FROM, TO, rows);

        InOrder order = inOrder(repository);
        order.verify(repository).deleteByLocationIdAndHwTimeBetween(7L, FROM, TO);
        order.verify(repository).saveAll(rows);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("an empty row list only clears the range and saves nothing")
    void replace_emptyRows_onlyClears() {
        MarineHighWaterWriter writer = new MarineHighWaterWriter(repository);

        writer.replace(7L, FROM, TO, List.of());

        InOrder order = inOrder(repository);
        order.verify(repository).deleteByLocationIdAndHwTimeBetween(7L, FROM, TO);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("replace is @Transactional: delete and insert share one transaction")
    void replace_isTransactional() throws NoSuchMethodException {
        Method replace = MarineHighWaterWriter.class.getMethod(
                "replace", Long.class, LocalDateTime.class, LocalDateTime.class, List.class);

        assertThat(replace.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
