package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpArgs;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskToolArguments.BadArguments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AskToolArguments}: the model's tool arguments are untrusted. */
class AskToolArgumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    @DisplayName("every rank_spots field is read, and a JSON null reads as absent")
    void rankSpotsFields() throws Exception {
        RankSpotsArgs a = AskToolArguments.rankSpots(json("""
                {"windowIds": ["2026-10-05_sunset"], "regionNames": ["Northumberland"],
                 "coastalOnly": true, "tideState": "HIGH", "maxDriveMinutes": 60, "limit": 3,
                 "unknown": "ignored"}"""));

        assertThat(a.windowIds()).containsExactly("2026-10-05_sunset");
        assertThat(a.regionNames()).containsExactly("Northumberland");
        assertThat(a.coastalOnly()).isTrue();
        assertThat(a.tideState()).isEqualTo("HIGH");
        assertThat(a.maxDriveMinutes()).isEqualTo(60);
        assertThat(a.limit()).isEqualTo(3);

        RankSpotsArgs nulls = AskToolArguments.rankSpots(json("{\"limit\": null, \"tideState\": null}"));
        assertThat(nulls.limit()).isNull();
        assertThat(nulls.tideState()).isNull();
    }

    @Test
    @DisplayName("a missing, null or missing-node input is an empty object: every argument is optional")
    void emptyInputs() throws Exception {
        assertThat(AskToolArguments.rankSpots(null)).isEqualTo(new RankSpotsArgs(null, null, null, null, null, null));
        assertThat(AskToolArguments.hotTopics(json("null"))).isEqualTo(new HotTopicsArgs(null, null));
        assertThat(AskToolArguments.comingUp(json("{}"))).isEqualTo(new ComingUpArgs(null, null));
        assertThat(AskToolArguments.comingUp(mapper.missingNode())).isEqualTo(new ComingUpArgs(null, null));
    }

    @Test
    @DisplayName("hot topics and coming up arguments are read")
    void otherTools() throws Exception {
        assertThat(AskToolArguments.hotTopics(json("{\"types\": [\"AURORA\", \"ECLIPSE\"], \"limit\": 4}")))
                .isEqualTo(new HotTopicsArgs(List.of("AURORA", "ECLIPSE"), 4));
        assertThat(AskToolArguments.comingUp(json("{\"days\": 30, \"limit\": 2}")))
                .isEqualTo(new ComingUpArgs(30, 2));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "[]", "\"x\"", "7", "true",
            "{\"limit\": \"5\"}", "{\"limit\": 5.5}", "{\"limit\": 5.0}", "{\"limit\": 3000000000}",
            "{\"limit\": true}", "{\"coastalOnly\": \"yes\"}", "{\"coastalOnly\": 1}",
            "{\"tideState\": 5}", "{\"windowIds\": \"2026-10-05_sunset\"}", "{\"windowIds\": [1]}",
            "{\"regionNames\": [null]}", "{\"maxDriveMinutes\": \"60\"}"
    })
    @DisplayName("a field of the wrong type is a bad-arguments error, which the engine feeds back")
    void wrongTypesAreBadArguments(String input) {
        assertThatThrownBy(() -> AskToolArguments.rankSpots(json(input)))
                .isInstanceOf(BadArguments.class)
                .hasMessageNotContaining("Exception");
    }

    @Test
    @DisplayName("the int boundary: the largest int is read, one more is bad")
    void intBoundary() throws Exception {
        assertThat(AskToolArguments.comingUp(json("{\"days\": 2147483647}")).days()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> AskToolArguments.comingUp(json("{\"days\": 2147483648}")))
                .isInstanceOf(BadArguments.class);
    }
}
