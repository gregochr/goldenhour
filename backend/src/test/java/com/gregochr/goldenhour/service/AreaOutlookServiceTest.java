package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.AreaOutlookEntry;
import com.gregochr.goldenhour.model.AreaWindowOutlook;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link AreaOutlookService}: the per-window counts and top figure, with {@code LitePickShaper} (the real
 * one, over mocked picks) deciding what a Lite reader may see. A gated member is counted and never rated.
 */
@ExtendWith(MockitoExtension.class)
class AreaOutlookServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2019-03-12T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = ForecastHorizon.today(CLOCK);
    private static final Set<TargetType> EVENTS = Set.of(TargetType.SUNRISE, TargetType.SUNSET);

    @Mock
    private AreaDirectoryService directory;

    @Mock
    private EvaluationViewService evaluationViewService;

    @Mock
    private BriefingService briefingService;

    @Mock
    private UserSubRegionService picks;

    @Mock
    private SubRegionProperties properties;

    private Authentication reader;

    private AreaOutlookService service;

    @BeforeEach
    void setUp() {
        service = new AreaOutlookService(directory, evaluationViewService,
                new LitePickShaper(picks, properties), CLOCK, briefingService);
    }

    /** A Lite reader "anna", enforcement on, whose picks cover the given location rows. */
    private void lite(SubRegionLocationRow... picked) {
        reader = new TestingAuthenticationToken("anna", "n/a", "ROLE_LITE_USER");
        when(properties.isEnforce()).thenReturn(true);
        when(picks.pickedLocationRows("anna")).thenReturn(List.of(picked));
    }

    /** An unrestricted reader: Pro, so enforcement does not shape anything. */
    private void pro() {
        reader = new TestingAuthenticationToken("pro", "n/a", "ROLE_PRO_USER");
        when(properties.isEnforce()).thenReturn(true);
    }

    private static LocationEvaluationView view(long id, String name, LocalDate date, TargetType type,
            Integer rating) {
        return new LocationEvaluationView(id, name, 3L, "North East", date, type,
                LocationEvaluationView.Source.CACHED_EVALUATION, rating, null, null, null, null, null, "SONNET",
                null, DisplayVerdict.WORTH_IT, null, null, null, null);
    }

    private void stub(List<LocationEvaluationView> views) {
        when(directory.directoryFor(reader)).thenReturn(List.of(
                new AreaDirectoryEntry(7L, "Hadrian's Wall", AreaKind.NAMED, List.of(), null, null,
                        List.of(1L, 2L, 3L)),
                new AreaDirectoryEntry(8L, "Empty Edge", AreaKind.NAMED, List.of(), null, null, List.of(9L))));
        when(evaluationViewService.forDateRange(TODAY, TODAY.plusDays(RunType.FORECAST_HORIZON_DAYS), EVENTS))
                .thenReturn(views);
    }

    @Test
    @DisplayName("an unrestricted reader gets rated counts, the top rating and the member that holds it")
    void unrestricted_countsAndTop() {
        pro();
        stub(List.of(
                view(1L, "Housesteads", TODAY, TargetType.SUNSET, 3),
                view(2L, "Cawfields", TODAY, TargetType.SUNSET, 4),
                view(3L, "Steel Rigg", TODAY, TargetType.SUNSET, null),
                view(1L, "Housesteads", TODAY.plusDays(1), TargetType.SUNRISE, 2)));

        List<AreaOutlookEntry> outlook = service.outlookFor(reader);

        assertThat(outlook).hasSize(1);
        assertThat(outlook.get(0).areaId()).isEqualTo(7L);
        assertThat(outlook.get(0).windows()).containsExactly(
                new AreaWindowOutlook(TODAY, TargetType.SUNSET, 2, 3, 0, 4, 2L),
                new AreaWindowOutlook(TODAY.plusDays(1), TargetType.SUNRISE, 1, 3, 0, 2, 1L));
    }

    @Test
    @DisplayName("a tie for the top rating goes to the earlier name, then the lower id")
    void tie_isStable() {
        pro();
        stub(List.of(
                view(1L, "Housesteads", TODAY, TargetType.SUNRISE, 4),
                view(2L, "Cawfields", TODAY, TargetType.SUNRISE, 4)));

        assertThat(service.outlookFor(reader).get(0).windows().get(0).topLocationId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a shaped (Lite) reader: gated members are counted, never rated, and never the top")
    void shaped_gatedCountedNeverRated() {
        lite(new SubRegionLocationRow(100L, 1L, "Housesteads", 55.0, -2.0));
        stub(List.of(
                view(1L, "Housesteads", TODAY, TargetType.SUNSET, 3),
                view(2L, "Cawfields", TODAY, TargetType.SUNSET, 5),
                view(3L, "Steel Rigg", TODAY, TargetType.SUNSET, 5)));

        AreaWindowOutlook window = service.outlookFor(reader).get(0).windows().get(0);

        assertThat(window.ratedMembers()).isEqualTo(1);
        assertThat(window.gatedMembers()).isEqualTo(2);
        assertThat(window.totalMembers()).isEqualTo(3);
        assertThat(window.topRating()).isEqualTo(3);
        assertThat(window.topLocationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a window where every member is gated reports the count with no top figure")
    void allGated_noTop() {
        lite();
        stub(List.of(view(2L, "Cawfields", TODAY, TargetType.SUNSET, 5)));

        AreaWindowOutlook window = service.outlookFor(reader).get(0).windows().get(0);

        assertThat(window.ratedMembers()).isZero();
        assertThat(window.gatedMembers()).isEqualTo(1);
        assertThat(window.topRating()).isNull();
        assertThat(window.topLocationId()).isNull();
    }

    @Test
    @DisplayName("a window with no rated and no gated member is not reported; an area with none is omitted")
    void unscoredWindow_noFigure() {
        pro();
        stub(List.of(view(3L, "Steel Rigg", TODAY, TargetType.SUNSET, null)));

        assertThat(service.outlookFor(reader)).isEmpty();
    }

    private static BriefingSlot slot(long id, String name, Integer rating, Boolean outlier) {
        return new BriefingSlot(id, name, LocalDateTime.of(2019, 3, 12, 16, 0), Verdict.GO, null, null, List.of(),
                rating, rating, null, null, null, DisplayVerdict.WORTH_IT, null, false, null, outlier);
    }

    private static DailyBriefingResponse briefingWith(BriefingSlot... slots) {
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "prose", List.of(), List.of(slots),
                null, null, null, null, null, null, DisplayVerdict.WORTH_IT, 2, "Worth it", Confidence.HIGH, 3.5,
                5, 0.4, true, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of(),
                LocalDateTime.of(2019, 3, 12, 16, 0), null);
        return new DailyBriefingResponse(LocalDateTime.of(2019, 3, 12, 9, 0), "headline",
                List.of(new BriefingDay(TODAY, List.of(summary))), List.of(), null, null, false, "Opus", List.of(),
                List.of());
    }

    @Test
    @DisplayName("a neighbour outlier still counts as rated but never supplies the top figure")
    void outlier_neverTheTop() {
        pro();
        stub(List.of(
                view(1L, "Lone Spike", TODAY, TargetType.SUNSET, 5),
                view(2L, "Supported", TODAY, TargetType.SUNSET, 4)));
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefingWith(
                slot(1L, "Lone Spike", 5, Boolean.TRUE), slot(2L, "Supported", 4, null)));

        AreaWindowOutlook window = service.outlookFor(reader).get(0).windows().get(0);

        assertThat(window.ratedMembers()).isEqualTo(2);
        assertThat(window.topRating()).isEqualTo(4);
        assertThat(window.topLocationId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("when every rated member is an outlier there is a count and no top figure")
    void allOutliers_noTop() {
        pro();
        stub(List.of(view(1L, "Lone Spike", TODAY, TargetType.SUNSET, 5)));
        when(briefingService.getCachedBriefingForApi())
                .thenReturn(briefingWith(slot(1L, "Lone Spike", 5, Boolean.TRUE)));

        AreaWindowOutlook window = service.outlookFor(reader).get(0).windows().get(0);

        assertThat(window.ratedMembers()).isEqualTo(1);
        assertThat(window.topRating()).isNull();
    }
}
