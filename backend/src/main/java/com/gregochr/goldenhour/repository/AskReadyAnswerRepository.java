package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AskReadyAnswerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link AskReadyAnswerEntity}.
 */
public interface AskReadyAnswerRepository extends JpaRepository<AskReadyAnswerEntity, Long> {

    /**
     * Finds the stored answer of one question in one scope.
     *
     * @param scopeKey   {@code ALL} or a region id as text
     * @param questionId the Ready question's name
     * @return the row, or empty
     */
    Optional<AskReadyAnswerEntity> findByScopeKeyAndQuestionId(String scopeKey, String questionId);

    /**
     * Every stored answer of a scope.
     *
     * @param scopeKey {@code ALL} or a region id as text
     * @return the rows, in no particular order
     */
    List<AskReadyAnswerEntity> findByScopeKey(String scopeKey);
}
