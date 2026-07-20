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
}
