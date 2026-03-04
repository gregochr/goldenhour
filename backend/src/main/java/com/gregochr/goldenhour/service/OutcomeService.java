package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ActualOutcome;
import com.gregochr.goldenhour.repository.ActualOutcomeRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Handles recording of actual observed sunrise/sunset outcomes.
 *
 * <p>Maps the {@link ActualOutcome} request DTO to a {@link ActualOutcomeEntity} and persists it.
 */
@Service
public class OutcomeService {

    private final ActualOutcomeRepository repository;
    private final LocationRepository locationRepository;

    /**
     * Constructs an {@code OutcomeService}.
     *
     * @param repository         the actual outcome repository
     * @param locationRepository the location repository for resolving location names
     */
    public OutcomeService(ActualOutcomeRepository repository,
                          LocationRepository locationRepository) {
        this.repository = repository;
        this.locationRepository = locationRepository;
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
    /**
     * Validates that a score is in the 0–100 range if present.
     *
     * @param value the score to validate, or {@code null}
     * @param name  field name used in the exception message
     */
    private void validateScore(Integer value, String name) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    public ActualOutcomeEntity record(ActualOutcome outcome) {
        validateScore(outcome.fierySkyActual(), "fierySkyActual");
        validateScore(outcome.goldenHourActual(), "goldenHourActual");
        LocationEntity location = locationRepository.findByName(outcome.locationName())
                .orElseThrow(() -> new NoSuchElementException(
                        "No location named '" + outcome.locationName() + "'"));
        ActualOutcomeEntity entity = ActualOutcomeEntity.builder()
                .locationLat(BigDecimal.valueOf(outcome.locationLat()))
                .locationLon(BigDecimal.valueOf(outcome.locationLon()))
                .location(location)
                .outcomeDate(outcome.outcomeDate())
                .targetType(outcome.targetType())
                .wentOut(outcome.wentOut())
                .fierySkyActual(outcome.fierySkyActual())
                .goldenHourActual(outcome.goldenHourActual())
                .notes(outcome.notes())
                .recordedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        return repository.save(entity);
    }
}
