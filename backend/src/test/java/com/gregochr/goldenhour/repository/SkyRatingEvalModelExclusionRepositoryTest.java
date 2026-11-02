package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalModelExclusionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for the weekly Sky-Rating Eval exclusion table: the model enum round-trips as
 * the primary key and a deleted row is gone.
 */
@DataJpaTest
class SkyRatingEvalModelExclusionRepositoryTest {

    @Autowired
    private SkyRatingEvalModelExclusionRepository repository;

    @Test
    @DisplayName("an exclusion round-trips by model and can be deleted")
    void roundTripsAndDeletes() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 2, 9, 0);
        repository.saveAndFlush(SkyRatingEvalModelExclusionEntity.builder()
                .model(EvaluationModel.SONNET_55).excludedAt(at).build());

        SkyRatingEvalModelExclusionEntity found = repository.findById(EvaluationModel.SONNET_55).orElseThrow();
        assertThat(found.getExcludedAt()).isEqualTo(at);
        assertThat(repository.existsById(EvaluationModel.OPUS)).isFalse();

        repository.deleteById(EvaluationModel.SONNET_55);
        repository.flush();
        assertThat(repository.findAll()).isEmpty();
    }
}
