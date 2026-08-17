package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.TargetType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Serialization contract for the three components Phase 3 added to persisted records:
 * {@code DailyBriefingResponse.renderedEvents}, {@code BriefingDay.peak} and
 * {@code BriefingRegion.meanRating}.
 *
 * <p>The briefing response is stored as JSON in {@code daily_briefing_cache} and re-read on
 * restart, so two properties matter and neither is visible from the projector's own tests. The two
 * <b>projector-derived</b> fields must be omitted entirely when absent, so a stored briefing is
 * unchanged and no migration is needed. And every field must deserialise fail-soft from a row
 * cached before it existed, because every already-stored briefing is exactly that on the first
 * serve after deploy.
 *
 * <p><b>{@code meanRating} is deliberately in a different position and is asserted differently.</b>
 * It is derived on the enrichment path, which also runs at build time, so it <em>is</em> written to
 * the stored payload — additive, not absent. What it must do is survive the round trip and tolerate
 * its own absence. Writing that distinction down here is the point: the "stored payload unchanged"
 * claim is true of two of these three and stating it of all three would be false.
 *
 * <p>Uses the {@code JavaTimeModule}-registered mapper the cache round-trip uses, since two of the
 * three carry temporal types.
 */
class PlanPayloadFieldSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final LocalDate DATE = LocalDate.of(2026, 5, 23);

    private static BriefingRegion region(Double meanRating) {
        BriefingRegion base = new BriefingRegion("North East", Verdict.GO, "summary", List.of(),
                List.of(), 14.0, 13.0, 4.5, 3, "Breaking clear", "Detail.",
                DisplayVerdict.WORTH_IT, 2);
        return meanRating == null ? base : base.withMeanRating(meanRating);
    }

    private static DailyBriefingResponse response(List<BriefingDay> days,
            List<PlanRenderedEvent> rendered) {
        DailyBriefingResponse base = new DailyBriefingResponse(
                LocalDateTime.of(DATE, java.time.LocalTime.NOON), "headline", days,
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
        return rendered == null ? base : base.withPlan(days, rendered);
    }

    private static BriefingDay day(BriefingDayPeak peak) {
        BriefingDay base = new BriefingDay(DATE, List.of(
                new BriefingEventSummary(TargetType.SUNSET, List.of(region(4.5)), List.of())));
        return peak == null ? base : base.withPeak(peak);
    }

    // ── The two serve-time fields: absent means ABSENT ───────────────────────

    @Test
    @DisplayName("an unprojected response writes no renderedEvents key at all")
    void renderedEventsIsOmittedWhenAbsent() throws Exception {
        // Not `"renderedEvents": null` — a key at all would change every stored briefing and make
        // the "no migration" claim false the moment anyone diffed the column.
        ObjectNode json = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(response(List.of(day(null)), null)));

        assertThat(json.has("renderedEvents")).isFalse();
    }

    @Test
    @DisplayName("an unprojected day writes no peak key at all")
    void peakIsOmittedWhenAbsent() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(day(null)));

        assertThat(json.has("peak")).isFalse();
    }

    @Test
    @DisplayName("a projected response writes both, and they survive the round trip")
    void projectedFieldsRoundTrip() throws Exception {
        BriefingDayPeak peak = new BriefingDayPeak(DisplayVerdict.WORTH_IT,
                List.of(TargetType.SUNSET),
                List.of(new BriefingDayPeak.PeakRegion("North East", TargetType.SUNSET,
                        DisplayVerdict.WORTH_IT)));
        DailyBriefingResponse projected = response(List.of(day(peak)),
                List.of(new PlanRenderedEvent(DATE, TargetType.SUNSET)));

        DailyBriefingResponse back = mapper.readValue(
                mapper.writeValueAsString(projected), DailyBriefingResponse.class);

        assertThat(back.renderedEvents())
                .containsExactly(new PlanRenderedEvent(DATE, TargetType.SUNSET));
        assertThat(back.days().get(0).peak()).isEqualTo(peak);
    }

    // ── Absent and empty are different answers ───────────────────────────────

    @Test
    @DisplayName("an EMPTY rendered list survives as empty, never as absent")
    void anEmptyRenderedListIsNotNormalisedAway() throws Exception {
        // The client branches on exactly this: absent means "nothing projected this response" and
        // it degrades to walking the days itself; empty means "the projector ran and found no live
        // window" and it draws nothing. Collapsing the two would make an entirely elapsed forecast
        // render every past window.
        DailyBriefingResponse back = mapper.readValue(
                mapper.writeValueAsString(response(List.of(day(null)), List.of())),
                DailyBriefingResponse.class);

        assertThat(back.renderedEvents()).isNotNull().isEmpty();
    }

    // ── Legacy rows: every field deserialises fail-soft ──────────────────────

    @Test
    @DisplayName("a row cached before these fields existed still reads, with each one null")
    void aLegacyRowDeserialisesFailSoft() throws Exception {
        // Every stored briefing is this on the first serve after deploy. The three keys are
        // stripped rather than set to null, because that is what an older writer actually produced.
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(
                response(List.of(day(new BriefingDayPeak(DisplayVerdict.MAYBE, List.of(),
                        List.of()))), List.of(new PlanRenderedEvent(DATE, TargetType.SUNSET)))));
        json.remove("renderedEvents");
        ((ObjectNode) json.get("days").get(0)).remove("peak");
        ((ObjectNode) json.get("days").get(0).get("eventSummaries").get(0)
                .get("regions").get(0)).remove("meanRating");

        DailyBriefingResponse back = mapper.readValue(
                mapper.writeValueAsString(json), DailyBriefingResponse.class);

        assertThat(back.renderedEvents()).isNull();
        assertThat(back.days().get(0).peak()).isNull();
        assertThat(back.days().get(0).eventSummaries().get(0).regions().get(0).meanRating())
                .isNull();
    }

    // ── meanRating: persisted, and it must round-trip ────────────────────────

    @Test
    @DisplayName("meanRating IS written to the stored payload, and survives the round trip")
    void meanRatingIsPersistedAndRoundTrips() throws Exception {
        // Unlike the two above. It is derived by the enrichment pass, which runs at build time as
        // well as at serve time, so a stored briefing gains this key — an additive change to the
        // JSON column, needing no migration but not leaving the payload byte-identical either.
        String json = mapper.writeValueAsString(region(3.5));

        assertThat(json).contains("\"meanRating\":3.5");
        assertThat(mapper.readValue(json, BriefingRegion.class).meanRating()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("an unscored region writes no meanRating key, so a zero is never stored")
    void meanRatingIsOmittedWhenNull() throws Exception {
        // Null and 0.0 are different claims — "not rated" against "rated, and worthless" — and the
        // cell renders them differently. A key written as null would still be honest; the omission
        // is what keeps an unscored region's stored shape identical to what it was before.
        assertThat(mapper.writeValueAsString(region(null))).doesNotContain("meanRating");
    }
}
