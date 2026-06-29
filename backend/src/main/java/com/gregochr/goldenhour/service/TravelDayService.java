package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Manages user-declared travel ranges and answers the "is this date a travel
 * day" question the overnight forecast batch gate asks per target date.
 *
 * <p>Travel ranges are ad-hoc and operator-maintained (travel is not fixed week
 * to week). An empty table is the dormant state: {@link #isTravelDay(LocalDate)}
 * returns {@code false} for every date and the gate is a no-op.
 */
@Service
public class TravelDayService {

    private static final Logger LOG = LoggerFactory.getLogger(TravelDayService.class);

    private final TravelDayRepository repository;

    /**
     * Constructs the service.
     *
     * @param repository travel-day persistence
     */
    public TravelDayService(TravelDayRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns whether the given date falls inside any declared travel range
     * (inclusive of both bounds).
     *
     * @param date the target date to test
     * @return {@code true} if the operator is travelling on that date
     */
    @Transactional(readOnly = true)
    public boolean isTravelDay(LocalDate date) {
        return repository.existsForDate(date);
    }

    /**
     * Lists all travel ranges, soonest first.
     *
     * @return the ranges as API views
     */
    @Transactional(readOnly = true)
    public List<TravelDayResponse> list() {
        return repository.findAllByOrderByStartDateAsc().stream()
                .map(TravelDayResponse::from)
                .toList();
    }

    /**
     * Creates a new travel range.
     *
     * @param request the range to create
     * @return the persisted range as an API view
     * @throws IllegalArgumentException if dates are missing or the end precedes the start
     */
    @Transactional
    public TravelDayResponse add(TravelDayRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        TravelDayEntity saved = repository.save(TravelDayEntity.builder()
                .startDate(request.startDate())
                .endDate(request.endDate())
                .note(request.note())
                .build());
        LOG.info("[TRAVEL] Added travel range {} → {} ({})",
                saved.getStartDate(), saved.getEndDate(),
                saved.getNote() != null ? saved.getNote() : "no note");
        return TravelDayResponse.from(saved);
    }

    /**
     * Deletes a travel range by id.
     *
     * @param id the range id
     * @throws NoSuchElementException if no range with that id exists
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("No travel range with id " + id);
        }
        repository.deleteById(id);
        LOG.info("[TRAVEL] Deleted travel range id={}", id);
    }
}
