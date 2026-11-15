package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire shape of the five horizon cloud layers on {@link BriefingSlot.WeatherConditions}.
 *
 * <p>Two mappers matter: the Jackson 2 mapper {@code AppConfig} builds, which writes and reads
 * {@code daily_briefing_cache}, and the Jackson 3 ({@code tools.jackson}) mapper Spring MVC serves
 * {@code /api/briefing} through. The layers are flattened into the slot by {@code @JsonUnwrapped}
 * and must appear only when set, so a cached briefing written before this change still reads.
 */
class BriefingSlotHorizonCloudJsonTest {

    private static final ObjectMapper CACHE_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final JsonMapper HTTP_MAPPER = JsonMapper.builder().build();

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 16, 16, 0);

    private static BriefingSlot slotWith(BriefingSlot.WeatherConditions weather) {
        return new BriefingSlot("Angel of the North", AT, Verdict.GO, weather,
                BriefingSlot.TideInfo.NONE, List.of());
    }

    private static BriefingSlot.WeatherConditions observerOnly() {
        return new BriefingSlot.WeatherConditions(18, BigDecimal.ZERO, 20000, 65,
                10.0, 8.0, 0, BigDecimal.valueOf(3.5), 0, 0);
    }

    private static BriefingSlot.WeatherConditions withHorizons() {
        return new BriefingSlot.WeatherConditions(18, BigDecimal.ZERO, 20000, 65,
                10.0, 8.0, 0, BigDecimal.valueOf(3.5), 0, 0, 15, 0, 10, 5, 90);
    }

    @Test
    @DisplayName("Jackson 2 cache mapper: a slot with no horizon layers writes none of the keys")
    void cacheMapper_nullLayers_omitted() throws Exception {
        String json = CACHE_MAPPER.writeValueAsString(slotWith(observerOnly()));

        assertThat(json).doesNotContain("sunSide").doesNotContain("antiSunSide");
    }

    @Test
    @DisplayName("Jackson 3 HTTP mapper: a slot with no horizon layers writes none of the keys")
    void httpMapper_nullLayers_omitted() {
        JsonNode node = HTTP_MAPPER.readTree(HTTP_MAPPER.writeValueAsString(slotWith(observerOnly())));

        assertThat(node.has("sunSideLowCloudPercent")).isFalse();
        assertThat(node.has("sunSideMidCloudPercent")).isFalse();
        assertThat(node.has("sunSideHighCloudPercent")).isFalse();
        assertThat(node.has("antiSunSideMidCloudPercent")).isFalse();
        assertThat(node.has("antiSunSideHighCloudPercent")).isFalse();
        assertThat(node.get("lowCloudPercent").asInt()).isEqualTo(18);
    }

    @Test
    @DisplayName("Jackson 3 HTTP mapper: set layers are flattened into the slot beside the observer figures")
    void httpMapper_setLayers_flattened() {
        JsonNode node = HTTP_MAPPER.readTree(HTTP_MAPPER.writeValueAsString(slotWith(withHorizons())));

        assertThat(node.get("sunSideLowCloudPercent").asInt()).isEqualTo(15);
        assertThat(node.get("sunSideMidCloudPercent").asInt()).isZero();
        assertThat(node.get("sunSideHighCloudPercent").asInt()).isEqualTo(10);
        assertThat(node.get("antiSunSideMidCloudPercent").asInt()).isEqualTo(5);
        assertThat(node.get("antiSunSideHighCloudPercent").asInt()).isEqualTo(90);
        assertThat(node.get("highCloudPercent").asInt()).isZero();
    }

    @Test
    @DisplayName("Jackson 2 cache mapper: a round trip preserves the horizon layers")
    void cacheMapper_roundTrip_preservesLayers() throws Exception {
        String json = CACHE_MAPPER.writeValueAsString(slotWith(withHorizons()));
        BriefingSlot back = CACHE_MAPPER.readValue(json, BriefingSlot.class);

        assertThat(back.weather()).isEqualTo(withHorizons());
    }

    @Test
    @DisplayName("Jackson 2 cache mapper: a cached slot written before the layers existed reads them as null")
    void cacheMapper_oldJson_readsNull() throws Exception {
        String old = CACHE_MAPPER.writeValueAsString(slotWith(observerOnly()));
        BriefingSlot back = CACHE_MAPPER.readValue(old, BriefingSlot.class);

        assertThat(back.weather().sunSideLowCloudPercent()).isNull();
        assertThat(back.weather().sunSideMidCloudPercent()).isNull();
        assertThat(back.weather().sunSideHighCloudPercent()).isNull();
        assertThat(back.weather().antiSunSideMidCloudPercent()).isNull();
        assertThat(back.weather().antiSunSideHighCloudPercent()).isNull();
        assertThat(back.weather().lowCloudPercent()).isEqualTo(18);
    }
}
