package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import com.gregochr.goldenhour.model.TravelDayRequest;
import com.gregochr.goldenhour.model.TravelDayResponse;
import com.gregochr.goldenhour.repository.TravelDayRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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

    /** The longest note the {@code note} column holds. */
    static final int MAX_NOTE_LENGTH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(TravelDayService.class);

    private final TravelDayRepository repository;
    private final ServedBriefingEpoch servedEpoch;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param repository  travel-day persistence
     * @param servedEpoch bumped after commit on every create, update and delete (hot topics read travel days)
     * @param clock       injected clock; "today" is the UK civil date derived through {@link ForecastHorizon}
     */
    public TravelDayService(TravelDayRepository repository, ServedBriefingEpoch servedEpoch, Clock clock) {
        this.repository = repository;
        this.servedEpoch = servedEpoch;
        this.clock = clock;
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
     * Lists the current and upcoming travel ranges, furthest-future range first. Ranges that have
     * already ended (end date before today, UK civil date) are omitted — they neither gate any
     * upcoming forecast nor belong in the public list.
     *
     * @return the current/future ranges as API views, furthest-future first
     */
    @Transactional(readOnly = true)
    public List<TravelDayResponse> list() {
        LocalDate today = ForecastHorizon.today(clock);
        return repository.findByEndDateGreaterThanEqualOrderByStartDateDesc(today).stream()
                .map(TravelDayResponse::from)
                .toList();
    }

    /**
     * Lists every travel range, past and upcoming, newest start first. Backs the admin list only
     * ({@code GET /api/admin/travel-days?includePast=true}); the public endpoint keeps {@link #list()}.
     *
     * @return all ranges as API views, newest start first
     */
    @Transactional(readOnly = true)
    public List<TravelDayResponse> listAll() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "startDate")).stream()
                .map(TravelDayResponse::from)
                .toList();
    }

    /**
     * Creates a new travel range.
     *
     * @param request the range to create
     * @return the persisted range as an API view
     * @throws IllegalArgumentException if dates are missing, the end precedes the start or the note is too long
     */
    @Transactional
    public TravelDayResponse add(TravelDayRequest request) {
        validate(request);
        TravelDayEntity saved = repository.save(TravelDayEntity.builder()
                .startDate(request.startDate())
                .endDate(request.endDate())
                .note(request.note())
                .build());
        servedEpoch.bump();
        LOG.info("[TRAVEL] Added travel range {} → {} ({})",
                saved.getStartDate(), saved.getEndDate(),
                saved.getNote() != null ? saved.getNote() : "no note");
        return TravelDayResponse.from(saved);
    }

    /**
     * Replaces the dates and note of an existing travel range.
     *
     * @param id      the range id
     * @param request the new values
     * @return the updated range as an API view
     * @throws IllegalArgumentException if dates are missing, the end precedes the start or the note is too long
     * @throws NoSuchElementException   if no range with that id exists
     */
    @Transactional
    public TravelDayResponse update(Long id, TravelDayRequest request) {
        validate(request);
        TravelDayEntity entity = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No travel range with id " + id));
        entity.setStartDate(request.startDate());
        entity.setEndDate(request.endDate());
        entity.setNote(request.note());
        TravelDayEntity saved = repository.save(entity);
        servedEpoch.bump();
        LOG.info("[TRAVEL] Updated travel range id={} to {} → {}", id, saved.getStartDate(), saved.getEndDate());
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
        servedEpoch.bump();
        LOG.info("[TRAVEL] Deleted travel range id={}", id);
    }

    /**
     * The one validation add and update share: both dates present, end not before start, note within
     * the column length.
     *
     * @param request the payload to check
     */
    private static void validate(TravelDayRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (request.note() != null && request.note().length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
    }
}
