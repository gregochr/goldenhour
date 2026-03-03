package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.OptimisationStrategyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    // getEnabledStrategies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getEnabledStrategies returns only enabled strategies for run type")
    void getEnabledStrategies_returnsEnabled() {
        var s1 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        when(repository.findByRunTypeAndEnabledTrue(RunType.VERY_SHORT_TERM)).thenReturn(List.of(s1));

        List<OptimisationStrategyEntity> result = service.getEnabledStrategies(RunType.VERY_SHORT_TERM);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStrategyType()).isEqualTo(OptimisationStrategyType.SKIP_LOW_RATED);
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
    // updateStrategy — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateStrategy enables a strategy and saves")
    void updateStrategy_enablesAndSaves() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, false, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED)).thenReturn(Optional.of(entity));
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED, true, 4);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getParamValue()).isEqualTo(4);
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("updateStrategy disables a strategy without validation")
    void updateStrategy_disablesWithoutValidation() {
        var entity = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.VERY_SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateStrategy(RunType.VERY_SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED, false, null);

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("updateStrategy throws if strategy not found")
    void updateStrategy_throwsIfNotFound() {
        when(repository.findByRunTypeAndStrategyType(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Strategy not found");
    }

    // -------------------------------------------------------------------------
    // Mutual exclusivity validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EVALUATE_ALL conflicts with SKIP_LOW_RATED")
    void evaluateAll_conflictsWithSkipLowRated() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.EVALUATE_ALL, false, null);
        var existing = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.EVALUATE_ALL)).thenReturn(Optional.of(entity));
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.EVALUATE_ALL, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EVALUATE_ALL conflicts");
    }

    @Test
    @DisplayName("SKIP_EXISTING conflicts with SKIP_LOW_RATED")
    void skipExisting_conflictsWithSkipLowRated() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_EXISTING, false, null);
        var existing = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_EXISTING)).thenReturn(Optional.of(entity));
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_EXISTING, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKIP_EXISTING conflicts with SKIP_LOW_RATED");
    }

    @Test
    @DisplayName("SKIP_LOW_RATED conflicts with EVALUATE_ALL")
    void skipLowRated_conflictsWithEvaluateAll() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, false, 3);
        var existing = strategy(RunType.SHORT_TERM, OptimisationStrategyType.EVALUATE_ALL, true, null);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED)).thenReturn(Optional.of(entity));
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.SKIP_LOW_RATED, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EVALUATE_ALL");
    }

    @Test
    @DisplayName("FORCE_IMMINENT has no conflicts and can always be enabled")
    void forceImminent_noConflicts() {
        var entity = strategy(RunType.SHORT_TERM, OptimisationStrategyType.FORCE_IMMINENT, false, null);
        var existing = strategy(RunType.SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        when(repository.findByRunTypeAndStrategyType(RunType.SHORT_TERM,
                OptimisationStrategyType.FORCE_IMMINENT)).thenReturn(Optional.of(entity));
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateStrategy(RunType.SHORT_TERM,
                OptimisationStrategyType.FORCE_IMMINENT, true, null);

        assertThat(result.isEnabled()).isTrue();
    }

    // -------------------------------------------------------------------------
    // serialiseEnabledStrategies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("serialiseEnabledStrategies formats with params")
    void serialise_formatsWithParams() {
        var s1 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.SKIP_LOW_RATED, true, 3);
        var s2 = strategy(RunType.VERY_SHORT_TERM, OptimisationStrategyType.FORCE_IMMINENT, true, null);
        when(repository.findByRunTypeAndEnabledTrue(RunType.VERY_SHORT_TERM)).thenReturn(List.of(s1, s2));

        String result = service.serialiseEnabledStrategies(RunType.VERY_SHORT_TERM);

        assertThat(result).isEqualTo("SKIP_LOW_RATED(3),FORCE_IMMINENT");
    }

    @Test
    @DisplayName("serialiseEnabledStrategies returns empty string when none enabled")
    void serialise_emptyWhenNoneEnabled() {
        when(repository.findByRunTypeAndEnabledTrue(RunType.SHORT_TERM)).thenReturn(List.of());

        String result = service.serialiseEnabledStrategies(RunType.SHORT_TERM);

        assertThat(result).isEmpty();
    }
}
