package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-path (Jackson 2, {@link AppConfig#objectMapper()}) tests for the run extent on
 * {@link TideRunDay}. The Jackson 3 wire format is pinned in {@code BriefingControllerTest}.
 */
class TideRunDayJsonTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 10);
    private static final LocalDate END = LocalDate.of(2026, 10, 14);

    private static TideRunDay day() {
        return new TideRunDay("KING RUN", 2, 3, "MON 12", "Whitby", "4.2 m",
                null, null, "06:41", "17:55", null, List.of(), "v", true, "sunrise", null, false, null);
    }

    @Test
    @DisplayName("the extent round-trips through the persistence mapper")
    void extentRoundTrips() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        String json = mapper.writeValueAsString(day().withRunExtent(START, END));

        TideRunDay loaded = mapper.readValue(json, TideRunDay.class);

        assertThat(loaded.runStart()).isEqualTo(START);
        assertThat(loaded.runEnd()).isEqualTo(END);
        assertThat(loaded).isEqualTo(day().withRunExtent(START, END));
    }

    @Test
    @DisplayName("persisted JSON without the keys (an old cache row) loads with null extent")
    void oldJsonWithoutKeysLoadsAsNull() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(day()));
        assertThat(node.has("runStart")).isFalse();
        assertThat(node.has("runEnd")).isFalse();

        TideRunDay loaded = mapper.readValue(node.toString(), TideRunDay.class);

        assertThat(loaded.runStart()).isNull();
        assertThat(loaded.runEnd()).isNull();
        assertThat(loaded).isEqualTo(day());
    }

    @Test
    @DisplayName("unknown keys are still ignored next to the new ones")
    void unknownKeysIgnored() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(day().withRunExtent(START, END)));
        node.put("someFutureKey", 1);

        TideRunDay loaded = mapper.readValue(node.toString(), TideRunDay.class);

        assertThat(loaded.runEnd()).isEqualTo(END);
    }

    @Test
    @DisplayName("withRunExtent keeps every other component and the original is untouched")
    void withRunExtentCopies() {
        TideRunDay original = day();

        TideRunDay stamped = original.withRunExtent(START, END);

        assertThat(original.runStart()).isNull();
        assertThat(stamped.dayNumber()).isEqualTo(2);
        assertThat(stamped.runLabel()).isEqualTo("KING RUN");
        assertThat(stamped.runStart()).isEqualTo(START);
        assertThat(stamped.runEnd()).isEqualTo(END);
    }
}
