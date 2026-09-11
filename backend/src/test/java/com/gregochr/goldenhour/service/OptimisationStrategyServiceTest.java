package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.OptimisationStrategyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OptimisationStrategyService}.
 */
@ExtendWith(MockitoExtension.class)
class OptimisationStrategyServiceTest {

    @Mock
    private OptimisationStrategyRepository repository;

    @InjectMocks
    private OptimisationStrategyService service;

    private static OptimisationStrategyEntity strategy(RunType runType,
            OptimisationStrategyType type, boolean enabled, Integer param) {
        return OptimisationStrategyEntity.builder()
                .id(1L)
                .runType(runType)
                .strategyType(type)
                .enabled(enabled)
                .paramValue(param)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // -------------------------------------------------------------------------
    // seedDefaults — the local-dev prune and gap-fill
    // -------------------------------------------------------------------------

    /**
     * The prune must name exactly V153's retired types — written as literals, not read from
     * {@code RETIRED_STRATEGY_TYPES}, because an expectation read from the constant under test would
     * agree with it by construction. It must NOT be "every type the enum does not know": see
     * {@code OptimisationStrategyRepositoryTest} for the rollback case that rules that out.
     */
    @Test
    @DisplayName("seedDefaults deletes exactly the seven types V153 retired")
    void seedDefaults_prunesExactlyTheRetiredTypes() {
        stubAllRowsPresent();

        service.seedDefaults();

        verify(repository).deleteByStrategyTypeIn(List.of(
                "SKIP_LOW_RATED", "SKIP_EXISTING", "FORCE_IMMINENT", "FORCE_STALE",
                "EVALUATE_ALL", "NEXT_EVENT_ONLY", "BATCH_API"));
    }

    @Test
    @DisplayName("seedDefaults fills every missing row, matching production's defaults")
    void seedDefaults_emptyTable_seedsBothTypesPerRunType() {
        // Unstubbed, findByRunTypeAndStrategyType answers Optional.empty() — every row is missing.
        service.seedDefaults();

        ArgumentCaptor<OptimisationStrategyEntity> saved = ArgumentCaptor.forClass(OptimisationStrategyEntity.class);
        verify(repository, times(6)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OptimisationStrategyEntity::getRunType, OptimisationStrategyEntity::getStrategyType,
                        OptimisationStrategyEntity::isEnabled, OptimisationStrategyEntity::getParamValue)
                .containsExactlyInAnyOrder(
                        tuple(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 2),
                        tuple(RunType.VERY_SHORT_TERM, OptimisationStrategyType.TIDE_ALIGNMENT, true, null),
                        tuple(RunType.SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 2),
                        tuple(RunType.SHORT_TERM, OptimisationStrategyType.TIDE_ALIGNMENT, true, null),
                        tuple(RunType.LONG_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 2),
                        tuple(RunType.LONG_TERM, OptimisationStrategyType.TIDE_ALIGNMENT, true, null));
    }

    /**
     * ⚠️ The existing-local-database case. The old seed never wrote {@code TIDE_ALIGNMENT}, so once
     * the retired rows are pruned a local database holds only its three sentinel rows. A seed that ran
     * only on an empty table would see those and never add the tide rows, leaving the one strategy
     * production enables by default untoggleable locally.
     */
    @Test
    @DisplayName("seedDefaults adds the missing tide rows to a table that already has its sentinel rows")
    void seedDefaults_partialTable_addsOnlyTheMissingRows() {
        for (RunType rt : List.of(RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM)) {
            when(repository.findByRunTypeAndStrategyType(rt, OptimisationStrategyType.SENTINEL_SAMPLING))
                    .thenReturn(Optional.of(strategy(rt, OptimisationStrategyType.SENTINEL_SAMPLING, false, 4)));
            when(repository.findByRunTypeAndStrategyType(rt, OptimisationStrategyType.TIDE_ALIGNMENT))
                    .thenReturn(Optional.empty());
        }

        service.seedDefaults();

        ArgumentCaptor<OptimisationStrategyEntity> saved = ArgumentCaptor.forClass(OptimisationStrategyEntity.class);
        verify(repository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OptimisationStrategyEntity::getStrategyType)
                .containsOnly(OptimisationStrategyType.TIDE_ALIGNMENT);
    }

    @Test
    @DisplayName("seedDefaults writes nothing when every row already exists")
    void seedDefaults_fullTable_writesNothing() {
        stubAllRowsPresent();

        service.seedDefaults();

        verify(repository, never()).save(any());
    }

    /** Makes every (run type, strategy) row the service seeds already exist. */
    private void stubAllRowsPresent() {
        for (RunType rt : List.of(RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM)) {
            for (OptimisationStrategyType st : List.of(
                    OptimisationStrategyType.SENTINEL_SAMPLING, OptimisationStrategyType.TIDE_ALIGNMENT)) {
                when(repository.findByRunTypeAndStrategyType(rt, st))
                        .thenReturn(Optional.of(strategy(rt, st, true, null)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // getEnabledStrategies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getEnabledStrategies returns only enabled strategies for run type")
    void getEnabledStrategies_returnsEnabled() {
        var s1 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 2);
        when(repository.findByRunTypeAndEnabledTrue(RunType.VERY_SHORT_TERM)).thenReturn(List.of(s1));

        List<OptimisationStrategyEntity> result = service.getEnabledStrategies(RunType.VERY_SHORT_TERM);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStrategyType()).isEqualTo(OptimisationStrategyType.SENTINEL_SAMPLING);
    }

    // -------------------------------------------------------------------------
    // getAllConfigs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAllConfigs returns strategies grouped by forecast run types")
    void getAllConfigs_returnsGroupedByRunType() {
        when(repository.findByRunType(any())).thenReturn(List.of());

        Map<RunType, List<OptimisationStrategyEntity>> result = service.getAllConfigs();

        assertThat(result).containsOnlyKeys(
                RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM);
    }

    // -------------------------------------------------------------------------
    // updateStrategy
    // -------------------------------------------------------------------------

    /**
     * Enabling consults no other row: with the retired types gone the two survivors are independent,
     * so the mutual-exclusion check — which read the enabled set before every enable — went too.
     */
    @Test
    @DisplayName("updateStrategy enables a strategy and saves, without reading any other row")
    void updateStrategy_enablesAndSaves() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, false, 2);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.SENTINEL_SAMPLING)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.SENTINEL_SAMPLING, true, 4);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getParamValue()).isEqualTo(4);
        verify(repository).save(entity);
        verify(repository, never()).findByRunTypeAndEnabledTrue(any());
    }

    @Test
    @DisplayName("updateStrategy disables a strategy and keeps its stored parameter")
    void updateStrategy_disables() {
        var entity = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.VERY_SHORT_TERM,
                OptimisationStrategyType.SENTINEL_SAMPLING)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateStrategy(RunType.VERY_SHORT_TERM,
                OptimisationStrategyType.SENTINEL_SAMPLING, false, null);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getParamValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("updateStrategy throws if strategy not found")
    void updateStrategy_throwsIfNotFound() {
        when(repository.findByRunTypeAndStrategyType(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.TIDE_ALIGNMENT, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Strategy not found");
    }

    // -------------------------------------------------------------------------
    // serialiseEnabledStrategies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("serialiseEnabledStrategies formats with params")
    void serialise_formatsWithParams() {
        var s1 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SENTINEL_SAMPLING, true, 2);
        var s2 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.TIDE_ALIGNMENT, true, null);
        when(repository.findByRunTypeAndEnabledTrue(RunType.VERY_SHORT_TERM)).thenReturn(List.of(s1, s2));

        String result = service.serialiseEnabledStrategies(RunType.VERY_SHORT_TERM);

        assertThat(result).isEqualTo("SENTINEL_SAMPLING(2),TIDE_ALIGNMENT");
    }

    @Test
    @DisplayName("serialiseEnabledStrategies returns empty string when none enabled")
    void serialise_emptyWhenNoneEnabled() {
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of());

        String result = service.serialiseEnabledStrategies(RunType.SHORT_TERM);

        assertThat(result).isEmpty();
    }
}
