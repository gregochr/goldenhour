package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.WarningDto;
import com.gregochr.goldenhour.model.WarningsResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpCounts;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.warnings.WarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ComingUpWarningMerger}: which warnings get an entry, what the entry says, where it sorts, and
 * the guarantees that make the per-request merge safe (same instance when nothing is added, never a mutation of the
 * cached feed, role-free, fail-soft).
 */
@ExtendWith(MockitoExtension.class)
class ComingUpWarningMergerTest {

    /** A Wednesday; the scored days are 18 to 21 November. */
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @Mock
    private WarningService warningService;

    private ComingUpWarningMerger merger;

    @BeforeEach
    void setUp() {
        merger = new ComingUpWarningMerger(warningService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WarningsResponse enabled(WarningDto... warnings) {
        return new WarningsResponse(true, NOW, false, List.of(warnings), "https://example.test/", "Attribution");
    }

    private static WarningDto warning(String id, String level, List<String> types, String from, String to,
            String upgradedFrom) {
        return new WarningDto(id, "2.0", level, types, 3, 2, from == null ? null : Instant.parse(from),
                to == null ? null : Instant.parse(to), null, null, "Headline " + id, List.of(), null, null, null,
                true, List.of(), null, upgradedFrom, null, Set.of(7L), false);
    }

    private static WarningDto beyond(String id) {
        return warning(id, "AMBER", List.of("SNOW", "WIND"), "2026-11-19T12:00:00Z", "2026-11-24T06:00:00Z", null);
    }

    private static ComingUpEntry almanac(String type, LocalDate start) {
        return ComingUpEntry.from(new AlmanacEvent(start, start, AlmanacKind.ALMANAC, type, "Title " + type, "d",
                Map.of(), List.of()), TODAY.minusDays(80));
    }

    private static ComingUpResponse feed(ComingUpEntry... entries) {
        return new ComingUpResponse(TODAY, null, new ComingUpCounts(2, 1, Map.of("coastal", 1)), List.of(entries));
    }

    @Test
    @DisplayName("a null feed stays null")
    void nullFeed() {
        assertThat(merger.merge(null, 90)).isNull();
    }

    @Test
    @DisplayName("feature off: the very same feed instance comes back")
    void featureOff() {
        ComingUpResponse feed = feed(almanac("spring-tide", TODAY.plusDays(10)));
        when(warningService.served()).thenReturn(
                new WarningsResponse(false, null, true, List.of(), "https://example.test/", "Attribution"));

        assertThat(merger.merge(feed, 90)).isSameAs(feed);
    }

    @Test
    @DisplayName("enabled with nothing in force: the same instance, and no all-clear entry is invented")
    void nothingInForce() {
        ComingUpResponse feed = feed(almanac("spring-tide", TODAY.plusDays(10)));
        when(warningService.served()).thenReturn(enabled());

        assertThat(merger.merge(feed, 90)).isSameAs(feed);
    }

    @Test
    @DisplayName("a failing lookup is fail-soft: the unmerged feed")
    void lookupFails() {
        ComingUpResponse feed = feed(almanac("spring-tide", TODAY.plusDays(10)));
        when(warningService.served()).thenThrow(new IllegalStateException("db down"));

        assertThat(merger.merge(feed, 90)).isSameAs(feed);
    }

    @Test
    @DisplayName("a warning wholly inside the scored days has no Coming up entry (Plan owns those days)")
    void insideScoredDaysIsSkipped() {
        ComingUpResponse feed = feed();
        when(warningService.served()).thenReturn(enabled(
                warning("W1", "AMBER", List.of("WIND"), "2026-11-18T08:00:00Z", "2026-11-21T22:00:00Z", null)));

        assertThat(merger.merge(feed, 90)).isSameAs(feed);
    }

    @Test
    @DisplayName("a warning reaching past the scored days gets an entry with its true span, words and a plan door")
    void entryShape() {
        ComingUpResponse feed = feed(almanac("spring-tide", TODAY.plusDays(10)));
        when(warningService.served()).thenReturn(enabled(beyond("W1")));

        ComingUpResponse merged = merger.merge(feed, 90);

        assertThat(merged.entries()).hasSize(2);
        ComingUpEntry entry = merged.entries().getFirst();
        assertThat(entry.type()).isEqualTo("weather-warning");
        assertThat(entry.id()).isEqualTo("weather-warning:W1");
        assertThat(entry.family()).isEqualTo("warning");
        assertThat(entry.kind()).isEqualTo(AlmanacKind.FORECAST);
        assertThat(entry.kindTag()).isEqualTo("Met Office warning");
        assertThat(entry.title()).isEqualTo("Amber warning of snow and wind");
        assertThat(entry.detail()).isEqualTo("Headline W1");
        assertThat(entry.startDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(entry.endDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(entry.interim()).isTrue();
        assertThat(entry.bits()).isNull();
        assertThat(entry.facts()).isEmpty();
        assertThat(entry.meta()).containsEntry("warningId", "W1").containsEntry("warningVersion", "2.0")
                .containsEntry("level", "AMBER").containsEntry("weatherTypes", "SNOW,WIND")
                .containsEntry("validFrom", "2026-11-19T12:00:00Z").containsEntry("validTo", "2026-11-24T06:00:00Z")
                .containsEntry("updated", "true").doesNotContainKey("upgradedFrom");
        assertThat(entry.action().kind()).isEqualTo("plan");
        assertThat(entry.action().date()).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    @Test
    @DisplayName("a warning that starts after the scored days offers the Met Office link, not a plan door")
    void whollyBeyondHasMetOfficeAction() {
        when(warningService.served()).thenReturn(enabled(
                warning("W1", "YELLOW", List.of("RAIN"), "2026-11-23T00:00:00Z", "2026-11-24T06:00:00Z", null)));

        ComingUpEntry entry = merger.merge(feed(), 90).entries().getFirst();

        assertThat(entry.action().kind()).isEqualTo("met-office");
        assertThat(entry.action().date()).isNull();
        assertThat(entry.startDate()).isEqualTo(LocalDate.of(2026, 11, 23));
    }

    @Test
    @DisplayName("a warning already under way is reported with its true start, the plan door opens on today")
    void underWayKeepsTrueStart() {
        when(warningService.served()).thenReturn(enabled(
                warning("W1", "RED", List.of("SNOW"), "2026-11-16T09:00:00Z", "2026-11-23T09:00:00Z", null)));

        ComingUpEntry entry = merger.merge(feed(), 90).entries().getFirst();

        assertThat(entry.startDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(entry.action().date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("a missing or reversed start falls back to today; a missing end or id is skipped")
    void unusableDates() {
        ComingUpResponse feed = feed();
        when(warningService.served()).thenReturn(enabled(
                warning("NOFROM", "AMBER", List.of("WIND"), null, "2026-11-23T09:00:00Z", null),
                warning("REVERSED", "AMBER", List.of("WIND"), "2026-11-30T09:00:00Z", "2026-11-23T09:00:00Z", null),
                warning("NOEND", "AMBER", List.of("WIND"), "2026-11-19T09:00:00Z", null, null),
                warning(null, "AMBER", List.of("WIND"), "2026-11-19T09:00:00Z", "2026-11-23T09:00:00Z", null)));

        List<ComingUpEntry> entries = merger.merge(feed, 90).entries();

        assertThat(entries).extracting(ComingUpEntry::id)
                .containsExactly("weather-warning:NOFROM", "weather-warning:REVERSED");
        assertThat(entries).extracting(ComingUpEntry::startDate).containsExactly(TODAY, TODAY);
    }

    @Test
    @DisplayName("a warning that starts after the requested horizon is left out")
    void beyondHorizon() {
        ComingUpResponse feed = feed();
        when(warningService.served()).thenReturn(enabled(
                warning("W1", "AMBER", List.of("WIND"), "2026-11-23T00:00:00Z", "2026-11-24T06:00:00Z", null)));

        // days=3 reaches 20 November; 6 reaches 23 November; an absurd value is clamped, not rejected.
        assertThat(merger.merge(feed, 3)).isSameAs(feed);
        assertThat(merger.merge(feed, 6).entries()).hasSize(1);
        assertThat(merger.merge(feed, 100000).entries()).hasSize(1);
        assertThat(merger.merge(feed, 0)).isSameAs(feed);
    }

    @Test
    @DisplayName("counts gain the warnings under forecast and the warning family; a null counts stays null")
    void counts() {
        when(warningService.served()).thenReturn(enabled(beyond("W1"), beyond("W2")));

        ComingUpResponse merged = merger.merge(feed(), 90);
        ComingUpResponse nullCounts = merger.merge(new ComingUpResponse(TODAY, null, null, List.of()), 90);

        assertThat(merged.counts().fixed()).isEqualTo(2);
        assertThat(merged.counts().forecast()).isEqualTo(3);
        assertThat(merged.counts().byFamily()).containsEntry("coastal", 1).containsEntry("warning", 2);
        assertThat(nullCounts.counts()).isNull();
        assertThat(merged.builtFor()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("an upgraded warning floats to the top of its day; the rest follow the existing entries")
    void upgradedFloatsToTopOfItsDay() {
        ComingUpEntry earlier = almanac("meteor-shower", TODAY);
        ComingUpEntry sameDayA = almanac("spring-tide", LocalDate.of(2026, 11, 19));
        ComingUpEntry sameDayB = almanac("supermoon", LocalDate.of(2026, 11, 19));
        ComingUpEntry later = almanac("solstice", LocalDate.of(2026, 11, 25));
        WarningDto plain = beyond("PLAIN");
        WarningDto raised = warning("RAISED", "AMBER", List.of("WIND"), "2026-11-19T06:00:00Z",
                "2026-11-24T06:00:00Z", "YELLOW");
        when(warningService.served()).thenReturn(enabled(plain, raised));

        List<ComingUpEntry> entries = merger.merge(feed(earlier, sameDayA, sameDayB, later), 90).entries();

        assertThat(entries).extracting(ComingUpEntry::id).containsExactly(
                earlier.id(), "weather-warning:RAISED", sameDayA.id(), sameDayB.id(), "weather-warning:PLAIN",
                later.id());
        assertThat(entries.get(1).meta()).containsEntry("upgradedFrom", "YELLOW");
    }

    @Test
    @DisplayName("the merge never mutates the cached feed it was handed")
    void inputIsNotMutated() {
        ComingUpResponse cached = feed(almanac("spring-tide", TODAY.plusDays(10)));
        when(warningService.served()).thenReturn(enabled(beyond("W1")));

        ComingUpResponse merged = merger.merge(cached, 90);

        assertThat(merged).isNotSameAs(cached);
        assertThat(cached.entries()).hasSize(1);
        assertThat(cached.counts().forecast()).isEqualTo(1);
        assertThat(cached.counts().byFamily()).doesNotContainKey("warning");
    }

    @Test
    @DisplayName("titles: unknown level and types are printed from the served values, never dropped")
    void titles() {
        assertThat(ComingUpWarningMerger.title(warning("a", null, null, null, null, null)))
                .isEqualTo("Weather warning");
        assertThat(ComingUpWarningMerger.title(warning("a", " ", List.of(), null, null, null)))
                .isEqualTo("Weather warning");
        assertThat(ComingUpWarningMerger.title(warning("a", "PURPLE", List.of("EXTREME_HEAT"), null, null, null)))
                .isEqualTo("Purple warning of extreme heat");
        assertThat(ComingUpWarningMerger.title(warning("a", "Red",
                Arrays.asList("RAIN", null, " ", "ICE", "FOG"), null, null, null)))
                .isEqualTo("Red warning of rain, ice and fog");
    }

    @Test
    @DisplayName("a warning with no version, types or headline still produces a minimal, valid entry")
    void minimalMeta() {
        WarningDto bare = new WarningDto("W9", null, "YELLOW", null, null, null,
                Instant.parse("2026-11-22T00:00:00Z"), Instant.parse("2026-11-23T00:00:00Z"), null, null, null, null,
                null, null, null, false, null, null, null, null, null, false);
        when(warningService.served()).thenReturn(enabled(bare));

        ComingUpEntry entry = merger.merge(feed(), 90).entries().getFirst();

        assertThat(entry.title()).isEqualTo("Yellow warning");
        assertThat(entry.detail()).isNull();
        assertThat(entry.meta()).doesNotContainKeys("warningVersion", "weatherTypes", "upgradedFrom")
                .containsEntry("updated", "false");
    }

    @Test
    @DisplayName("the role shaper leaves a warning entry exactly as it is, so Lite reads what Pro reads")
    void roleShaperPassesWarningEntriesThrough() {
        when(warningService.served()).thenReturn(enabled(beyond("W1")));
        ComingUpResponse merged = merger.merge(feed(), 90);

        ComingUpResponse lite = new ComingUpRoleShaper().forLite(merged);

        assertThat(lite.entries()).isEqualTo(merged.entries());
        assertThat(lite.entries().getFirst().lightRowsWithheld()).isFalse();
    }
}
