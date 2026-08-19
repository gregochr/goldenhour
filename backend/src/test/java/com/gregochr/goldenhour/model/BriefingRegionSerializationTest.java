package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization contract for the {@code confidence} channel added to
 * {@link BriefingRegion}. Uses a plain Jackson 2 {@link ObjectMapper} matching the type the
 * {@code daily_briefing_cache} round-trip injects in {@code BriefingService}. The production
 * bean additionally registers {@code JavaTimeModule}, but {@link BriefingRegion} has no
 * {@code java.time} fields, so a vanilla mapper is equivalent for this record.
 */
class BriefingRegionSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static BriefingRegion region(Confidence confidence) {
        return new BriefingRegion("North East", Verdict.GO, "Clear skies",
                List.of(), List.of(), 10.0, 9.0, 3.0, 1, null, null,
                DisplayVerdict.WORTH_IT, 3, null, false, confidence);
    }

    @Test
    void serialisesConfidenceAsItsLowercaseValue() throws Exception {
        String json = mapper.writeValueAsString(region(Confidence.HIGH));

        assertThat(mapper.readTree(json).get("confidence").asText()).isEqualTo("high");
    }

    @Test
    void legacyPayloadWithoutConfidenceDeserialisesToNull() throws Exception {
        // A briefing cached before the field existed: strip "confidence" from the JSON and confirm
        // deserialization is fail-soft (null), not an exception — every already-cached row depends
        // on this after deploy (the serve path re-derives confidence anyway).
        ObjectNode node = (ObjectNode) mapper.readTree(mapper.writeValueAsString(region(Confidence.HIGH)));
        node.remove("confidence");

        BriefingRegion restored = mapper.treeToValue(node, BriefingRegion.class);

        assertThat(restored.confidence()).isNull();
        assertThat(restored.regionName()).isEqualTo("North East");
    }

    @Test
    void nullConfidenceRoundTrips() throws Exception {
        String json = mapper.writeValueAsString(region(null));

        BriefingRegion restored = mapper.readValue(json, BriefingRegion.class);

        assertThat(restored.confidence()).isNull();
    }

    @Test
    void nonNullConfidenceRoundTripsBackToTheEnum() throws Exception {
        // Guards the @JsonValue reverse-mapping ("low" -> Confidence.LOW) that every cached row
        // written after this change relies on when reloaded at restart. A regression here would
        // throw and the persisted briefing would be silently dropped.
        String json = mapper.writeValueAsString(region(Confidence.LOW));

        BriefingRegion restored = mapper.readValue(json, BriefingRegion.class);

        assertThat(restored.confidence()).isEqualTo(Confidence.LOW);
    }

    // ── bestRating: the heat field's `best N★`, added the same way (P1) ──────

    @Test
    void anUnratedRegionWritesNoBestRatingKeyAtAll() throws Exception {
        // NON_NULL, so a region nothing has scored adds nothing to the stored payload — the
        // `confidence`/`meanRating` precedent, and what makes this a no-migration field. Asserted
        // as key ABSENCE rather than a null value: a written `"bestRating": null` would still be
        // additive to every cached row, and would render as a 0★ best on any client reading the
        // key's presence.
        String json = mapper.writeValueAsString(region(Confidence.HIGH));

        assertThat(mapper.readTree(json).has("bestRating")).isFalse();
    }

    @Test
    void aRatedRegionRoundTripsItsBestRating() throws Exception {
        BriefingRegion region = region(Confidence.HIGH).withBestRating(4);

        BriefingRegion restored = mapper.readValue(
                mapper.writeValueAsString(region), BriefingRegion.class);

        assertThat(restored.bestRating()).isEqualTo(4);
    }

    @Test
    void legacyPayloadWithoutBestRatingDeserialisesToNull() throws Exception {
        // Every already-stored briefing is exactly this on the first serve after deploy. The
        // serve path re-enriches, so the null lives only until the response is rebuilt — but it
        // must not throw on the way there.
        ObjectNode node = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(region(Confidence.HIGH).withBestRating(4)));
        node.remove("bestRating");

        BriefingRegion restored = mapper.treeToValue(node, BriefingRegion.class);

        assertThat(restored.bestRating()).isNull();
        assertThat(restored.regionName()).isEqualTo("North East");
    }

    @Test
    void witheringABestRatingPreservesEveryOtherField() {
        // The trap this record's javadoc names: a wither that rebuilds positionally is how a
        // later-added component gets silently defaulted away. Two of these — confidence and
        // meanRating — are the fields a `withBestRating` written before them would drop.
        BriefingRegion before = region(Confidence.LOW).withMeanRating(2.5);

        BriefingRegion after = before.withBestRating(3);

        assertThat(after.bestRating()).isEqualTo(3);
        assertThat(after.confidence()).isEqualTo(Confidence.LOW);
        assertThat(after.meanRating()).isEqualTo(2.5);
        assertThat(after.regionName()).isEqualTo(before.regionName());
        assertThat(after.displayVerdict()).isEqualTo(before.displayVerdict());
        assertThat(after.scoredLocationCount()).isEqualTo(before.scoredLocationCount());
    }

    @Test
    void theOtherWithersCarryTheBestRatingThrough() {
        // The same trap from the other direction, and the one a new component actually hits: the
        // four withers that existed BEFORE bestRating each rebuild the record positionally, so
        // any of them could drop it. withMeanRating is the one that matters most in production —
        // the enrichment path calls it immediately before withBestRating's sibling.
        BriefingRegion rated = region(Confidence.HIGH).withBestRating(5);

        assertThat(rated.withMeanRating(3.0).bestRating()).isEqualTo(5);
        assertThat(rated.withConfidence(Confidence.LOW).bestRating()).isEqualTo(5);
        assertThat(rated.withLightlyEvaluated().bestRating()).isEqualTo(5);
        assertThat(rated.withGloss("Head", "Detail").bestRating()).isEqualTo(5);
    }
}
