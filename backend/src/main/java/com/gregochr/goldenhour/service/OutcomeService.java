package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.model.ActualOutcome;
import com.gregochr.goldenhour.repository.ActualOutcomeRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Handles recording of actual observed sunrise/sunset outcomes.
 *
 * <p>Maps the {@link ActualOutcome} request DTO to a {@link ActualOutcomeEntity} and persists it.
 */
@Service
public class OutcomeService {

    private final ActualOutcomeRepository repository;

    /**
     * Constructs an {@code OutcomeService}.
     *
     * @param repository the actual outcome repository
     */
    public OutcomeService(ActualOutcomeRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns recorded outcomes for a location within a date range.
     *
     * @param lat  latitude of the location
     * @param lon  longitude of the location
     * @param from start of the date range (inclusive)
     * @param to   end of the date range (inclusive)
     * @return outcomes ordered by outcome date ascending
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    public List<ActualOutcomeEntity> query(double lat, double lon, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        return repository.findByLocationLatAndLocationLonAndOutcomeDateBetweenOrderByOutcomeDateAsc(
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), from, to);
    }

    /**
     * Persists an observed outcome and returns the saved entity.
     *
     * @param outcome the outcome data received from the client
     * @return the saved entity with its assigned database ID
     */
    public ActualOutcomeEntity record(ActualOutcome outcome) {
        if (outcome.actualRating() < 1 || outcome.actualRating() > 5) {
            throw new IllegalArgumentException("actualRating must be between 1 and 5");
        }
        ActualOutcomeEntity entity = ActualOutcomeEntity.builder()
                .locationLat(BigDecimal.valueOf(outcome.locationLat()))
                .locationLon(BigDecimal.valueOf(outcome.locationLon()))
                .locationName(outcome.locationName())
                .outcomeDate(outcome.outcomeDate())
                .targetType(outcome.targetType())
                .wentOut(outcome.wentOut())
                .actualRating(outcome.actualRating())
                .notes(outcome.notes())
                .recordedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        return repository.save(entity);
    }
}
