package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Canonical merged view of a location's evaluation state for a given date and target type.
 *
 * <p><b>The four light times are location-derived, not evaluation-derived.</b> They are pure
 * astronomy for this location on this date — computed at serve time from lat/lon, never persisted,
 * and independent of whether anything scored the slot. They ride this record because the drill-down
 * needs them beside the rating for the same location + event (the location-sheet superset plan,
 * Phase 2), and because they are location-derived rather than home-derived they are safe on the
 * ETag-revalidated {@code /api/briefing/evaluate/scores} payload — the privacy seam that keeps
 * everything under {@code /api/user/settings} off a revalidatable path is untouched by them.
 *
 * <p>Combines data from {@code cached_evaluation} (batch/SSE-scored results) and
 * {@code forecast_evaluation} (triage stand-down rows and legacy scored rows) with
 * clear precedence: cached evaluation wins, then scored forecast rows, then triage rows.
 *
 * @param locationId          the location primary key
 * @param locationName        the location display name
 * @param regionId            the region primary key (nullable for unregioned locations)
 * @param regionName          the region display name (nullable for unregioned locations)
 * @param date                the forecast date
 * @param targetType          SUNRISE or SUNSET
 * @param source              which data source provided this view
 * @param rating              1-5 star rating, or null if triaged/absent
 * @param summary             Claude's plain-English explanation, or null
 * @param fierySkyPotential   fiery sky score 0-100, or null
 * @param goldenHourPotential golden hour score 0-100, or null
 * @param triageReason        categorised stand-down reason, or null if scored
 * @param triageMessage       formatted stand-down explanation, or null if scored
 * @param evaluationModel     the model that produced the evaluation, or null
 * @param evaluatedAt         when the evaluation was produced, or null
 * @param displayVerdict      unified colour/label signal derived from Claude rating
 *                            (primary) or triage verdict (fallback); never null
 * @param goldenHourStart     start of the golden hour for this location/date/event, UTC, or null
 * @param goldenHourEnd       end of the golden hour, UTC, or null
 * @param blueHourStart       start of the blue hour, UTC, or null
 * @param blueHourEnd         end of the blue hour, UTC, or null
 * @param gated               {@code TRUE} when the view is outside the reader's sub-region picks and its
 *                            rating fields were removed by {@code LitePickShaper}, otherwise null (never
 *                            {@code FALSE}, so an unshaped body is byte-identical to before the field).
 *                            Gated is not unrated: the slot has a rating the reader may not see
 */
public record LocationEvaluationView(
        Long locationId,
        String locationName,
        Long regionId,
        String regionName,
        LocalDate date,
        TargetType targetType,
        Source source,
        Integer rating,
        String summary,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        TriageReason triageReason,
        String triageMessage,
        String evaluationModel,
        Instant evaluatedAt,
        DisplayVerdict displayVerdict,
        LocalDateTime goldenHourStart,
        LocalDateTime goldenHourEnd,
        LocalDateTime blueHourStart,
        LocalDateTime blueHourEnd,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean gated
) {

    /**
     * Twenty-component form, defaulting {@code gated} to null (an unshaped view). Retained so every call
     * site that predates the field keeps compiling unchanged; the withers thread {@code gated} through.
     *
     * @param locationId          the location primary key
     * @param locationName        the location display name
     * @param regionId            the region primary key, or null
     * @param regionName          the region display name, or null
     * @param date                the forecast date
     * @param targetType          SUNRISE or SUNSET
     * @param source              which data source provided this view
     * @param rating              1-5 star rating, or null
     * @param summary             Claude's plain-English explanation, or null
     * @param fierySkyPotential   fiery sky score 0-100, or null
     * @param goldenHourPotential golden hour score 0-100, or null
     * @param triageReason        categorised stand-down reason, or null
     * @param triageMessage       formatted stand-down explanation, or null
     * @param evaluationModel     the model that produced the evaluation, or null
     * @param evaluatedAt         when the evaluation was produced, or null
     * @param displayVerdict      unified colour/label signal
     * @param goldenHourStart     start of the golden hour, UTC, or null
     * @param goldenHourEnd       end of the golden hour, UTC, or null
     * @param blueHourStart       start of the blue hour, UTC, or null
     * @param blueHourEnd         end of the blue hour, UTC, or null
     */
    public LocationEvaluationView(Long locationId, String locationName, Long regionId, String regionName,
            LocalDate date, TargetType targetType, Source source, Integer rating, String summary,
            Integer fierySkyPotential, Integer goldenHourPotential, TriageReason triageReason,
            String triageMessage, String evaluationModel, Instant evaluatedAt, DisplayVerdict displayVerdict,
            LocalDateTime goldenHourStart, LocalDateTime goldenHourEnd, LocalDateTime blueHourStart,
            LocalDateTime blueHourEnd) {
        this(locationId, locationName, regionId, regionName, date, targetType, source, rating, summary,
                fierySkyPotential, goldenHourPotential, triageReason, triageMessage, evaluationModel,
                evaluatedAt, displayVerdict, goldenHourStart, goldenHourEnd, blueHourStart, blueHourEnd, null);
    }

    /**
     * Returns a copy of this view carrying the four golden/blue hour boundary times.
     *
     * <p>The light times are attached in one place rather than threaded through every branch of
     * the merge, because they are answered by a different question from everything else on this
     * record: the merge decides <em>what was said about</em> this slot, while the light times are
     * true of the slot whatever anyone said. Keeping the wither here rather than in the service
     * puts it beside the components it copies, so a new component cannot be added without the
     * copy going stale in view.
     *
     * @param goldenStart start of the golden hour, UTC, or null
     * @param goldenEnd   end of the golden hour, UTC, or null
     * @param blueStart   start of the blue hour, UTC, or null
     * @param blueEnd     end of the blue hour, UTC, or null
     * @return a copy with the light times set
     */
    public LocationEvaluationView withLightTimes(LocalDateTime goldenStart, LocalDateTime goldenEnd,
            LocalDateTime blueStart, LocalDateTime blueEnd) {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType, source,
                rating, summary, fierySkyPotential, goldenHourPotential,
                triageReason, triageMessage, evaluationModel, evaluatedAt, displayVerdict,
                goldenStart, goldenEnd, blueStart, blueEnd, gated);
    }

    /**
     * Returns a copy of this view as a LITE reader may see it: the Fiery Sky and Golden Hour
     * potentials and the written {@link #summary} are null, and a {@link TriageReason#TIDE_MISALIGNED}
     * stand-down is served as {@link TriageReason#GENERIC} with no message (it states the water
     * against the light, a Pro fact); everything else (the star rating, the verdict, any other
     * stand-down reason, the light times) unchanged.
     *
     * @return a new view with the Pro-only fields removed
     */
    public LocationEvaluationView forLite() {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType, source,
                rating, null, null, null,
                TriageReason.forLite(triageReason),
                triageReason == TriageReason.TIDE_MISALIGNED ? null : triageMessage,
                evaluationModel, evaluatedAt, displayVerdict,
                goldenHourStart, goldenHourEnd, blueHourStart, blueHourEnd, gated);
    }

    /**
     * Returns a copy of this view for a reader whose sub-region picks do not cover its location: the star,
     * the written why, both potentials, the stand-down reason and message, the display verdict, and the
     * provenance that would reveal one (the source, which tells a stand-down from a score, the model and
     * the time) are removed, and {@link #gated} is set. The source becomes {@link Source#NONE}: nothing is
     * served to this reader, which is not the same as nothing existing, and {@code gated} says which. The
     * identity, the date, the event and the light times stay.
     *
     * @return a gated copy
     */
    public LocationEvaluationView forGated() {
        return new LocationEvaluationView(
                locationId, locationName, regionId, regionName, date, targetType, Source.NONE,
                null, null, null, null,
                null, null, null, null, null,
                goldenHourStart, goldenHourEnd, blueHourStart, blueHourEnd, Boolean.TRUE);
    }

    /**
     * Indicates which data source provided this evaluation view.
     */
    public enum Source {
        /** From the {@code cached_evaluation} table (batch or SSE). */
        CACHED_EVALUATION,
        /** Scored row from {@code forecast_evaluation}. */
        FORECAST_EVALUATION_SCORED,
        /** Triage stand-down row from {@code forecast_evaluation}. */
        FORECAST_EVALUATION_TRIAGE,
        /** No evaluation data found anywhere. */
        NONE
    }
}
