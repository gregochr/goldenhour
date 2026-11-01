package com.gregochr.goldenhour.service.ask;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AskToolSchemas}: the schemas Claude sees match the argument records of
 * {@link AskTools} and the reply contract of {@link AskAnswerParser}, field for field.
 */
class AskToolSchemasTest {

    private static Tool tool(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Set<String> properties(Tool tool) {
        return new TreeSet<>(tool.inputSchema().properties().orElseThrow()._additionalProperties().keySet());
    }

    private static Set<String> components(Class<? extends Record> type) {
        return new TreeSet<>(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
    }

    private static JsonNode property(Tool tool, String name) {
        return tool.inputSchema().properties().orElseThrow()._additionalProperties().get(name)
                .convert(JsonNode.class);
    }

    @Test
    @DisplayName("six tools, submit_answer last, named by the constants the engine dispatches on")
    void toolNames() {
        List<Tool> tools = AskToolSchemas.tools(true);

        assertThat(tools).extracting(Tool::name).containsExactly(AskToolSchemas.LIST_WINDOWS,
                AskToolSchemas.RANK_SPOTS, AskToolSchemas.RANK_NIGHT_SPOTS, AskToolSchemas.GET_HOT_TOPICS,
                AskToolSchemas.GET_COMING_UP, AskToolSchemas.SUBMIT_ANSWER);
        assertThat(tools).allSatisfy(t -> assertThat(t.description()).isPresent());
    }

    @Test
    @DisplayName("look_up_place: typed lists carry it after rank_spots, the Ready list does not, and the "
            + "two-argument lists are unchanged")
    void lookUpPlaceIsATypedQuestionTool() {
        List<String> typed = AskToolSchemas.tools(true, false, true).stream().map(Tool::name).toList();
        List<String> followUp = AskToolSchemas.tools(true, true, true).stream().map(Tool::name).toList();
        List<String> ready = AskToolSchemas.tools(false, false, false).stream().map(Tool::name).toList();

        assertThat(typed).containsExactly(AskToolSchemas.LIST_WINDOWS, AskToolSchemas.RANK_SPOTS,
                AskToolSchemas.LOOK_UP_PLACE, AskToolSchemas.RANK_NIGHT_SPOTS, AskToolSchemas.GET_HOT_TOPICS,
                AskToolSchemas.GET_COMING_UP, AskToolSchemas.SUBMIT_ANSWER);
        assertThat(followUp).isEqualTo(typed);
        assertThat(ready).containsExactly(AskToolSchemas.LIST_WINDOWS, AskToolSchemas.RANK_SPOTS,
                AskToolSchemas.RANK_NIGHT_SPOTS, AskToolSchemas.GET_HOT_TOPICS, AskToolSchemas.GET_COMING_UP,
                AskToolSchemas.SUBMIT_ANSWER);
        assertThat(AskToolSchemas.tools(false)).extracting(Tool::name).doesNotContain(AskToolSchemas.LOOK_UP_PLACE);
        assertThat(AskToolSchemas.tools(true, true)).extracting(Tool::name)
                .doesNotContain(AskToolSchemas.LOOK_UP_PLACE);
        assertThat(AskToolSchemas.LOOK_UP_PLACE).isEqualTo("look_up_place");
    }

    @Test
    @DisplayName("look_up_place's schema is exactly its argument record, requires only name, and says it never "
            + "makes a pick")
    void lookUpPlaceSchema() {
        Tool lookUp = tool(AskToolSchemas.tools(true, false, true), AskToolSchemas.LOOK_UP_PLACE);

        assertThat(properties(lookUp)).isEqualTo(components(AskTools.LookUpPlaceArgs.class));
        assertThat(lookUp.inputSchema().required().orElseThrow()).containsExactly("name");
        assertThat(property(lookUp, "name").get("type").asText()).isEqualTo("string");
        assertThat(property(lookUp, "windowIds").get("type").asText()).isEqualTo("array");
        assertThat(lookUp.description().orElseThrow()).contains("why it is rated as it is")
                .contains("never makes the place a pick");
    }

    @Test
    @DisplayName("rank_spots' description names Fiery Sky and Golden Hour for a typed conversation; the user-less "
            + "text is the unchanged original")
    void rankSpotsDescriptionNamesTheLightFiguresOnlyWithAUser() {
        String base = "The spots worth going to, best first: only places rated 3 stars or better, never a wood. "
                + "Every pick in your answer must be a locationId and windowId this tool returned.";
        String typedText = base + " Each spot also has its Fiery Sky and Golden Hour (0-100).";

        assertThat(tool(AskToolSchemas.tools(true), AskToolSchemas.RANK_SPOTS).description().orElseThrow())
                .isEqualTo(typedText);
        assertThat(tool(AskToolSchemas.tools(true, false, true), AskToolSchemas.RANK_SPOTS).description()
                .orElseThrow()).isEqualTo(typedText);
        assertThat(tool(AskToolSchemas.tools(false), AskToolSchemas.RANK_SPOTS).description().orElseThrow())
                .isEqualTo(base);
        assertThat(tool(AskToolSchemas.tools(false, false, false), AskToolSchemas.RANK_SPOTS).description()
                .orElseThrow()).isEqualTo(base);
    }

    @Test
    @DisplayName("areaName is on the typed rank_spots; the Ready list never has it, and look_up_place's "
            + "description names the named area")
    void namedAreaIsATypedQuestionArgument() {
        Tool typed = tool(AskToolSchemas.tools(true, false, true), AskToolSchemas.RANK_SPOTS);

        assertThat(properties(typed)).contains("areaName");
        assertThat(property(typed, "areaName").get("type").asText()).isEqualTo("string");
        assertThat(property(typed, "areaName").get("description").asText()).contains("named area");
        assertThat(properties(tool(AskToolSchemas.tools(true, true, true), AskToolSchemas.RANK_SPOTS)))
                .contains("areaName");
        assertThat(properties(tool(AskToolSchemas.tools(false, false, false), AskToolSchemas.RANK_SPOTS)))
                .doesNotContain("areaName");
        assertThat(properties(tool(AskToolSchemas.tools(false), AskToolSchemas.RANK_SPOTS)))
                .doesNotContain("areaName");
        assertThat(AskToolSchemas.tools(false, false, false).toString()).doesNotContain("areaName")
                .doesNotContain("named area");
        assertThat(tool(AskToolSchemas.tools(true, false, true), AskToolSchemas.LOOK_UP_PLACE).description()
                .orElseThrow()).contains("The name may also be a named area, such as a landmark, or another name "
                + "for a place");
    }

    @Test
    @DisplayName("each lookup tool's schema properties are exactly its argument record's fields")
    void lookupSchemasMatchArgumentRecords() {
        List<Tool> tools = AskToolSchemas.tools(true);

        // areaName is the typed conversation's (see namedAreaIsATypedQuestionArgument); Ready's list is as before.
        Set<String> withoutArea = components(AskTools.RankSpotsArgs.class);
        withoutArea.remove("areaName");
        assertThat(properties(tool(tools, AskToolSchemas.RANK_SPOTS))).isEqualTo(withoutArea);
        assertThat(properties(tool(AskToolSchemas.tools(true, false, true), AskToolSchemas.RANK_SPOTS)))
                .isEqualTo(components(AskTools.RankSpotsArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.RANK_NIGHT_SPOTS)))
                .isEqualTo(components(AskTools.RankNightSpotsArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.GET_HOT_TOPICS)))
                .isEqualTo(components(AskTools.HotTopicsArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.GET_COMING_UP)))
                .isEqualTo(components(AskTools.ComingUpArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.LIST_WINDOWS))).isEmpty();
    }

    @Test
    @DisplayName("a user-less conversation is not even told about maxDriveMinutes or nearHome")
    void driveLimitIsAdvertisedOnlyWithAUser() {
        Set<String> withUser = properties(tool(AskToolSchemas.tools(true), AskToolSchemas.RANK_SPOTS));
        Set<String> userLess = properties(tool(AskToolSchemas.tools(false), AskToolSchemas.RANK_SPOTS));

        assertThat(withUser).contains("maxDriveMinutes", "nearHome");
        assertThat(userLess).doesNotContain("maxDriveMinutes", "nearHome")
                .containsExactlyInAnyOrderElementsOf(withUser.stream()
                        .filter(p -> !p.equals("maxDriveMinutes") && !p.equals("nearHome")).toList());
    }

    @Test
    @DisplayName("the night tool advertises maxDriveMinutes and nearHome only with a user, names the two window "
            + "ids a pick must use, and offers the three kinds")
    void nightToolSchema() {
        Tool withUser = tool(AskToolSchemas.tools(true), AskToolSchemas.RANK_NIGHT_SPOTS);
        Tool userLess = tool(AskToolSchemas.tools(false), AskToolSchemas.RANK_NIGHT_SPOTS);

        assertThat(properties(withUser)).contains("maxDriveMinutes", "nearHome");
        assertThat(properties(userLess)).doesNotContain("maxDriveMinutes", "nearHome").contains("night", "kind");
        assertThat(withUser.description().orElseThrow()).contains("astroWindowId").contains("auroraWindowId")
                .contains("clear skies tonight");
        assertThat(property(withUser, "kind").get("enum")).hasSize(3);
    }

    @Test
    @DisplayName("nearHome is a boolean whose description says the straight-line saved radius and to quote it as "
            + "'within N miles of home'; maxDriveMinutes no longer carries the 60-minute default")
    void nearHomeIsDescribedAsTheSavedRadius() {
        Tool rank = tool(AskToolSchemas.tools(true), AskToolSchemas.RANK_SPOTS);

        assertThat(property(rank, "nearHome").get("type").asText()).isEqualTo("boolean");
        assertThat(property(rank, "nearHome").get("description").asText())
                .contains("straight-line miles").contains("within N miles of home");
        assertThat(property(rank, "maxDriveMinutes").get("description").asText()).doesNotContain("60");
    }

    @Test
    @DisplayName("a follow-up's submit_answer allows four picks for a ranking; a fresh question's stays at three, "
            + "and nothing else differs")
    void followUpAllowsAFourthPick() {
        Tool fresh = tool(AskToolSchemas.tools(true, false), AskToolSchemas.SUBMIT_ANSWER);
        Tool followUp = tool(AskToolSchemas.tools(true, true), AskToolSchemas.SUBMIT_ANSWER);

        assertThat(property(fresh, "picks").get("maxItems").intValue()).isEqualTo(AskAnswerValidator.MAX_PICKS);
        assertThat(property(followUp, "picks").get("maxItems").intValue())
                .isEqualTo(AskAnswerValidator.MAX_RANKED_PICKS);
        assertThat(AskToolSchemas.tools(true)).hasToString(AskToolSchemas.tools(true, false).toString());
        assertThat(properties(followUp)).isEqualTo(properties(fresh));
    }

    @Test
    @DisplayName("submit_answer matches the validator's raw answer, has no 'try' field, and requires "
            + "only answerable and summary")
    void submitAnswerMatchesTheContract() {
        Tool submit = tool(AskToolSchemas.tools(true), AskToolSchemas.SUBMIT_ANSWER);

        assertThat(properties(submit)).isEqualTo(components(AskAnswerValidator.Raw.class))
                .doesNotContain("try", "safetyNote");
        assertThat(submit.inputSchema().required().orElseThrow())
                .containsExactlyInAnyOrder("answerable", "summary");
        assertThat(new TreeSet<>(iterate(property(submit, "picks").get("items").get("properties"))))
                .isEqualTo(components(AskAnswerValidator.RawPick.class));
        assertThat(new TreeSet<>(iterate(property(submit, "events").get("items").get("properties"))))
                .isEqualTo(components(AskAnswerValidator.RawEvent.class));
        assertThat(property(submit, "picks").get("maxItems").intValue()).isEqualTo(AskAnswerValidator.MAX_PICKS);
    }

    @Test
    @DisplayName("the limits the tools clamp to are the ones the schemas advertise")
    void advertisedLimitsMatchTheTools() {
        List<Tool> tools = AskToolSchemas.tools(true);

        assertThat(property(tool(tools, AskToolSchemas.RANK_SPOTS), "limit").get("maximum").intValue())
                .isEqualTo(AskTools.MAX_SPOTS);
        assertThat(property(tool(tools, AskToolSchemas.GET_HOT_TOPICS), "limit").get("maximum").intValue())
                .isEqualTo(AskTools.MAX_EVENTS);
        assertThat(property(tool(tools, AskToolSchemas.GET_COMING_UP), "days").get("maximum").intValue())
                .isEqualTo(AskSnapshot.MAX_COMING_UP_DAYS);
    }

    @Test
    @DisplayName("the schemas are byte-stable between builds (insertion-ordered)")
    void schemasAreStable() {
        assertThat(AskToolSchemas.tools(true).toString()).isEqualTo(AskToolSchemas.tools(true).toString());
    }

    @Test
    @DisplayName("golden: the schemas are byte-for-byte the pinned text (the map helper's refactor, then the "
            + "events tools' complementary descriptions), with and without a drive limit")
    void schemasAreUnchangedFromTheGolden() throws Exception {
        assertThat(AskToolSchemas.tools(true).toString()).isEqualTo(golden("tool-schemas-with-user.txt"));
        assertThat(AskToolSchemas.tools(false).toString()).isEqualTo(golden("tool-schemas-user-less.txt"));
        assertThat(AskToolSchemas.tools(true, true).toString()).isEqualTo(golden("tool-schemas-with-user-thread.txt"));
    }

    private static String golden(String name) throws Exception {
        try (var in = AskToolSchemasTest.class.getResourceAsStream("/ask/" + name)) {
            return new String(java.util.Objects.requireNonNull(in, name).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the map helper keeps insertion order and takes an empty list")
    void mapHelperKeepsOrder() {
        assertThat(AskToolSchemas.m()).isEmpty();
        assertThat(AskToolSchemas.m("z", 1, "a", 2, "m", null).keySet()).containsExactly("z", "a", "m");
        assertThat(AskToolSchemas.m("z", 1, "a", 2, "m", null)).containsEntry("a", 2).containsEntry("m", null);
    }

    @Test
    @DisplayName("an odd-length argument list is refused up front, whatever its length")
    void mapHelperRefusesOddLengths() {
        for (Object[] odd : new Object[][] {{"k"}, {"k", 1, "j"}, {"a", 1, "b", 2, "c"}}) {
            assertThatThrownBy(() -> AskToolSchemas.m(odd)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("key/value pairs").hasMessageContaining(String.valueOf(odd.length));
        }
    }

    @Test
    @DisplayName("a key that is not a String is an IllegalArgumentException naming its place, never a "
            + "ClassCastException; the null key too")
    void mapHelperRefusesNonStringKeys() {
        assertThatThrownBy(() -> AskToolSchemas.m(5, "v"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("argument 0")
                .hasMessageContaining("java.lang.Integer");
        assertThatThrownBy(() -> AskToolSchemas.m("ok", 1, null, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("argument 2")
                .hasMessageContaining("null");
    }

    private static List<String> iterate(JsonNode object) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("the two events tools describe each other: the live 5-day topics and the almanac's "
            + "longer range, each telling the model to call the other for any events question")
    void eventsToolsAreSelfDescribingAndComplementary() {
        List<Tool> tools = AskToolSchemas.tools(true);

        String hot = tool(tools, AskToolSchemas.GET_HOT_TOPICS).description().orElseThrow();
        String coming = tool(tools, AskToolSchemas.GET_COMING_UP).description().orElseThrow();

        assertThat(hot).contains("live forecast").contains("next few days")
                .contains(AskToolSchemas.GET_COMING_UP);
        assertThat(coming).contains("almanac").contains("live forecast").contains("meteor showers")
                .contains(AskToolSchemas.GET_HOT_TOPICS);
    }

    @Test
    @DisplayName("list_windows names the spread it returns, and submit_answer asks for up to three picks, "
            + "fewer being fine (never 'two or three')")
    void listWindowsDescribesTheSpreadAndPicksAreUpToThree() {
        for (boolean thread : new boolean[] {false, true}) {
            List<Tool> tools = AskToolSchemas.tools(true, thread);
            String list = tool(tools, AskToolSchemas.LIST_WINDOWS).description().orElseThrow();
            String picks = property(tool(tools, AskToolSchemas.SUBMIT_ANSWER), "picks").get("description")
                    .asText();

            assertThat(list).contains("spread of its ratings").contains("the count at each star")
                    .contains("how many are game on").contains("regions holding a game-on place")
                    .contains("stood down or not scored").contains("BEST BET / ALSO GOOD");
            assertThat(picks).startsWith("Up to three different spots").contains("fewer is fine")
                    .doesNotContain("Two or three");
        }
    }
}
