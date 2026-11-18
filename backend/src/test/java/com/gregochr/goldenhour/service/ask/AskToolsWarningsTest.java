package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskTools.ListWindowsResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.served;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.servedStale;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.twoRegions;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.warning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What {@link AskTools} shows the model about Met Office warnings (weather-warnings-plan.md section 14, W6): the
 * facts ride the first result of a typed conversation only, never a Ready one, never while the feature is off, and
 * nothing changes byte for byte when there is nothing to show.
 */
class AskToolsWarningsTest {

    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final Map<Long, AskLocationNames.Place> PLACES = Map.of(
            1L, new AskLocationNames.Place("Whitby", 54.48, -0.61),
            2L, new AskLocationNames.Place("Staithes", 54.56, -0.79),
            3L, new AskLocationNames.Place("Hawes", 54.30, -2.20));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools typed(AskSnapshot snapshot, AskScope scope) {
        return new AskTools(snapshot, PRO, scope, driveTimes, mapper, () -> PLACES);
    }

    private JsonNode json(AskToolResult<?> result) throws JsonProcessingException {
        assertThat(result.error()).as(result.content()).isFalse();
        return mapper.readTree(result.content());
    }

    @Test
    @DisplayName("with the feature off, or on with nothing in force, the result is byte for byte the payload's own")
    void nothingToShow_isByteIdentical() throws Exception {
        AskSnapshot base = twoRegions();
        AskToolResult<ListWindowsResult> plain = typed(base, AskScope.ALL).listWindows();
        AskToolResult<ListWindowsResult> none = typed(base.withWarnings(AskSnapshot.Warnings.NONE), AskScope.ALL)
                .listWindows();
        AskToolResult<ListWindowsResult> empty = typed(base.withWarnings(served()), AskScope.ALL).listWindows();
        AskToolResult<ListWindowsResult> staleEmpty = typed(base.withWarnings(servedStale()), AskScope.ALL)
                .listWindows();

        assertThat(plain.content()).isEqualTo(mapper.writeValueAsString(plain.payload()))
                .doesNotContain("warnings");
        assertThat(none.content()).isEqualTo(plain.content());
        assertThat(empty.content()).isEqualTo(plain.content());
        assertThat(staleEmpty.content()).isEqualTo(plain.content());
    }

    @Test
    @DisplayName("a user-less (Ready) conversation never sees a warning, in force or not, and stays cacheable")
    void readyConversation_neverSeesWarnings() {
        AskSnapshot snapshot = twoRegions().withWarnings(served(warning("W1", "AMBER", List.of("SNOW"), 1L)));
        AskTools ready = new AskTools(snapshot, AskUserContext.userLess(), AskScope.ALL, driveTimes, mapper,
                () -> PLACES);

        AskToolResult<ListWindowsResult> result = ready.listWindows();

        assertThat(result.content()).doesNotContain("warnings").doesNotContain("Amber");
        assertThat(ready.evidence().warningIds()).isEmpty();
        assertThat(ready.uncacheable()).isFalse();
        assertThat(ready.hasWarningFacts()).isFalse();
    }

    @Test
    @DisplayName("a typed conversation's first result starts with the Met Office's own facts, and the payload's "
            + "own fields follow unchanged")
    void typedConversation_firstResultCarriesTheFacts() throws Exception {
        AskSnapshot base = twoRegions();
        AskSnapshot snapshot = base.withWarnings(served(warning("W1", "AMBER", List.of("SNOW", "WIND"), 1L, 2L)));
        AskTools tools = typed(snapshot, AskScope.ALL);

        AskToolResult<ListWindowsResult> result = tools.listWindows();

        JsonNode root = json(result);
        assertThat(root.fieldNames().next()).isEqualTo("warnings");
        JsonNode block = root.get("warnings");
        assertThat(block.get("source").asText()).isEqualTo("Met Office Weather Warnings");
        assertThat(block.get("stale").asBoolean()).isFalse();
        assertThat(block.has("note")).isFalse();
        assertThat(block.has("moreWarnings")).isFalse();
        JsonNode item = block.get("inForce").get(0);
        assertThat(item.get("id").asText()).isEqualTo("W1");
        assertThat(item.get("phrase").asText()).isEqualTo("Amber warning of snow and wind");
        assertThat(item.has("updated")).isFalse();
        assertThat(item.get("validFrom").asText()).isEqualTo("Mon 5 Oct 07:00");
        assertThat(item.get("validTo").asText()).isEqualTo("Tue 6 Oct 19:00");
        assertThat(item.get("headline").asText()).isEqualTo("Snow, heavy at times");
        assertThat(item.get("whatToExpect")).hasSize(2);
        assertThat(item.get("places")).extracting(JsonNode::asText).containsExactly("Staithes", "Whitby");
        assertThat(item.has("placeCount")).isFalse();
        // The rest of the result is the payload exactly as it was.
        ((ObjectNode) root).remove("warnings");
        assertThat(mapper.writeValueAsString(root)).isEqualTo(mapper.writeValueAsString(result.payload()));
        assertThat(tools.evidence().warningIds()).containsExactly("W1");
        assertThat(tools.uncacheable()).isTrue();
    }

    @Test
    @DisplayName("the facts ride the first result whichever tool it is, once; a later result has none")
    void factsRideTheFirstResultOnly() throws Exception {
        AskSnapshot snapshot = twoRegions().withWarnings(served(warning("W1", "RED", List.of("SNOW"), 1L)));
        AskTools tools = typed(snapshot, AskScope.ALL);

        JsonNode first = json(tools.rankSpots(new RankSpotsArgs(null, null, null, null, null, null)));
        JsonNode second = json(tools.listWindows());

        assertThat(first.has("warnings")).isTrue();
        assertThat(first.get("warnings").get("inForce").get(0).get("phrase").asText())
                .isEqualTo("Red warning of snow");
        assertThat(second.has("warnings")).isFalse();
    }

    @Test
    @DisplayName("an error result delivers nothing: the facts wait for the first result that succeeds")
    void anErrorResult_doesNotUseUpTheFacts() throws Exception {
        AskSnapshot snapshot = twoRegions().withWarnings(served(warning("W1", "AMBER", List.of("SNOW"), 1L)));
        AskTools tools = typed(snapshot, AskScope.ALL);

        AskToolResult<?> bad = tools.rankSpots(new RankSpotsArgs(List.of("no-such-window"), null, null, null, null,
                null));
        assertThat(bad.error()).isTrue();
        assertThat(tools.evidence().warningIds()).isEmpty();

        assertThat(json(tools.listWindows()).has("warnings")).isTrue();
        assertThat(tools.evidence().warningIds()).containsExactly("W1");
    }

    @Test
    @DisplayName("only a warning covering a place in the question's regions is shown")
    void scopeFiltersTheWarnings() throws Exception {
        AskSnapshot snapshot = twoRegions().withWarnings(served(
                warning("W-dales", "AMBER", List.of("SNOW"), 3L), warning("W-coast", "YELLOW", List.of("WIND"), 1L)));

        JsonNode coast = json(typed(snapshot, TestScopes.of("Coast")).listWindows());
        JsonNode dales = json(typed(snapshot, TestScopes.of("Dales")).listWindows());
        JsonNode everywhere = json(typed(snapshot, AskScope.ALL).listWindows());

        assertThat(coast.get("warnings").get("inForce")).extracting(n -> n.get("id").asText())
                .containsExactly("W-coast");
        assertThat(dales.get("warnings").get("inForce")).extracting(n -> n.get("id").asText())
                .containsExactly("W-dales");
        assertThat(everywhere.get("warnings").get("inForce")).extracting(n -> n.get("id").asText())
                .containsExactly("W-dales", "W-coast");
    }

    @Test
    @DisplayName("a warning over no place in the question's regions shows nothing, and the result is unchanged")
    void aWarningElsewhere_showsNothing() throws Exception {
        AskSnapshot base = twoRegions();
        AskSnapshot snapshot = base.withWarnings(served(warning("W-dales", "AMBER", List.of("SNOW"), 3L)));
        AskTools tools = typed(snapshot, TestScopes.of("Coast"));

        AskToolResult<ListWindowsResult> result = tools.listWindows();

        assertThat(result.content()).isEqualTo(mapper.writeValueAsString(result.payload()));
        assertThat(tools.evidence().warningIds()).isEmpty();
        assertThat(tools.uncacheable()).isFalse();
        assertThat(tools.hasWarningFacts()).isFalse();
        assertThat(typed(snapshot, TestScopes.of("Dales")).hasWarningFacts()).isTrue();
    }

    @Test
    @DisplayName("at most three warnings are listed and the rest are counted, so the prompt cannot grow without "
            + "bound")
    void manyWarnings_areCappedAndCounted() throws Exception {
        List<AskSnapshot.Warning> five = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            five.add(warning("W" + i, "YELLOW", List.of("RAIN"), 1L));
        }
        AskSnapshot snapshot = twoRegions().withWarnings(served(five.toArray(AskSnapshot.Warning[]::new)));
        AskTools tools = typed(snapshot, AskScope.ALL);

        JsonNode block = json(tools.listWindows()).get("warnings");

        assertThat(block.get("inForce")).hasSize(3);
        assertThat(block.get("moreWarnings").asInt()).isEqualTo(2);
        assertThat(tools.evidence().warningIds()).containsExactly("W1", "W2", "W3");
    }

    @Test
    @DisplayName("a stale feed says so in a fixed instruction, so the answer can never read as complete")
    void staleFeed_carriesTheInstruction() throws Exception {
        AskSnapshot snapshot = twoRegions().withWarnings(
                servedStale(warning("W1", "AMBER", List.of("SNOW"), 1L)));

        JsonNode block = json(typed(snapshot, AskScope.ALL).listWindows()).get("warnings");

        assertThat(block.get("stale").asBoolean()).isTrue();
        assertThat(block.get("note").asText()).isEqualTo(AskTools.WARNING_STALE_NOTE);
    }

    @Test
    @DisplayName("long text is cut, only two expectation lines and five places ride along, an update is flagged, "
            + "and times are UK clock times in winter too")
    void textIsBounded() throws Exception {
        String longLine = "x".repeat(400);
        AskSnapshot.Warning wide = new AskSnapshot.Warning("W1", "3.0", "AMBER", List.of("SNOW"),
                Instant.parse("2026-11-19T06:00:00Z"), null, longLine,
                List.of("one", " ", "two", "three"), true, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        Map<Long, AskLocationNames.Place> many = new HashMap<>();
        for (long id = 1; id <= 7; id++) {
            many.put(id, new AskLocationNames.Place("Place " + id, 54.0, -1.0));
        }
        AskSnapshot snapshot = twoRegions().withWarnings(served(wide));
        AskTools tools = new AskTools(snapshot, PRO, AskScope.ALL, driveTimes, mapper, () -> many);

        JsonNode item = json(tools.listWindows()).get("warnings").get("inForce").get(0);

        assertThat(item.get("headline").asText()).hasSize(AskTools.MAX_WARNING_TEXT).endsWith("…");
        assertThat(item.get("whatToExpect")).extracting(JsonNode::asText).containsExactly("one", "two");
        assertThat(item.get("places")).hasSize(5);
        assertThat(item.get("placeCount").asInt()).isEqualTo(7);
        assertThat(item.get("updated").asBoolean()).isTrue();
        assertThat(item.get("validFrom").asText()).isEqualTo("Thu 19 Nov 06:00");
        assertThat(item.has("validTo")).isFalse();
    }

    @Test
    @DisplayName("a warning with no headline and no expectation lines carries neither, never an empty field")
    void sparseWarning_omitsEmptyFields() throws Exception {
        AskSnapshot.Warning sparse = new AskSnapshot.Warning("W1", "1.0", "YELLOW", List.of(), null, null, null,
                List.of(), false, Set.of(1L));
        AskSnapshot snapshot = twoRegions().withWarnings(served(sparse));

        JsonNode item = json(typed(snapshot, AskScope.ALL).listWindows()).get("warnings").get("inForce").get(0);

        assertThat(item.get("phrase").asText()).isEqualTo("Yellow warning");
        assertThat(item.has("headline")).isFalse();
        assertThat(item.has("whatToExpect")).isFalse();
        assertThat(item.has("validFrom")).isFalse();
    }

    @Test
    @DisplayName("a result that cannot be rewritten is still sent, without the facts, and nothing is recorded as "
            + "shown")
    void unreadableResult_isSentWithoutTheFacts() {
        ObjectMapper failing = new ObjectMapper() {
            @Override
            public JsonNode readTree(String content) throws JsonProcessingException {
                throw new JsonProcessingException("unreadable") { };
            }
        };
        AskSnapshot snapshot = twoRegions().withWarnings(served(warning("W1", "AMBER", List.of("SNOW"), 1L)));
        AskTools tools = new AskTools(snapshot, PRO, AskScope.ALL, driveTimes, failing, () -> PLACES);

        AskToolResult<ListWindowsResult> result = tools.listWindows();

        assertThat(result.error()).isFalse();
        assertThat(result.content()).doesNotContain("warnings");
        assertThat(tools.evidence().warningIds()).isEmpty();
        assertThat(tools.uncacheable()).isFalse();
    }
}
