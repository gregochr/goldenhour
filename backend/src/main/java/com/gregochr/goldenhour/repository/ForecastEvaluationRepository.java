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
     * Returns evaluations for a set of locations within a date range, ordered by location name,
     * then target date, then target type.
     *
     * <p>Backs {@code GET /api/forecast/history}: whether the caller passed one location (a
     * singleton collection) or wants every enabled location (the full id list), this is the one
     * query either way — {@code IN :locationIds} lets both shapes share a single round trip
     * instead of one query per location. Ordering by location name first reproduces the order the
     * old per-location loop produced (it iterated {@code findAllEnabled()}, which is itself
     * name-ordered) so callers see byte-for-byte the same sequence.
     *
     * @param locationIds the location primary keys to query (never empty — callers should return
     *                     an empty list without querying rather than pass one)
     * @param from        the start of the date range (inclusive)
     * @param to          the end of the date range (inclusive)
     * @return evaluations ordered by location name ascending, then target date ascending, then
     *         target type ascending
     */
    @Query("SELECT e FROM ForecastEvaluationEntity e JOIN FETCH e.location loc"
            + " WHERE loc.id IN :locationIds"
            + " AND e.targetDate BETWEEN :from AND :to"
            + " ORDER BY loc.name ASC, e.targetDate ASC, e.targetType ASC")
    List<ForecastEvaluationEntity> findByLocationIdInAndTargetDateBetween(
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
     * Returns every evaluation of the given target types over a date range, across the whole
     * roster — regardless of triage or batch state. The range form of
     * {@link #findByTargetDateAndTargetTypeIn}, for a reader that needs several days at once
     * rather than one day per call (e.g. the "Coming up" standing-conditions strip replaying dust
     * presence over a trailing 60-day window, plan {@code docs/engineering/coming-up-plan.md} §7 —
     * see that method's Javadoc for why the complete population, not a survivor surface, is the
     * unbiased read for this column).
     *
     * @param from        the start of the date range (inclusive)
     * @param to          the end of the date range (inclusive)
     * @param targetTypes the target types to include (normally {@code SUNRISE}, {@code SUNSET})
     * @return matching rows, in no guaranteed order
     */
    List<ForecastEvaluationEntity> findByTargetDateBetweenAndTargetTypeIn(
            LocalDate from, LocalDate to, Collection<TargetType> targetTypes);

    /**
     * Returns all evaluation runs for a specific location, date, and target type, ordered
     * chronologically by when the forecast was run. Used to plot forecast convergence over time.
     *
     * @param locationId the location primary key
     * @param targetDate the date being forecast
     * @param targetType SUNRISE or SUNSET
     * @return evaluations ordered by forecast_run_at ascending
     */
    List<ForecastEvaluationEntity> findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
            Long locationId, LocalDate targetDate, TargetType targetType);

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
     * Pairs every rated evaluation with the outcome a photographer recorded for the same slot.
     *
     * <p>Joins {@code forecast_evaluation} to {@code actual_outcome} on the natural key both share
     * — location, date and target type. One slot yields one row per {@code daysAhead}, which is
     * deliberate: it is what lets accuracy be scored per forecast horizon. Callers are expected to
     * keep only the latest {@code forecastRunAt} within each (slot, horizon) group, since a slot
     * can be re-evaluated by both the nightly batch and the intraday refresh.
     *
     * <p>Both ratings must be present; nothing else is filtered. In particular an outcome recorded
     * with {@code wentOut = false} still counts — a photographer can rate a sky they watched from
     * a window — so the flag is projected rather than applied.
     *
     * @param from start of the outcome window (inclusive)
     * @param to   end of the outcome window (inclusive)
     * @return forecast/outcome pairs, unordered
     */
    @Query("SELECT new com.gregochr.goldenhour.model.CalibrationPair("
            + " e.location.name, e.targetDate, e.targetType, e.daysAhead, e.forecastRunAt,"
            + " e.evaluationModel, e.rating, e.fierySkyPotential, e.goldenHourPotential,"
            + " o.actualRating, o.fierySkyActual, o.goldenHourActual, o.wentOut)"
            + " FROM ForecastEvaluationEntity e, ActualOutcomeEntity o"
            + " WHERE e.location.id = o.location.id"
            + " AND e.targetDate = o.outcomeDate"
            + " AND e.targetType = o.targetType"
            + " AND e.rating IS NOT NULL"
            + " AND o.actualRating IS NOT NULL"
            + " AND o.outcomeDate BETWEEN :from AND :to")
    List<CalibrationPair> findCalibrationPairs(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
