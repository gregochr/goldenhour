package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Unit tests for {@link BestBetEnricher}: day labels (Today / Tomorrow / weekday), the event type,
 * and the UK-local event time looked up from the briefing days.
 */
class BestBetEnricherTest {

    /** Sunday 21 June 2026, midday UTC (1pm BST). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC);

    private final BestBetEnricher enricher = new BestBetEnricher(CLOCK);

    private static BestBet pick(String event) {
        return new BestBet(1, "headline", "detail", event, "Northumberland", Confidence.HIGH, 30,
                null, null, null, null, null);
    }

    private static BriefingSlot slot(LocalDateTime solarEventTimeUtc) {
        return new BriefingSlot("Bamburgh", solarEventTimeUtc, Verdict.GO,
                new BriefingSlot.WeatherConditions(10, java.math.BigDecimal.ZERO, 20000, 60,
                        12.0, 10.0, 1, java.math.BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of());
    }

    private static BriefingDay day(LocalDate date, TargetType type, BriefingSlot... slots) {
        BriefingRegion region = new BriefingRegion("Northumberland", Verdict.GO, "summary",
                List.of(), List.of(slots), 10.5, 8.0, 3.2, 1, null, null, DisplayVerdict.UNRATED, 1);
        return new BriefingDay(date, List.of(new BriefingEventSummary(type, List.of(region), List.of())));
    }

    private BestBet enrichOne(String event, List<BriefingDay> days) {
        List<BestBet> result = enricher.enrichWithEventData(List.of(pick(event)), days);
        assertThat(result).hasSize(1);
        return result.get(0);
    }

    @Test
    @DisplayName("a stay-home pick (no event) is returned unchanged")
    void stayHomePick_unchanged() {
        BestBet stayHome = new BestBet(1, "Stay in", "d", null, null, Confidence.LOW, null,
                null, null, null, null, null);

        assertThat(enricher.enrichWithEventData(List.of(stayHome), List.of())).containsExactly(stayHome);
    }

    @Test
    @DisplayName("an aurora event dated today is labelled Today, 'aurora', 'after dark'")
    void auroraToday() {
        BestBet enriched = enrichOne("2026-06-21_aurora", List.of());

        assertThat(enriched.dayName()).isEqualTo("Today");
        assertThat(enriched.eventType()).isEqualTo("aurora");
        assertThat(enriched.eventTime()).isEqualTo("after dark");
        assertThat(enriched.headline()).isEqualTo("headline");
    }

    @Test
    @DisplayName("an aurora event dated tomorrow is labelled Tomorrow")
    void auroraTomorrow() {
        assertThat(enrichOne("2026-06-22_aurora", List.of()).dayName()).isEqualTo("Tomorrow");
    }

    @Test
    @DisplayName("an aurora event further out is labelled with the full weekday name")
    void auroraLater() {
        // 2026-06-25 is a Thursday.
        assertThat(enrichOne("2026-06-25_aurora", List.of()).dayName()).isEqualTo("Thursday");
    }

    @Test
    @DisplayName("a sunset event today takes the UK-local time of the first slot (UTC 20:30 is 21:30 BST)")
    void sunsetToday_ukLocalTime() {
        BestBet enriched = enrichOne("2026-06-21_sunset",
                List.of(day(LocalDate.of(2026, 6, 21), TargetType.SUNSET,
                        slot(LocalDateTime.of(2026, 6, 21, 20, 30)))));

        assertThat(enriched.dayName()).isEqualTo("Today");
        assertThat(enriched.eventType()).isEqualTo("sunset");
        assertThat(enriched.eventTime()).isEqualTo("21:30");
    }

    @Test
    @DisplayName("a sunrise event tomorrow is labelled Tomorrow")
    void sunriseTomorrow() {
        BestBet enriched = enrichOne("2026-06-22_sunrise",
                List.of(day(LocalDate.of(2026, 6, 22), TargetType.SUNRISE,
                        slot(LocalDateTime.of(2026, 6, 22, 3, 55)))));

        assertThat(enriched.dayName()).isEqualTo("Tomorrow");
        assertThat(enriched.eventTime()).isEqualTo("04:55");
    }

    @Test
    @DisplayName("a sunset event further out is labelled with the weekday")
    void sunsetLater() {
        assertThat(enrichOne("2026-06-24_sunset", List.of()).dayName()).isEqualTo("Wednesday");
    }

    @Test
    @DisplayName("an event id with no event-type part is returned unchanged")
    void eventWithoutType_unchanged() {
        BestBet original = pick("2026-06-21");

        assertThat(enricher.enrichWithEventData(List.of(original), List.of())).containsExactly(original);
    }

    @Test
    @DisplayName("an unrecognised event type keeps its text but has no event time")
    void unknownEventType_noTime() {
        BestBet enriched = enrichOne("2026-06-21_noon",
                List.of(day(LocalDate.of(2026, 6, 21), TargetType.SUNSET,
                        slot(LocalDateTime.of(2026, 6, 21, 20, 30)))));

        assertThat(enriched.eventType()).isEqualTo("noon");
        assertThat(enriched.eventTime()).isNull();
    }

    @Test
    @DisplayName("no briefing day for the event's date means no event time")
    void noMatchingDay_noTime() {
        BestBet enriched = enrichOne("2026-06-21_sunset",
                List.of(day(LocalDate.of(2026, 6, 22), TargetType.SUNSET,
                        slot(LocalDateTime.of(2026, 6, 22, 20, 30)))));

        assertThat(enriched.eventTime()).isNull();
    }

    @Test
    @DisplayName("a day whose summary is for the other event type gives no event time")
    void otherTargetTypeOnly_noTime() {
        BestBet enriched = enrichOne("2026-06-21_sunset",
                List.of(day(LocalDate.of(2026, 6, 21), TargetType.SUNRISE,
                        slot(LocalDateTime.of(2026, 6, 21, 3, 55)))));

        assertThat(enriched.eventTime()).isNull();
    }

    @Test
    @DisplayName("slots with no solar event time are skipped")
    void slotWithoutSolarTime_noTime() {
        BestBet enriched = enrichOne("2026-06-21_sunset",
                List.of(day(LocalDate.of(2026, 6, 21), TargetType.SUNSET, slot(null))));

        assertThat(enriched.eventTime()).isNull();
    }
}
