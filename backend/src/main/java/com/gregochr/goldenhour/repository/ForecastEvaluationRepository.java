package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CalibrationPair;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link ForecastEvaluationEntity} persistence operations.
 */
@Repository
public interface ForecastEvaluationRepository extends JpaRepository<ForecastEvaluationEntity, Long> {

    /**
     * Returns only the most recent evaluation run per slot for a set of locations within a
     * date range.
     *
     * <p>A "slot" is (location, target date, target type). For {@code HOURLY} wildlife rows —
     * which share a single target type across many hours of one day — the solar event time is
     * added to the key so each hour is kept distinct. For {@code SUNRISE}/{@code SUNSET} the
     * solar event time is deliberately excluded: it is derived from the location's editable
     * lat/lon, so a coordinate edit changes it, and including it would return a stale pre-edit
     * row alongside the latest one for the same slot.
     *
     * <p>Because every evaluation run inserts a new row (runs are never updated in place, with one
     * designed exception — {@code ForecastResultHandler} scores a batch-{@code PENDING} row by
     * primary key when its result lands, rather than inserting a second row), the table
     * accumulates many rows per slot as forecasts are re-evaluated. The map view renders
     * only the latest run per slot, so this query performs that de-duplication at source — via a
     * single batched query instead of one query per location. The correlated {@code MAX} subquery
     * is supported by the composite index {@code idx_forecast_eval_latest_run} on
     * {@code (location_id, target_date, target_type, forecast_run_at)} (migration V128).
     *
     * <p>{@code JOIN FETCH e.location} initialises the eagerly-mapped location in the same query,
     * avoiding a secondary select per returned row.
     *
     * <p>⚠️ Excludes {@code PENDING} rows (batch-submitted, not yet scored) from both the outer
     * selection and the correlated {@code MAX} subquery. A pending row must never win this query:
     * it would blank the map's prompted pins for the submit→result window, suppressing the sparse
     * {@code cached_evaluation} fallback that exists precisely for that window (see
     * {@code ForecastController.getForecasts}'s javadoc). Excluding it from the {@code MAX}
     * subquery too is what lets the previous (non-pending) row still win — otherwise the outer
     * predicate could never match once a newer pending row exists for the slot.
     *
     * @param locationIds the location primary keys to query
     * @param from        the start of the date range (inclusive)
     * @param to          the end of the date range (inclusive)
     * @return the latest non-pending run per slot, ordered by location, target date, then target type
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e JOIN FETCH e.location"
            + " WHERE e.location.id IN :locationIds"
            + " AND e.targetDate BETWEEN :from AND :to"
            + " AND (e.batchState IS NULL"
            + "   OR e.batchState <> com.gregochr.goldenhour.entity.BatchState.PENDING)"
            + " AND e.forecastRunAt = ("
            + "   SELECT MAX(e2.forecastRunAt) FROM ForecastEvaluationEntity e2"
            + "   WHERE e2.location.id = e.location.id"
            + "   AND e2.targetDate = e.targetDate"
            + "   AND e2.targetType = e.targetType"
            + "   AND (e2.batchState IS NULL"
            + "     OR e2.batchState <> com.gregochr.goldenhour.entity.BatchState.PENDING)"
            + "   AND (e.targetType <> com.gregochr.goldenhour.entity.TargetType.HOURLY"
            + "     OR e2.solarEventTime = e.solarEventTime"
            + "     OR (e2.solarEventTime IS NULL AND e.solarEventTime IS NULL)))"
            + " ORDER BY e.location.id ASC, e.targetDate ASC, e.targetType ASC")
    List<ForecastEvaluationEntity> findLatestRunPerSlotByLocationIds(
            @Param("locationIds") Collection<Long> locationIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Returns evaluations for a location, date range, and evaluation model, ordered by date
     * and target type. Used by {@code GET /api/forecast} to return role-appropriate rows.
     *
     * @param locationId      the location primary key
     * @param from            the start of the date range (inclusive)
     * @param to              the end of the date range (inclusive)
     * @param evaluationModel which model's rows to return (HAIKU or SONNET)
     * @return evaluations ordered by target date ascending then target type ascending
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e WHERE e.location.id = :locationId"
            + " AND e.targetDate BETWEEN :from AND :to AND e.evaluationModel = :model"
            + " ORDER BY e.targetDate ASC, e.targetType ASC")
    List<ForecastEvaluationEntity> findByLocationAndDateRangeAndModel(
            @Param("locationId") Long locationId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("model") EvaluationModel evaluationModel);

    /**
     * Returns every evaluation of the given target types for one date, across the whole roster —
     * regardless of triage or batch state. {@code location} is {@code EAGER}-fetched on the entity,
     * so this needs no {@code JOIN FETCH} to read {@code location.getRegion()} safely afterwards.
     *
     * <p>Used by {@code TopicDailyLogJob} for the two topics ({@code DUST}, {@code STORM_SURGE})
     * whose columns are written pre-triage (from {@code AtmosphericData} during augmentation, not
     * from Claude's response) and therefore reflect the complete population rather than only the
     * rows that survived triage — see {@code CLAUDE.md}'s "Where a rating lives" table and plan
     * §1's DUST row. May return several rows for the same (location, date, type) slot when a run
     * was retried; callers that want one presence answer per slot should treat "any row" as
     * qualifying rather than assume one row per slot.
     *
     * @param targetDate  the calendar date to query
     * @param targetTypes the target types to include (normally {@code SUNRISE}, {@code SUNSET})
     * @return matching rows, in no guaranteed order
     */
    List<ForecastEvaluationEntity> findByTargetDateAndTargetTypeIn(
            LocalDate targetDate, Collection<TargetType> targetTypes);

    /**
     * Returns the most recent non-{@code PENDING} evaluation for a given location, date, and
     * target type. Used to check prior ratings before deciding whether an Opus optimisation run
     * is worthwhile, and by the briefing/plan view as its per-slot forecast fallback.
     *
     * <p>⚠️ Excludes {@code PENDING} rows (R1): a batch row submitted but not yet scored must
     * never shadow the previous cycle's rated or triaged row for this slot. Callers pass
     * {@code PageRequest.of(0, 1)} to cap the result to the single most recent row — a custom
     * {@code @Query} cannot express {@code Optional}-returning "top 1" directly (JPQL has no
     * {@code LIMIT}), so the caller takes {@code .stream().findFirst()}.
     *
     * @param locationId the location primary key
     * @param targetDate the date being forecast
     * @param targetType SUNRISE or SUNSET
     * @param pageable   caps the result; pass {@code PageRequest.of(0, 1)}
     * @return the most recent non-pending evaluation, newest first
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e"
            + " WHERE e.location.id = :locationId"
            + " AND e.targetDate = :targetDate"
            + " AND e.targetType = :targetType"
            + " AND (e.batchState IS NULL"
            + "   OR e.batchState <> com.gregochr.goldenhour.entity.BatchState.PENDING)"
            + " ORDER BY e.forecastRunAt DESC")
    List<ForecastEvaluationEntity> findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc(
            @Param("locationId") Long locationId,
            @Param("targetDate") LocalDate targetDate,
            @Param("targetType") TargetType targetType,
            Pageable pageable);

    /**
     * Every row of a roster's slot-day that had been SCORED by {@code upTo}, newest submission first —
     * the as-of-event resolver's step-2 pool ({@code AsOfEventResolver}, outcome-rating plan §2 #1).
     *
     * <p>Bounded on the AVAILABILITY stamp: {@code scored_at IS NOT NULL AND scored_at <= upTo}. A
     * row with no {@code scored_at} (still {@code PENDING}, {@code ABANDONED}, an {@code HOURLY}
     * comfort row, or written before V178 with nothing to say: V183 stamped the rest) is never
     * returned, because its {@code forecast_run_at} is a submission instant and proves nothing about
     * when it arrived. The caller applies the per-place
     * event bound, the has-something-to-say rule and the stability-skip retraction in memory, from
     * this one read — {@code upTo} is the latest event instant among the places asked about, so the
     * pool is a superset for every one of them.
     *
     * <p>Ordered {@code forecast_run_at DESC, id DESC}, the order {@link
     * #findTopByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtDesc} serves a slot in, so
     * the first row passing a caller's tests is the one the serve path would have picked.
     *
     * @param locationIds the places asked about
     * @param targetDate  the event date
     * @param targetType  SUNRISE or SUNSET
     * @param upTo        the latest availability instant of interest
     * @return the scored rows, newest submission first
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e"
            + " WHERE e.location.id IN :locationIds"
            + " AND e.targetDate = :targetDate"
            + " AND e.targetType = :targetType"
            + " AND e.scoredAt IS NOT NULL AND e.scoredAt <= :upTo"
            + " ORDER BY e.forecastRunAt DESC, e.id DESC")
    List<ForecastEvaluationEntity> findScoredByEventInstant(
            @Param("locationIds") Collection<Long> locationIds,
            @Param("targetDate") LocalDate targetDate,
            @Param("targetType") TargetType targetType,
            @Param("upTo") Instant upTo);

    /**
     * Deletes one place's {@code HOURLY} (wildlife comfort) rows for one target date — the
     * "replace" half of {@code WildlifeComfortRefreshJob}'s delete-then-insert, and the single
     * exception to this table being insert-only.
     *
     * <p>⚠️ The {@code HOURLY} literal is part of the query text, not a parameter, so no caller can
     * point this at a {@code SUNRISE} or {@code SUNSET} row: the scored forecast rows, their
     * {@code cloud_verification} dependants and the calibration join all stay out of its reach.
     * Callers must delete only inside the transaction that inserts the replacement rows, and only
     * once those rows exist — see {@code WildlifeComfortWriter}.
     *
     * @param locationId the location primary key
     * @param targetDate the target date whose {@code HOURLY} rows are superseded
     * @return the number of rows deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM ForecastEvaluationEntity e WHERE e.location.id = :locationId"
            + " AND e.targetDate = :targetDate"
            + " AND e.targetType = com.gregochr.goldenhour.entity.TargetType.HOURLY")
    int deleteHourlyByLocationIdAndTargetDate(@Param("locationId") Long locationId,
            @Param("targetDate") LocalDate targetDate);

    /**
     * R7(a) event-driven abandonment: stamps {@code ABANDONED} every still-{@code PENDING} row
     * among the given primary keys. A no-op for any id already {@code SCORED} (the batch result
     * beat the sweep) or already {@code ABANDONED} (an R6 retry precursor stamp beat it here) —
     * the {@code WHERE} clause only ever touches rows still awaiting a result.
     *
     * @param ids candidate row ids (typically every {@code fc-} row a just-ended batch touched)
     * @return the number of rows actually stamped {@code ABANDONED}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ForecastEvaluationEntity e SET e.batchState ="
            + " com.gregochr.goldenhour.entity.BatchState.ABANDONED"
            + " WHERE e.id IN :ids"
            + " AND e.batchState = com.gregochr.goldenhour.entity.BatchState.PENDING")
    int abandonPending(@Param("ids") Collection<Long> ids);

    /**
     * R7(b) time-based backstop: stamps {@code ABANDONED} every {@code PENDING} row whose
     * {@code forecast_run_at} (the submit-time timestamp — R4/R5 never bump it) is older than
     * {@code cutoff}. Catches crash windows, unreconstructable retries, and any path the R7(a)
     * event-driven stamp missed.
     *
     * @param cutoff rows submitted before this instant are stamped {@code ABANDONED}
     * @return the number of rows stamped {@code ABANDONED}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ForecastEvaluationEntity e SET e.batchState ="
            + " com.gregochr.goldenhour.entity.BatchState.ABANDONED"
            + " WHERE e.batchState = com.gregochr.goldenhour.entity.BatchState.PENDING"
            + " AND e.forecastRunAt < :cutoff")
    int abandonPendingOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Pairs each reader's {@code outcome_rating} with the forecast row it resolved to when it was
     * written, so the calibration gate can score forecasts against what readers saw.
     *
     * <p>The join is on {@code r.forecastEvaluationId} — the one row proven available by the event
     * and unretracted at it — never on the natural key, which would admit every rated row of the
     * slot and let a batch that landed after the event win the gate's newest-run-per-slot rule. A
     * rating with a null id therefore pairs with nothing. The predicted rating is
     * {@code r.forecastRating}, the star the reader saw (never {@code e.rating}, which for a
     * recombined OPEN_FELL slot is the sky lane alone); a rating with no captured star is not a
     * pair, and neither is one whose row carries no rating. Horizon, run time, model and
     * potentials are the row's own. "Didn't see it" ratings (null) are not pairs. The window
     * bounds the event date, inclusive at both ends.
     *
     * <p>The projection uses the narrower {@code CalibrationPair} constructor rather than null
     * literals: the record has no fiery-sky or golden-hour actual and no trip flag. It does carry the
     * row's valley-mist band ({@code e.mistPotential}, the forecast Claude echoed) and the reader's
     * {@code seen} tag (V187), which the mist-prone bucket and its tag tally read.
     *
     * @param from start of the event-date window (inclusive)
     * @param to   end of the event-date window (inclusive)
     * @return one pair per usable rating, unordered
     */
    @Query("SELECT new com.gregochr.goldenhour.model.CalibrationPair("
            + " e.location.name, e.targetDate, e.targetType, e.daysAhead, e.forecastRunAt,"
            + " e.evaluationModel, r.forecastRating, e.fierySkyPotential, e.goldenHourPotential,"
            + " r.rating, r.userId, e.appBuild, e.promptHash, e.mistPotential, r.seen)"
            + " FROM ForecastEvaluationEntity e, OutcomeRatingEntity r"
            + " WHERE e.id = r.forecastEvaluationId"
            + " AND e.rating IS NOT NULL"
            + " AND r.rating IS NOT NULL"
            + " AND r.forecastRating IS NOT NULL"
            + " AND r.eventDate BETWEEN :from AND :to")
    List<CalibrationPair> findCalibrationPairsFromRatings(
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Stamps the system-prompt fingerprint on rows whose batch request carried it (V182). One
     * statement per distinct prompt, never a load-modify-save, so it cannot rewrite any other column
     * of a row a result handler is scoring.
     *
     * @param hash the {@code PromptHash} of the system prompt
     * @param ids  the {@code forecast_evaluation} ids whose requests carried it
     * @return the number of rows stamped
     */
    @Modifying
    @Transactional
    @Query("UPDATE ForecastEvaluationEntity e SET e.promptHash = :hash WHERE e.id IN :ids")
    int stampPromptHash(@Param("hash") String hash, @Param("ids") Collection<Long> ids);
}
