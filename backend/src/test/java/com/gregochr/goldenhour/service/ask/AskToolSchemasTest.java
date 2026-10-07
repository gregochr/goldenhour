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
    @DisplayName("five tools, submit_answer last, named by the constants the engine dispatches on")
    void toolNames() {
        List<Tool> tools = AskToolSchemas.tools(true);

        assertThat(tools).extracting(Tool::name).containsExactly(AskToolSchemas.LIST_WINDOWS,
                AskToolSchemas.RANK_SPOTS, AskToolSchemas.GET_HOT_TOPICS, AskToolSchemas.GET_COMING_UP,
                AskToolSchemas.SUBMIT_ANSWER);
        assertThat(tools).allSatisfy(t -> assertThat(t.description()).isPresent());
    }

    @Test
    @DisplayName("each lookup tool's schema properties are exactly its argument record's fields")
    void lookupSchemasMatchArgumentRecords() {
        List<Tool> tools = AskToolSchemas.tools(true);

        assertThat(properties(tool(tools, AskToolSchemas.RANK_SPOTS)))
                .isEqualTo(components(AskTools.RankSpotsArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.GET_HOT_TOPICS)))
                .isEqualTo(components(AskTools.HotTopicsArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.GET_COMING_UP)))
                .isEqualTo(components(AskTools.ComingUpArgs.class));
        assertThat(properties(tool(tools, AskToolSchemas.LIST_WINDOWS))).isEmpty();
    }

    @Test
    @DisplayName("a user-less conversation is not even told about maxDriveMinutes")
    void driveLimitIsAdvertisedOnlyWithAUser() {
        Set<String> withUser = properties(tool(AskToolSchemas.tools(true), AskToolSchemas.RANK_SPOTS));
        Set<String> userLess = properties(tool(AskToolSchemas.tools(false), AskToolSchemas.RANK_SPOTS));

        assertThat(withUser).contains("maxDriveMinutes");
        assertThat(userLess).doesNotContain("maxDriveMinutes")
                .containsExactlyInAnyOrderElementsOf(
                        withUser.stream().filter(p -> !p.equals("maxDriveMinutes")).toList());
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
                .isEqualTo(AskTools.MAX_COMING_UP_DAYS);
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
}
