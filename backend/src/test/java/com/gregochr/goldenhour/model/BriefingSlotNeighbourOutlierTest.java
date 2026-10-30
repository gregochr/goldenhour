package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code neighbourOutlier} component of {@link BriefingSlot}: which constructors and withers carry
 * it, and how it serialises on the Jackson 2 graph that persists the briefing and the Jackson 3 graph
 * that serves it. {@link RecordWitherPreservationTest} covers every wither generically; this class
 * states the cases by name.
 */
class BriefingSlotNeighbourOutlierTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 18, 6, 0);

    private final ObjectMapper jackson2 = new ObjectMapper().registerModule(new JavaTimeModule());
    private final JsonMapper jackson3 = JsonMapper.builder().build();

    private static BriefingSlot rated() {
        return new BriefingSlot(7L, "Caw Gap", TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of(), 4, 4, 60, 55, "summary", DisplayVerdict.WORTH_IT, null, false);
    }

    @Test
    @DisplayName("every constructor that predates the component leaves it null")
    void legacyConstructorsDefaultToNull() {
        assertThat(rated().neighbourOutlier()).isNull();
        assertThat(new BriefingSlot(7L, "A", TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                List.of()).neighbourOutlier()).isNull();
        assertThat(new BriefingSlot("A", TIME, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
        List.of()).neighbourOutlier()).isNull();
        BriefingSlot seventeen = new BriefingSlot(7L, "A", TIME, Verdict.GO, null,
                BriefingSlot.TideInfo.NONE, List.of(), 4, 4, 60, 55, "s", DisplayVerdict.WORTH_IT,
                null, false, null);
        assertThat(seventeen.neighbourOutlier()).isNull();
    }

    @Test
    @DisplayName("canopySlot, both overloads, carries no mark")
    void canopySlotCarriesNoMark() {
        assertThat(BriefingSlot.canopySlot(7L, "Wood", TIME, Verdict.GO, null, List.of())
                .neighbourOutlier()).isNull();
        assertThat(BriefingSlot.canopySlot("Wood", TIME, Verdict.GO, null, List.of())
                .neighbourOutlier()).isNull();
    }

    @Test
    @DisplayName("withNeighbourOutlier stores the value verbatim, FALSE included, and changes nothing else")
    void withNeighbourOutlierStoresVerbatim() {
        BriefingSlot flagged = rated().withNeighbourOutlier(Boolean.TRUE);

        assertThat(flagged.neighbourOutlier()).isTrue();
        assertThat(flagged.withNeighbourOutlier(Boolean.FALSE).neighbourOutlier()).isFalse();
        assertThat(flagged.withNeighbourOutlier(null).neighbourOutlier()).isNull();
        assertThat(flagged.withNeighbourOutlier(null)).isEqualTo(rated());
    }

    @Test
    @DisplayName("withTide, withEclipse and every withClaudeScores overload preserve the mark")
    void otherWithersPreserveTheMark() {
        BriefingSlot flagged = rated().withNeighbourOutlier(Boolean.TRUE);

        assertThat(flagged.withTide(null).neighbourOutlier()).isTrue();
        assertThat(flagged.withEclipse(null).neighbourOutlier()).isTrue();
        assertThat(flagged.withClaudeScores(3, 50, 40, "s").neighbourOutlier()).isTrue();
        assertThat(flagged.withClaudeScores(3, 50, 40, "s", "h").neighbourOutlier()).isTrue();
        assertThat(flagged.withClaudeScores(3, 2, 50, 40, "s", "h").neighbourOutlier()).isTrue();
    }

    @Test
    @DisplayName("served JSON (Jackson 3) carries neighbourOutlier only when it is true")
    void servedJsonCarriesTheKeyOnlyWhenTrue() throws Exception {
        assertThat(jackson3.writeValueAsString(rated())).doesNotContain("neighbourOutlier");
        assertThat(jackson3.writeValueAsString(rated().withNeighbourOutlier(Boolean.TRUE)))
                .contains("\"neighbourOutlier\":true");
    }

    @Test
    @DisplayName("persisted JSON (Jackson 2) has no key for an unflagged slot and reads a flagged one back")
    void persistedJsonHasNoKeyUnlessSet() throws Exception {
        String unflagged = jackson2.writeValueAsString(rated());
        assertThat(unflagged).doesNotContain("neighbourOutlier");
        assertThat(jackson2.readValue(unflagged, BriefingSlot.class).neighbourOutlier()).isNull();

        BriefingSlot flagged = rated().withNeighbourOutlier(Boolean.TRUE);
        String json = jackson2.writeValueAsString(flagged);
        assertThat(json).contains("\"neighbourOutlier\":true");
        assertThat(jackson2.readValue(json, BriefingSlot.class).neighbourOutlier()).isTrue();
    }
}
