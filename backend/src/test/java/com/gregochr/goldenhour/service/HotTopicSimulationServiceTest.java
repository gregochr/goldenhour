package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HotTopicSimulationService}.
 */
class HotTopicSimulationServiceTest {

    private HotTopicSimulationService service;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 14);

    @BeforeEach
    void setUp() {
        service = new HotTopicSimulationService();
    }

    @Test
    @DisplayName("initial state — disabled, no active types")
    void initialState_disabledWithNoActiveTypes() {
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.getActiveTypes()).isEmpty();
    }

    @Test
    @DisplayName("setEnabled toggles simulation on and off")
    void setEnabled_togglesState() {
        service.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();

        service.setEnabled(false);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("setTypeActive adds type to active set")
    void setTypeActive_true_addsToActiveTypes() {
        service.setTypeActive("BLUEBELL", true);
        assertThat(service.getActiveTypes()).contains("BLUEBELL");
    }

    @Test
    @DisplayName("setTypeActive false removes type from active set")
    void setTypeActive_false_removesFromActiveTypes() {
        service.setTypeActive("BLUEBELL", true);
        service.setTypeActive("BLUEBELL", false);
        assertThat(service.getActiveTypes()).doesNotContain("BLUEBELL");
    }

    @Test
    @DisplayName("getSimulatedTopics returns empty list when simulation disabled")
    void getSimulatedTopics_disabled_returnsEmpty() {
        service.setTypeActive("BLUEBELL", true);
        // enabled remains false

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("getSimulatedTopics returns only active types when enabled")
    void getSimulatedTopics_enabled_returnsOnlyActiveTypes() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);
        service.setTypeActive("AURORA", true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::type)
                .containsExactlyInAnyOrder("BLUEBELL", "AURORA");
    }

    @Test
    @DisplayName("getSimulatedTopics returns empty list when enabled but no types active")
    void getSimulatedTopics_enabledNoTypes_returnsEmpty() {
        service.setEnabled(true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("simulated topic has correct label and non-null detail")
    void getSimulatedTopics_bluebellTopic_hasCorrectFields() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("BLUEBELL");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.detail()).isNotBlank();
        assertThat(topic.date()).isNotNull();
        assertThat(topic.regions()).containsExactly("Northumberland", "The Lake District");
    }

    @Test
    @DisplayName("getAllTypes returns all 15 simulatable types")
    void getAllTypes_returnsAllFifteenTypes() {
        List<HotTopicSimulationService.SimulatableType> types = service.getAllTypes();

        assertThat(types).hasSize(15);
        assertThat(types).extracting(HotTopicSimulationService.SimulatableType::type)
                .containsExactlyInAnyOrder(
                        "BLUEBELL", "KING_TIDE", "SPRING_TIDE", "STORM_SURGE", "AURORA", "DUST",
                        "INVERSION", "SUPERMOON", "SNOW_FRESH", "SNOW_MIST", "SNOW_TOPS",
                        "NLC", "METEOR", "EQUINOX", "CLEARANCE");
    }

    @Test
    @DisplayName("getAllTypes reflects active flags correctly")
    void getAllTypes_reflectsActiveFlags() {
        service.setTypeActive("AURORA", true);

        List<HotTopicSimulationService.SimulatableType> types = service.getAllTypes();

        assertThat(types).filteredOn(t -> t.type().equals("AURORA"))
                .extracting(HotTopicSimulationService.SimulatableType::active)
                .containsExactly(true);
        assertThat(types).filteredOn(t -> !t.type().equals("AURORA"))
                .extracting(HotTopicSimulationService.SimulatableType::active)
                .containsOnly(false);
    }

    @Test
    @DisplayName("disabling simulation preserves active type selections")
    void setEnabled_false_preservesActiveTypeSelections() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);
        service.setTypeActive("SNOW_FRESH", true);

        service.setEnabled(false);

        assertThat(service.getActiveTypes()).containsExactlyInAnyOrder("BLUEBELL", "SNOW_FRESH");
    }

    // ── Date mapping by dayOffset ─────────────────────────────────────────────

    @Test
    @DisplayName("AURORA (dayOffset=0) maps to today — fromDate")
    void getSimulatedTopics_aurora_mapsToToday() {
        service.setEnabled(true);
        service.setTypeActive("AURORA", true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("BLUEBELL (dayOffset=1) maps to tomorrow — fromDate + 1")
    void getSimulatedTopics_bluebell_mapsToTomorrow() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("SPRING_TIDE (dayOffset=2) maps to day after tomorrow — fromDate + 2")
    void getSimulatedTopics_springTide_mapsToDayAfterTomorrow() {
        service.setEnabled(true);
        service.setTypeActive("SPRING_TIDE", true);

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(TODAY.plusDays(2));
    }

    // ── filterAction per template ─────────────────────────────────────────────

    @Test
    @DisplayName("BLUEBELL filterAction is 'BLUEBELL'")
    void getSimulatedTopics_bluebell_filterActionIsBLUEBELL() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.filterAction()).isEqualTo("BLUEBELL");
    }

    @Test
    @DisplayName("AURORA filterAction is null")
    void getSimulatedTopics_aurora_filterActionIsNull() {
        service.setEnabled(true);
        service.setTypeActive("AURORA", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.filterAction()).isNull();
    }

    // ── Priority values per template ──────────────────────────────────────────

    @Test
    @DisplayName("BLUEBELL has priority 1")
    void getSimulatedTopics_bluebell_hasPriority1() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.priority()).isEqualTo(1);
    }

    @Test
    @DisplayName("STORM_SURGE has priority 2")
    void getSimulatedTopics_stormSurge_hasPriority2() {
        service.setEnabled(true);
        service.setTypeActive("STORM_SURGE", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.priority()).isEqualTo(2);
    }

    @Test
    @DisplayName("DUST has priority 3")
    void getSimulatedTopics_dust_hasPriority3() {
        service.setEnabled(true);
        service.setTypeActive("DUST", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.priority()).isEqualTo(3);
    }

    // ── description field populated by withDates() ───────────────────────────

    @Test
    @DisplayName("BLUEBELL simulated topic has a non-blank description")
    void getSimulatedTopics_bluebell_hasNonBlankDescription() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.description())
                .as("BLUEBELL description must be non-null and non-blank")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @DisplayName("KING_TIDE simulated topic has a non-blank description")
    void getSimulatedTopics_kingTide_hasNonBlankDescription() {
        service.setEnabled(true);
        service.setTypeActive("KING_TIDE", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.description()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("AURORA simulated topic has a non-blank description")
    void getSimulatedTopics_aurora_hasNonBlankDescription() {
        service.setEnabled(true);
        service.setTypeActive("AURORA", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.description()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("all 15 simulated topic types have non-blank descriptions")
    void getSimulatedTopics_allTypes_allHaveNonBlankDescriptions() {
        service.setEnabled(true);
        service.getAllTypes().forEach(t -> service.setTypeActive(t.type(), true));

        List<HotTopic> topics = service.getSimulatedTopics(TODAY, TODAY.plusDays(3));

        assertThat(topics).hasSize(15);
        assertThat(topics).extracting(HotTopic::description)
                .as("every simulated topic must have a non-blank description")
                .allSatisfy(desc -> assertThat(desc).isNotNull().isNotBlank());
    }

    // ── Regions stored in dedicated field, not appended to detail ──────────

    @Test
    @DisplayName("AURORA regions field includes 'Northumberland'")
    void getSimulatedTopics_aurora_regionsContainsNorthumberland() {
        service.setEnabled(true);
        service.setTypeActive("AURORA", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.regions()).contains("Northumberland");
    }

    @Test
    @DisplayName("BLUEBELL regions field includes both region names")
    void getSimulatedTopics_bluebell_regionsContainsBothRegions() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.regions()).contains("Northumberland", "The Lake District");
    }

    @Test
    @DisplayName("KING_TIDE regions field includes both coastal regions")
    void getSimulatedTopics_kingTide_regionsContainsBothRegions() {
        service.setEnabled(true);
        service.setTypeActive("KING_TIDE", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.regions()).contains("Northumberland", "The North Yorkshire Coast");
    }

    @Test
    @DisplayName("Detail string does not contain region names (regions in separate field)")
    void getSimulatedTopics_bluebell_detailDoesNotContainRegionNames() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.detail())
                .doesNotContain("Northumberland")
                .doesNotContain("The Lake District");
    }

    // ── Day label in detail string ────────────────────────────────────────────

    @Test
    @DisplayName("AURORA (dayOffset=0) detail contains 'today'")
    void getSimulatedTopics_aurora_detailContainsToday() {
        service.setEnabled(true);
        service.setTypeActive("AURORA", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.detail()).contains("today");
    }

    @Test
    @DisplayName("BLUEBELL (dayOffset=1) detail contains 'tomorrow'")
    void getSimulatedTopics_bluebell_detailContainsTomorrow() {
        service.setEnabled(true);
        service.setTypeActive("BLUEBELL", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        assertThat(topic.detail()).contains("tomorrow");
    }

    @Test
    @DisplayName("SPRING_TIDE (dayOffset=2) detail contains weekday name")
    void getSimulatedTopics_springTide_detailContainsWeekdayName() {
        // TODAY = 2026-04-14 (Tuesday), plusDays(2) = 2026-04-16 (Thursday)
        service.setEnabled(true);
        service.setTypeActive("SPRING_TIDE", true);

        HotTopic topic = service.getSimulatedTopics(TODAY, TODAY.plusDays(3)).get(0);

        // dayOffset=2 → day name (not "today" or "tomorrow")
        assertThat(topic.detail()).doesNotContain("today").doesNotContain("tomorrow");
        assertThat(topic.detail()).contains("Thursday");
    }
}
