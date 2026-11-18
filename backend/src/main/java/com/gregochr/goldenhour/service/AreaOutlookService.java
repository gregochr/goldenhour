package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.AreaOutlookEntry;
import com.gregochr.goldenhour.model.AreaWindowOutlook;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-reader figure behind each area in Find (docs/engineering/find-and-favourites-plan.md D3): for each
 * window from today to the end of the forecast horizon, how many of the area's members are rated, how many are
 * hidden from this reader, and the best rating the reader may see.
 *
 * <p>It is built from the same served scores as {@code /api/briefing/evaluate/scores}
 * ({@link EvaluationViewService#forDateRange}) and shaped by {@link LitePickShaper#shapeScores}, so a gated
 * member is counted and never rated. It is a count and a top figure, never a verdict and never a Best pick;
 * nothing here touches how a rating is made. A window where no member is rated or gated (a day beyond what
 * has been scored) is not reported at all, so no figure is shown for it. The result is the reader's own
 * shaped view and must never be ETag-shared.
 */
@Service
public class AreaOutlookService {

    private final AreaDirectoryService directory;
    private final EvaluationViewService evaluationViewService;
    private final LitePickShaper litePickShaper;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param directory             the areas and their enabled members
     * @param evaluationViewService the served scores
     * @param litePickShaper        shapes the scores for the reader
     * @param clock                 the application clock (UK civil "today")
     */
    public AreaOutlookService(AreaDirectoryService directory, EvaluationViewService evaluationViewService,
            LitePickShaper litePickShaper, Clock clock) {
        this.directory = directory;
        this.evaluationViewService = evaluationViewService;
        this.litePickShaper = litePickShaper;
        this.clock = clock;
    }

    /**
     * The outlook of every area for one reader.
     *
     * @param reader the caller's authentication
     * @return one entry per area that has at least one window with a rated or gated member
     */
    public List<AreaOutlookEntry> outlookFor(Authentication reader) {
        List<AreaDirectoryEntry> areas = directory.directoryFor(reader);
        LocalDate today = ForecastHorizon.today(clock);
        List<LocationEvaluationView> shaped = litePickShaper.shapeScores(
                evaluationViewService.forDateRange(today, today.plusDays(RunType.FORECAST_HORIZON_DAYS),
                        Set.of(TargetType.SUNRISE, TargetType.SUNSET)),
                litePickShaper.scopeFor(reader));
        Map<WindowKey, Map<Long, LocationEvaluationView>> byWindow = new HashMap<>();
        for (LocationEvaluationView view : shaped) {
            byWindow.computeIfAbsent(new WindowKey(view.date(), view.targetType()), k -> new HashMap<>())
                    .put(view.locationId(), view);
        }
        List<WindowKey> windows = byWindow.keySet().stream()
                .sorted(Comparator.comparing(WindowKey::date).thenComparing(WindowKey::targetType)).toList();
        List<AreaOutlookEntry> entries = new ArrayList<>();
        for (AreaDirectoryEntry area : areas) {
            List<AreaWindowOutlook> figures = new ArrayList<>();
            for (WindowKey window : windows) {
                AreaWindowOutlook figure = figure(area, window, byWindow.get(window));
                if (figure != null) {
                    figures.add(figure);
                }
            }
            if (!figures.isEmpty()) {
                entries.add(new AreaOutlookEntry(area.id(), figures));
            }
        }
        return entries;
    }

    private static AreaWindowOutlook figure(AreaDirectoryEntry area, WindowKey window,
            Map<Long, LocationEvaluationView> views) {
        int rated = 0;
        int gated = 0;
        LocationEvaluationView top = null;
        for (Long id : area.locationIds()) {
            LocationEvaluationView view = views.get(id);
            if (view == null) {
                continue;
            }
            if (Boolean.TRUE.equals(view.gated())) {
                gated++;
            } else if (view.rating() != null) {
                rated++;
                if (top == null || RANK.compare(view, top) < 0) {
                    top = view;
                }
            }
        }
        if (rated + gated == 0) {
            return null;
        }
        return new AreaWindowOutlook(window.date(), window.targetType(), rated, area.locationIds().size(), gated,
                top == null ? null : top.rating(), top == null ? null : top.locationId());
    }

    /** Best rating first, then name, then id, so the member that holds the top figure is stable. */
    private static final Comparator<LocationEvaluationView> RANK = Comparator
            .comparing(LocationEvaluationView::rating, Comparator.reverseOrder())
            .thenComparing(LocationEvaluationView::locationName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(LocationEvaluationView::locationId);

    private record WindowKey(LocalDate date, TargetType targetType) {
    }
}
