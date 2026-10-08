package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.service.pipeline.BestBetFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.gregochr.goldenhour.service.TideFactFixtures.coastal;
import static com.gregochr.goldenhour.service.TideFactFixtures.day;
import static com.gregochr.goldenhour.service.TideFactFixtures.inland;
import static com.gregochr.goldenhour.service.TideFactFixtures.region;
import static com.gregochr.goldenhour.service.TideFactFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL {@link ServedBriefingAssembler#assembleForPlan} with the REAL
 * {@link BriefingHonestyFilter}, to prove that per-location tide facts survive the filter that
 * empties a zero-coverage region's slots.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServedBriefingAssemblerTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 11);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private BestBetFallbackService bestBetFallbackService;
    @Mock
    private BriefingRegionSnapshotService regionSnapshotService;
    @Mock
    private WindowTideRollupBuilder windowTideRollupBuilder;
    @Mock
    private EvaluationViewService evaluationViewService;

    private ServedBriefingAssembler assembler;

    @BeforeEach
    void setUp() {
        when(regionSnapshotService.previousBuild(any()))
                .thenReturn(BriefingRegionSnapshotService.PreviousBuild.none());
        // The enricher is a stub that leaves the hierarchy as cached: the fixture region's
        // scoredLocationCount of zero is then exactly what the honesty filter reads.
        assembler = new ServedBriefingAssembler(bestBetFallbackService, regionSnapshotService,
                windowTideRollupBuilder, evaluationViewService, CLOCK,
                (days, resolver, triaged) -> days);
    }

    private static DailyBriefingResponse snapshot(BriefingEventSummary... summaries) {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "headline",
                List.of(day(D, summaries)), List.of(), null, null, false, false, 0, null,
                List.of(), List.of());
    }

    @Test
    @DisplayName("a zero-coverage coastal region loses its slots to the filter but its window "
            + "keeps every tide fact, verbatim, and the cached snapshot is untouched")
    void tideFactsSurviveTheHonestyFilter() {
        BriefingSlot bamburgh = coastal(1L, "Bamburgh", "HIGH");
        BriefingSlot whitby = coastal(2L, "Whitby", "LOW");
        BriefingSlot durham = inland(3L, "Durham");
        BriefingSlot orphan = coastal(4L, "Orphan", "MID");
        DailyBriefingResponse cached = snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, bamburgh, whitby, durham)), List.of(orphan)));
        DailyBriefingResponse before = snapshot(summary(TargetType.SUNRISE,
                List.of(region("North East", 0, bamburgh, whitby, durham)), List.of(orphan)));

        DailyBriefingResponse served = assembler.assembleForPlan(cached, 0.5);

        BriefingEventSummary es = served.days().get(0).eventSummaries().get(0);
        assertThat(es.regions().get(0).slots()).as("the filter emptied the region").isEmpty();
        assertThat(es.regions().get(0).displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(es.window()).isNotNull();
        assertThat(es.window().tideFacts()).containsExactly(
                LocationTideFact.from(bamburgh), LocationTideFact.from(whitby),
                LocationTideFact.from(orphan));
        assertThat(cached).as("the cached snapshot is not mutated").isEqualTo(before);
        assertThat(cached.days().get(0).eventSummaries().get(0).regions().get(0).slots())
                .containsExactly(bamburgh, whitby, durham);
    }

    @Test
    @DisplayName("a window with no coastal slot carries no tideFacts")
    void inlandWindowCarriesNull() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(summary(
                TargetType.SUNRISE, List.of(region("Dales", 0, inland(1L, "Hawes"))),
                List.of())), 0.5);

        assertThat(served.days().get(0).eventSummaries().get(0).window().tideFacts()).isNull();
    }

    @Test
    @DisplayName("each window gets its own facts")
    void eachWindowItsOwn() {
        DailyBriefingResponse served = assembler.assembleForPlan(snapshot(
                summary(TargetType.SUNRISE,
                        List.of(region("North East", 0, coastal(1L, "Bamburgh", "HIGH"))),
                        List.of()),
                summary(TargetType.SUNSET,
                        List.of(region("North East", 0, coastal(1L, "Bamburgh", "LOW"))),
                        List.of())), 0.5);

        List<BriefingEventSummary> es = served.days().get(0).eventSummaries();
        assertThat(es.get(0).window().tideFacts()).extracting(LocationTideFact::tideState)
                .containsExactly("HIGH");
        assertThat(es.get(1).window().tideFacts()).extracting(LocationTideFact::tideState)
                .containsExactly("LOW");
    }

    @Test
    @DisplayName("a null snapshot still assembles to null")
    void nullSnapshot() {
        assertThat(assembler.assembleForPlan(null, 0.5)).isNull();
    }

    @Test
    @DisplayName("the slotless-days case: a day with no summaries yields no facts and no failure")
    void dayWithoutSummaries() {
        DailyBriefingResponse snap = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0),
                "h", List.of(new BriefingDay(D, List.of())), List.of(), null, null, false, false,
                0, null, List.of(), List.of());

        assertThat(assembler.assembleForPlan(snap, 0.5).days()).hasSize(1);
    }
}
