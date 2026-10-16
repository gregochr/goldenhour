package com.gregochr.goldenhour.service.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.ForecastEvaluationPromptEntity;
import com.gregochr.goldenhour.repository.ForecastEvaluationPromptRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Unit tests for {@link ForecastPromptStore}. */
@ExtendWith(MockitoExtension.class)
class ForecastPromptStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");

    @Mock
    private ForecastEvaluationPromptRepository repository;

    @Mock
    private ForecastEvaluationRepository evaluationRepository;

    private ForecastPromptStore store;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        store = new ForecastPromptStore(repository, evaluationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        logger = (Logger) LoggerFactory.getLogger(ForecastPromptStore.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("stores one row per evalRowId with the exact message and the clock's instant")
    @SuppressWarnings("unchecked")
    void storesRows() {
        Map<Long, String> prompts = new LinkedHashMap<>();
        prompts.put(11L, "message eleven");
        prompts.put(12L, "message twelve");

        store.store(prompts);

        ArgumentCaptor<List<ForecastEvaluationPromptEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<ForecastEvaluationPromptEntity> rows = captor.getValue();
        assertThat(rows).extracting(ForecastEvaluationPromptEntity::getEvaluationId)
                .containsExactly(11L, 12L);
        assertThat(rows).extracting(ForecastEvaluationPromptEntity::getUserMessage)
                .containsExactly("message eleven", "message twelve");
        assertThat(rows).extracting(ForecastEvaluationPromptEntity::getCreatedAt)
                .containsOnly(NOW);
        assertThat(rows.get(0).isNew()).isTrue();
        assertThat(rows.get(0).getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("an empty or null map touches nothing")
    void emptyStoresNothing() {
        store.store(Map.of());
        store.store(null);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a repository failure is swallowed and logged once at WARN with the count")
    void failureIsSwallowedAndWarned() {
        doThrow(new IllegalStateException("db down")).when(repository).saveAll(anyList());

        assertThatCode(() -> store.store(Map.of(1L, "a", 2L, "b", 3L, "c")))
                .doesNotThrowAnyException();

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("3 forecast prompt(s)");
    }

    @Test
    @DisplayName("V179: stamps one statement per distinct hash with that hash's row ids")
    void stampsOneStatementPerHash() {
        Map<String, List<Long>> byHash = new LinkedHashMap<>();
        byHash.put("inland", List.of(11L, 12L));
        byHash.put("coastal", List.of(13L));

        store.stampPromptHashes(byHash);

        verify(evaluationRepository).stampPromptHash("inland", List.of(11L, 12L));
        verify(evaluationRepository).stampPromptHash("coastal", List.of(13L));
        verifyNoMoreInteractions(evaluationRepository);
    }

    @Test
    @DisplayName("V179: an empty or null map, or a hash with no rows, stamps nothing")
    void stampsNothingWhenNothingToStamp() {
        store.stampPromptHashes(Map.of());
        store.stampPromptHashes(null);
        store.stampPromptHashes(Map.of("inland", List.of()));

        verifyNoInteractions(evaluationRepository);
    }

    @Test
    @DisplayName("V179: a failed stamp is swallowed, logged at WARN, and does not stop the next hash")
    void failedStampIsSwallowedAndNextHashStillStamps() {
        Map<String, List<Long>> byHash = new LinkedHashMap<>();
        byHash.put("inland", List.of(11L, 12L));
        byHash.put("coastal", List.of(13L));
        doThrow(new IllegalStateException("db down")).when(evaluationRepository)
                .stampPromptHash(eq("inland"), anyCollection());

        assertThatCode(() -> store.stampPromptHashes(byHash)).doesNotThrowAnyException();

        verify(evaluationRepository).stampPromptHash("coastal", List.of(13L));
        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("2 forecast row(s)");
    }

    @Test
    @DisplayName("entity becomes not-new after persist")
    void entityMarksNotNew() {
        ForecastEvaluationPromptEntity row = new ForecastEvaluationPromptEntity(1L, "m", NOW);
        assertThat(row.isNew()).isTrue();
        row.markNotNew();
        assertThat(row.isNew()).isFalse();
    }
}
