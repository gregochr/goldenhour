package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import com.gregochr.goldenhour.model.PipelineRunPickComparison;
import com.gregochr.goldenhour.model.PipelineRunPickComparison.PickDiff;
import com.gregochr.goldenhour.model.PipelineRunPickComparison.PickView;
import com.gregochr.goldenhour.repository.PipelineRunPickRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Computes the intraday-vs-nightly best-bet comparison that proves (or
 * disproves) the intraday refresh's worth: for an INTRADAY run, did Plan A or
 * Plan B change versus the same morning's NIGHTLY run?
 *
 * <p>The baseline is the latest NIGHTLY run on the same {@code Europe/London}
 * calendar day, at or before the intraday run's trigger time (so a later
 * nightly run — there is normally only one per day — cannot be mistaken for the
 * morning baseline). Picks are matched by rank (1 = Plan A, 2 = Plan B) and
 * compared on region, event date, event type, and the snapshotted Claude
 * average rating.
 *
 * <p>Returns {@code null} when there is nothing to compare — a non-intraday
 * run, or an intraday run with no same-day nightly baseline (e.g. the very
 * first day intraday runs, or a missed nightly cycle). The caller treats null
 * as "no comparison available".
 */
@Service
public class PipelineRunComparisonService {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * Minimum absolute difference in snapshotted Claude average rating (in
     * stars, 1–5 scale) for the {@code RATING} dimension to count as changed.
     * Half a star — below this is forecast noise, not a plan change.
     */
    static final double RATING_CHANGE_THRESHOLD = 0.5;

    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunPickRepository pickRepository;

    /**
     * Constructs the service.
     *
     * @param pipelineRunRepository pipeline run repository (baseline lookup)
     * @param pickRepository        pick repository (both runs' picks)
     */
    public PipelineRunComparisonService(PipelineRunRepository pipelineRunRepository,
            PipelineRunPickRepository pickRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.pickRepository = pickRepository;
    }

    /**
     * Builds the cross-run comparison for the given run, or returns {@code null}
     * if there is nothing to compare.
     *
     * @param run the run being viewed
     * @return the comparison, or {@code null} for non-intraday runs / no baseline
     */
    public PipelineRunPickComparison compareToSameDayNightly(PipelineRunEntity run) {
        if (run == null || run.getCycleType() != CycleType.INTRADAY
                || run.getTriggerTime() == null) {
            return null;
        }
        LocalDate londonDay = run.getTriggerTime().atZone(LONDON).toLocalDate();
        Instant dayStart = londonDay.atStartOfDay(LONDON).toInstant();
        Optional<PipelineRunEntity> baseline = pipelineRunRepository
                .findFirstByCycleTypeAndTriggerTimeBetweenOrderByTriggerTimeDesc(
                        CycleType.NIGHTLY, dayStart, run.getTriggerTime());
        if (baseline.isEmpty()) {
            return null;
        }
        PipelineRunEntity nightly = baseline.get();

        List<PipelineRunPickEntity> intradayPicks =
                pickRepository.findByPipelineRunIdOrderByPickRankAsc(run.getId());
        List<PipelineRunPickEntity> nightlyPicks =
                pickRepository.findByPipelineRunIdOrderByPickRankAsc(nightly.getId());

        List<PickDiff> diffs = new ArrayList<>();
        for (int rank = 1; rank <= 2; rank++) {
            PipelineRunPickEntity intraday = pickAtRank(intradayPicks, rank);
            PipelineRunPickEntity night = pickAtRank(nightlyPicks, rank);
            if (intraday == null && night == null) {
                continue;
            }
            diffs.add(buildDiff(rank, intraday, night));
        }
        if (diffs.isEmpty()) {
            return null;
        }
        return new PipelineRunPickComparison(
                nightly.getId(), nightly.getTriggerTime(), diffs);
    }

    private static PickDiff buildDiff(int rank, PipelineRunPickEntity intraday,
            PipelineRunPickEntity night) {
        List<String> changed = new ArrayList<>();
        if (intraday == null || night == null) {
            // One run has a pick the other lacks — a plan change by presence.
            changed.add("PRESENCE");
            return new PickDiff(rank, true, changed, view(intraday), view(night));
        }
        if (!Objects.equals(intraday.getRegion(), night.getRegion())) {
            changed.add("REGION");
        }
        if (!Objects.equals(intraday.getEventDate(), night.getEventDate())) {
            changed.add("DATE");
        }
        if (!Objects.equals(normaliseEventType(intraday.getEventType()),
                normaliseEventType(night.getEventType()))) {
            changed.add("EVENT");
        }
        if (ratingChanged(intraday.getClaudeAverageRating(), night.getClaudeAverageRating())) {
            changed.add("RATING");
        }
        return new PickDiff(rank, !changed.isEmpty(), changed, view(intraday), view(night));
    }

    private static boolean ratingChanged(Double a, Double b) {
        if (a == null || b == null) {
            // A rating appearing or disappearing is a change; both-null is not.
            return a != b;
        }
        return Math.abs(a - b) >= RATING_CHANGE_THRESHOLD;
    }

    private static String normaliseEventType(String eventType) {
        return eventType == null ? null : eventType.toLowerCase(java.util.Locale.ROOT);
    }

    private static PipelineRunPickEntity pickAtRank(List<PipelineRunPickEntity> picks, int rank) {
        return picks.stream()
                .filter(p -> p.getPickRank() == rank)
                .findFirst()
                .orElse(null);
    }

    private static PickView view(PipelineRunPickEntity pick) {
        if (pick == null) {
            return null;
        }
        return new PickView(
                pick.getHeadline(),
                pick.getRegion(),
                pick.getEventDate(),
                pick.getEventType(),
                pick.getConfidence(),
                pick.getClaudeAverageRating());
    }
}
