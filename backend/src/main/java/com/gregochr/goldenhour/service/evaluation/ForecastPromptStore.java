package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.ForecastEvaluationPromptEntity;
import com.gregochr.goldenhour.repository.ForecastEvaluationPromptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Records the user message each batch sky forecast request sent, keyed by the pending
 * {@code forecast_evaluation} row id (table {@code forecast_evaluation_prompt}, V164).
 *
 * <p>Strictly best-effort: a failure to store is caught and logged once per batch with a count,
 * and never fails or blocks the submission it rides alongside.
 */
@Service
public class ForecastPromptStore {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastPromptStore.class);

    private final ForecastEvaluationPromptRepository repository;
    private final Clock clock;

    /**
     * Constructs the store.
     *
     * @param repository the prompt repository
     * @param clock      injectable clock for the {@code created_at} stamp
     */
    public ForecastPromptStore(ForecastEvaluationPromptRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Stores each user message against its evaluation row id. An empty or null map stores nothing.
     * Never throws.
     *
     * @param promptsByEvalRowId user message per pending {@code forecast_evaluation} id
     */
    public void store(Map<Long, String> promptsByEvalRowId) {
        if (promptsByEvalRowId == null || promptsByEvalRowId.isEmpty()) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            List<ForecastEvaluationPromptEntity> rows = promptsByEvalRowId.entrySet().stream()
                    .map(e -> new ForecastEvaluationPromptEntity(e.getKey(), e.getValue(), now))
                    .toList();
            repository.saveAll(rows);
        } catch (RuntimeException e) {
            LOG.warn("Could not store {} forecast prompt(s) for this batch — submission unaffected: {}",
                    promptsByEvalRowId.size(), e.toString());
        }
    }
}
