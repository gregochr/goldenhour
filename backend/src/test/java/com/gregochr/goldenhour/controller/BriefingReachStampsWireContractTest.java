package com.gregochr.goldenhour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the served {@code /api/briefing} body is unchanged by the reach-scoped stamps (reach-scoped-card-plan R2
 * step 1) and carries the one new field, {@code briefingVersion}.
 *
 * <p>Runs against the real Spring MVC converter chain, whose mapper is the auto-configured Jackson 3
 * ({@code tools.jackson}) one, because {@code @JsonIgnore} is only proved to take effect on the mapper the wire
 * uses; the Jackson 2 mapper that writes {@code daily_briefing_cache} is checked beside it, since a stamp must not
 * be persisted either.
 */
class BriefingReachStampsWireContractTest extends AbstractControllerTest {

    private static final BriefingSlot.WeatherConditions WEATHER = new BriefingSlot.WeatherConditions(
            10, BigDecimal.ZERO, 20000, 60, 12.0, 10.0, 1, BigDecimal.ONE, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper cacheMapper;

    private static BriefingSlot slot(boolean forced, boolean triaged) {
        return new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 1, 6, 30), Verdict.GO, WEATHER,
                BriefingSlot.TideInfo.NONE, List.of())
                .withClaudeScores(4, 4, 70, 60, "why", "Fiery")
                .withReachStamps(forced, triaged);
    }

    private static DailyBriefingResponse briefing(BriefingSlot slot, String version) {
        BriefingRegion region = new BriefingRegion("Northumberland", Verdict.GO, "s", List.of(), List.of(slot),
                12.0, 10.0, 1.0, 1, null, null, DisplayVerdict.WORTH_IT, 1);
        BriefingDay day = new BriefingDay(LocalDate.of(2026, 10, 1), List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of())));
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "h", List.of(day), List.of(), null,
                null, false, "Opus", List.of(), List.of()).withBriefingVersion(version);
    }

    private String served(BriefingSlot slot) throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing(slot, "v-1"));
        return mockMvc.perform(get("/api/briefing")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("the served body is byte-identical whether a slot is stamped forced/triaged or not, and names neither")
    void stampsAreNotOnTheWire() throws Exception {
        String stamped = served(slot(true, true));
        String plain = served(slot(false, false));

        assertThat(stamped).isEqualTo(plain);
        assertThat(stamped).doesNotContain("\"forced\"").doesNotContain("\"triaged\"");
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("the served body carries briefingVersion, and omits it when there is none")
    void briefingVersionIsServed() throws Exception {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing(slot(false, false), "v-1"));
        mockMvc.perform(get("/api/briefing")).andExpect(jsonPath("$.briefingVersion").value("v-1"));

        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing(slot(false, false), null));
        mockMvc.perform(get("/api/briefing")).andExpect(jsonPath("$.briefingVersion").doesNotExist());
    }

    @Test
    @DisplayName("the Jackson 2 cache mapper does not persist the stamps either, and reads a slot back unstamped")
    void stampsAreNotPersisted() throws Exception {
        String json = cacheMapper.writeValueAsString(slot(true, true));

        assertThat(json).doesNotContain("forced").doesNotContain("triaged");
        assertThat(json).isEqualTo(cacheMapper.writeValueAsString(slot(false, false)));

        BriefingSlot back = cacheMapper.readValue(
                json.replaceFirst("\\{", "{\"forced\":true,\"triaged\":true,"), BriefingSlot.class);
        assertThat(back.forced()).isFalse();
        assertThat(back.triaged()).isFalse();
        assertThat(back.claudeRating()).isEqualTo(4);
        assertThat(back.locationId()).isEqualTo(7L);
    }
}
