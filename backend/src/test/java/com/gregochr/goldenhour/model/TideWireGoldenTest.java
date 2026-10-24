package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TideState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden wire shapes of the window tide block and the per-location tide fact, through the Jackson 3
 * ({@code tools.jackson}) mapper the HTTP response chain uses.
 *
 * <p>The PRO and ADMIN strings below are the pre-gate shapes, byte for byte: widening
 * {@code tideAligned} to {@code Boolean} and {@code windowPosition}/{@code windowLevel} to
 * {@code Double} must change nothing for a reader who is served them (tide-pro-gate-plan.md §7.1
 * #3). The LITE strings pin what the strip leaves on the wire.
 */
class TideWireGoldenTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static BriefingWindowTide fullTide() {
        return new BriefingWindowTide("Whitby", TideState.MID,
                BriefingWindowTide.Direction.FALLING, "HW", "19:28", "1h43 before sunset",
                "4.9 m", "1.2 m above an average tide", "0.3 m · smooth",
                List.of(0.0, 0.5, 1.0), 0.88, 0.42, 0.25, 0.75,
                List.of(new BriefingWindowTide.Extreme("HW", 0.8, "19:28")), "3.1 m");
    }

    private static LocationTideFact fullFact() {
        return new LocationTideFact(7L, "Bamburgh", "HIGH", true, 0.61, true,
                "HW 06:12 · 25m after sunrise", 0.83, "RISING", "4.9 m", "wants higher",
                "Tide suits this spot");
    }

    @Test
    @DisplayName("PRO/ADMIN: the full window tide serialises exactly as it did before the type change")
    void fullTideWireIsUnchanged() {
        assertThat(mapper.writeValueAsString(fullTide())).isEqualTo("{"
                + "\"locationName\":\"Whitby\",\"state\":\"MID\",\"direction\":\"FALLING\","
                + "\"nearestType\":\"HW\",\"nearestTime\":\"19:28\","
                + "\"nearestOffset\":\"1h43 before sunset\",\"range\":\"4.9 m\","
                + "\"rangeAnomaly\":\"1.2 m above an average tide\",\"seas\":\"0.3 m · smooth\","
                + "\"curve\":[0.0,0.5,1.0],\"windowPosition\":0.88,\"windowLevel\":0.42,"
                + "\"sunrisePosition\":0.25,\"sunsetPosition\":0.75,"
                + "\"extremes\":[{\"kind\":\"HW\",\"position\":0.8,\"time\":\"19:28\"}],"
                + "\"heightAtWindow\":\"3.1 m\"}");
    }

    @Test
    @DisplayName("PRO/ADMIN: the full tide fact serialises exactly as it did before the type change")
    void fullFactWireIsUnchanged() {
        assertThat(mapper.writeValueAsString(fullFact())).isEqualTo("{"
                + "\"locationId\":7,\"locationName\":\"Bamburgh\",\"tideState\":\"HIGH\","
                + "\"tideAligned\":true,\"tideAlignmentQuality\":0.61,\"tideOnTheLight\":true,"
                + "\"nearestSolarOffsetPhrase\":\"HW 06:12 · 25m after sunrise\","
                + "\"tideLevel\":0.83,\"tideDirection\":\"RISING\",\"tideHeight\":\"4.9 m\","
                + "\"tideShortfall\":\"wants higher\",\"tideFitPhrase\":\"Tide suits this spot\"}");
    }

    @Test
    @DisplayName("A false tideAligned still serialises as false (a miss is not a missing value)")
    void falseAlignedStillWritten() {
        LocationTideFact miss = new LocationTideFact(null, "Whitby", "LOW", false, null, null,
                null, null, null, null, null, null);

        assertThat(mapper.writeValueAsString(miss)).isEqualTo(
                "{\"locationName\":\"Whitby\",\"tideState\":\"LOW\",\"tideAligned\":false}");
    }

    @Test
    @DisplayName("LITE: the stripped window tide keeps an empty curve and omits the rest")
    void liteTideWire() {
        assertThat(mapper.writeValueAsString(fullTide().forLite())).isEqualTo("{"
                + "\"locationName\":\"Whitby\",\"state\":\"MID\",\"direction\":\"FALLING\","
                + "\"nearestType\":\"HW\",\"nearestTime\":\"19:28\","
                + "\"nearestOffset\":\"1h43 before sunset\",\"range\":\"4.9 m\","
                + "\"rangeAnomaly\":\"1.2 m above an average tide\",\"seas\":\"0.3 m · smooth\","
                + "\"curve\":[]}");
    }

    @Test
    @DisplayName("LITE: the stripped tide fact keeps the state and light facts and omits the match")
    void liteFactWire() {
        assertThat(mapper.writeValueAsString(fullFact().forLite())).isEqualTo("{"
                + "\"locationId\":7,\"locationName\":\"Bamburgh\",\"tideState\":\"HIGH\","
                + "\"tideOnTheLight\":true,"
                + "\"nearestSolarOffsetPhrase\":\"HW 06:12 · 25m after sunrise\","
                + "\"tideDirection\":\"RISING\"}");
    }
}
