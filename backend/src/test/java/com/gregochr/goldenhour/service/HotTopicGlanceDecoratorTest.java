package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.model.TideRunDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HotTopicGlanceDecorator}, one case per row of the coming-up-glance plan's
 * §2.2 table. Real records throughout; the decorator has no collaborators.
 */
class HotTopicGlanceDecoratorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 25);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final LocalDate D3 = D1.plusDays(2);

    private final HotTopicGlanceDecorator decorator = new HotTopicGlanceDecorator();

    private static HotTopic topic(String type, LocalDate date) {
        return new HotTopic(type, type, "detail", date, 1, null, List.of(), null, null);
    }

    private static SurgeRunDay surge(String peak, String highWater, Double... metres) {
        return new SurgeRunDay("Seaham", Arrays.asList(metres), List.of(), peak, "01:00", highWater,
                "07:00", "17:00", "v", false, "d", "p");
    }

    private static TideRunDay tide(int dayNumber, int dayCount, String range, boolean peak) {
        return new TideRunDay("run", dayNumber, dayCount, "day", "Seaham", range, null, null, "07:00", "17:00", null,
        List.of(), "v", false, null, null, peak,
                null);
    }

    private GlanceLine glanceOf(HotTopic t) {
        List<HotTopic> out = decorator.decorate(List.of(t));
        assertThat(out).hasSize(1);
        return out.getFirst().glance();
    }

    // ── Storm surge ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("surge prints HW time and the served peak string exactly once")
    void surge_printsHighWaterAndPeakAsServed() {
        HotTopic t = topic("STORM_SURGE", D1).withSurgeRun(surge("+0.72 m", "16:02", 0.72));

        GlanceLine glance = glanceOf(t);

        assertThat(glance.value()).isEqualTo("HW 16:02 · +0.72 m");
        assertThat(glance.weather()).isTrue();
    }

    @Test
    @DisplayName("PEAK lands on every surge topic dated the peak date, sunrise and sunset alike")
    void surge_peakOnBothEventsOfThePeakDate() {
        HotTopic lowSunrise = topic("STORM_SURGE", D1).withEvent("SUNRISE", "07:00")
                .withSurgeRun(surge("+0.40 m", "06:00", 0.40));
        HotTopic peakSunrise = topic("STORM_SURGE", D2).withEvent("SUNRISE", "07:00")
                .withSurgeRun(surge("+0.72 m", "06:30", 0.72));
        HotTopic peakSunset = topic("STORM_SURGE", D2).withEvent("SUNSET", "17:00")
                .withSurgeRun(surge("+0.72 m", "18:30", 0.72));

        List<HotTopic> out = decorator.decorate(List.of(lowSunrise, peakSunrise, peakSunset));

        assertThat(out.get(0).glance().badge()).isNull();
        assertThat(out.get(0).glance().shortValue()).isEqualTo("+0.40 m");
        assertThat(out.get(1).glance().badge()).isEqualTo("PEAK");
        assertThat(out.get(1).glance().shortValue()).isEqualTo("+0.72 m pk");
        assertThat(out.get(2).glance().badge()).isEqualTo("PEAK");
    }

    @Test
    @DisplayName("a tie on the largest surge goes to the earliest date")
    void surge_tieGoesToEarliestDate() {
        HotTopic later = topic("STORM_SURGE", D3).withSurgeRun(surge("+0.60 m", "10:00", 0.60));
        HotTopic earlier = topic("STORM_SURGE", D2).withSurgeRun(surge("+0.60 m", "09:00", 0.60));

        List<HotTopic> out = decorator.decorate(List.of(later, earlier));

        assertThat(out.get(0).glance().badge()).isNull();
        assertThat(out.get(1).glance().badge()).isEqualTo("PEAK");
    }

    @Test
    @DisplayName("null surge metres are ignored when picking the peak")
    void surge_nullMetresIgnored() {
        HotTopic nulls = topic("STORM_SURGE", D1).withSurgeRun(surge("+0.10 m", "10:00", null, 0.10, null));
        HotTopic bigger = topic("STORM_SURGE", D2).withSurgeRun(surge("+0.30 m", "11:00", null, 0.30));

        List<HotTopic> out = decorator.decorate(List.of(nulls, bigger));

        assertThat(out.get(0).glance().badge()).isNull();
        assertThat(out.get(1).glance().badge()).isEqualTo("PEAK");
    }

    @Test
    @DisplayName("a surge with no surgeRun has a null value and no badge")
    void surge_nullSurgeRun_nullValue() {
        GlanceLine glance = glanceOf(topic("STORM_SURGE", D1));

        assertThat(glance).isEqualTo(new GlanceLine(null, null, null, true));
    }

    @Test
    @DisplayName("a surge with no highWaterTime drops the HW half")
    void surge_nullHighWaterTime_dropsHwHalf() {
        HotTopic t = topic("STORM_SURGE", D1).withSurgeRun(surge("+0.72 m", null, 0.72));

        GlanceLine glance = glanceOf(t);

        assertThat(glance.value()).isEqualTo("+0.72 m");
        assertThat(glance.shortValue()).isEqualTo("+0.72 m pk");
    }

    // ── Tides ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tide prints its range, and says 'run starts' only on day 1 of a multi-day run")
    void tide_runStartsOnlyWhenRunHasMoreThanOneDay() {
        HotTopic start = topic("SPRING_TIDE", D1).withTideRun(tide(1, 3, "5.6 m", false));
        HotTopic single = topic("KING_TIDE", D1).withTideRun(tide(1, 1, "6.1 m", false));
        HotTopic middle = topic("SPRING_TIDE", D2).withTideRun(tide(2, 3, "5.8 m", false));

        List<HotTopic> out = decorator.decorate(List.of(start, single, middle));

        assertThat(out.get(0).glance().value()).isEqualTo("5.6 m · run starts");
        assertThat(out.get(0).glance().shortValue()).isEqualTo("5.6 m");
        assertThat(out.get(1).glance().value()).isEqualTo("6.1 m");
        assertThat(out.get(2).glance().value()).isEqualTo("5.8 m");
        assertThat(out).allSatisfy(t -> assertThat(t.glance().weather()).isFalse());
    }

    @Test
    @DisplayName("the peak tide day carries PEAK and a ' pk' short value")
    void tide_peakDay() {
        HotTopic t = topic("KING_TIDE", D2).withTideRun(tide(2, 3, "6.4 m", true));

        GlanceLine glance = glanceOf(t);

        assertThat(glance.badge()).isEqualTo("PEAK");
        assertThat(glance.shortValue()).isEqualTo("6.4 m pk");
        assertThat(glance.value()).isEqualTo("6.4 m");
    }

    @Test
    @DisplayName("a tide with no tideRun has a null value")
    void tide_nullTideRun() {
        assertThat(glanceOf(topic("SPRING_TIDE", D1)))
                .isEqualTo(new GlanceLine(null, null, null, false));
    }

    // ── Supermoon ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("supermoon prints the rise time from the 'rises' fact, with FULL")
    void supermoon_risesFactParsed() {
        HotTopic t = topic("SUPERMOON", D1).withEvent("NIGHT", "17:45").withScience(List.of(
                HotTopicFact.metric("perigee", "+7% larger · 99% lit"),
                HotTopicFact.directional("rises", "16:34, just after sunset", "ENE", false)), null);

        GlanceLine glance = glanceOf(t);

        assertThat(glance).isEqualTo(new GlanceLine("rise 16:34", "full", "FULL", false));
    }

    @Test
    @DisplayName("a supermoon with no 'rises' fact keeps FULL with a null value, never eventTime")
    void supermoon_noRisesFact_keepsFullNullValue() {
        HotTopic t = topic("SUPERMOON", D1).withEvent("NIGHT", "17:45");

        GlanceLine glance = glanceOf(t);

        assertThat(glance).isEqualTo(new GlanceLine(null, "full", "FULL", false));
    }

    // ── Dust ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dust prints the AOD fact, and the bare number on the phone")
    void dust_aodFact() {
        HotTopic t = topic("DUST", D1).withScience(List.of(
                HotTopicFact.context("afterglow"),
                HotTopicFact.metric("AOD", "0.45 · thick")), null);

        assertThat(glanceOf(t)).isEqualTo(new GlanceLine("0.45 · thick", "0.45", null, true));
    }

    @Test
    @DisplayName("dust with no AOD fact has null lines but stays weather-driven")
    void dust_noAod() {
        assertThat(glanceOf(topic("DUST", D1))).isEqualTo(new GlanceLine(null, null, null, true));
    }

    // ── First-fact types ────────────────────────────────────────────────────

    @Test
    @DisplayName("weather types with no strategy glance print the first served fact value")
    void weatherTypes_firstFact() {
        for (String type : List.of("CLEARANCE", "SNOW_TOPS", "SNOW_MIST", "SNOW_FRESH", "AURORA", "NLC")) {
            HotTopic t = topic(type, D1).withScience(List.of(HotTopicFact.metric("k", "first"),
                    HotTopicFact.metric("k2", "second")), null);

            assertThat(glanceOf(t)).as(type).isEqualTo(new GlanceLine("first", "first", null, true));
        }
    }

    @Test
    @DisplayName("calendar types print the first served fact value and are not weather-driven")
    void calendarTypes_firstFact() {
        for (String type : List.of("METEOR", "EQUINOX", "ECLIPSE", "LUNAR_ECLIPSE", "BLUEBELL")) {
            HotTopic t = topic(type, D1).withScience(List.of(HotTopicFact.metric("k", "first")), null);

            assertThat(glanceOf(t)).as(type).isEqualTo(new GlanceLine("first", "first", null, false));
        }
    }

    @Test
    @DisplayName("a first-fact type with no facts has null lines")
    void firstFactType_noFacts() {
        assertThat(glanceOf(topic("AURORA", D1))).isEqualTo(new GlanceLine(null, null, null, true));
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unstamped weather type (simulated inversion or mist) is still marked WX")
    void unstampedWeatherType_markedWeather() {
        assertThat(glanceOf(topic("INVERSION", D1))).isEqualTo(new GlanceLine(null, null, null, true));
        assertThat(glanceOf(topic("MIST", D1))).isEqualTo(new GlanceLine(null, null, null, true));
        assertThat(glanceOf(topic("SEA_MIST", D1))).isEqualTo(new GlanceLine(null, null, null, true));
    }

    @Test
    @DisplayName("an unknown type keeps a null glance")
    void unknownType_nullGlance() {
        assertThat(glanceOf(topic("MYSTERY", D1))).isNull();
    }

    @Test
    @DisplayName("an existing glance is never overwritten")
    void existingGlance_neverOverwritten() {
        GlanceLine own = new GlanceLine("dawn · 9/10", "9/10", null, true);
        HotTopic inversion = topic("INVERSION", D1).withGlance(own);
        HotTopic dust = topic("DUST", D1).withGlance(own).withScience(
                List.of(HotTopicFact.metric("AOD", "0.45 · thick")), null);

        List<HotTopic> out = decorator.decorate(List.of(inversion, dust));

        assertThat(out.get(0).glance()).isSameAs(own);
        assertThat(out.get(1).glance()).isSameAs(own);
    }

    @Test
    @DisplayName("an empty list decorates to an empty list, and order is preserved")
    void emptyAndOrder() {
        assertThat(decorator.decorate(List.of())).isEmpty();
        List<HotTopic> in = List.of(topic("DUST", D1), topic("MYSTERY", D2), topic("AURORA", D3));

        assertThat(decorator.decorate(in)).extracting(HotTopic::type)
                .containsExactly("DUST", "MYSTERY", "AURORA");
    }

    @Test
    @DisplayName("the weather set is exactly the eleven plan types")
    void weatherSet_isThePlanSet() {
        assertThat(HotTopicGlanceDecorator.WEATHER_TYPES).containsExactlyInAnyOrder(
                "STORM_SURGE", "MIST", "SEA_MIST", "INVERSION", "DUST", "CLEARANCE",
                "SNOW_TOPS", "SNOW_MIST", "SNOW_FRESH", "AURORA", "NLC");
    }
}
