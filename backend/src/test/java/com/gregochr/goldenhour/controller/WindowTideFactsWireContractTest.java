package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the wire shape of {@code window.tideFacts} on the real HTTP pipeline (Jackson 3), the seam
 * the hand-built Jackson 2 mappers in the model tests cannot prove. Modelled on
 * {@code JsonDateFormatContractTest#getBriefing_pinsEclipseSightDateFormat}.
 */
class WindowTideFactsWireContractTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static BriefingWindow window(List<LocationTideFact> facts) {
        return new BriefingWindow(null, DisplayVerdict.AWAITING, null, null, null, List.of(), null,
                null, facts);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing serves window.tideFacts with its field names, omits null "
            + "optional fields, and omits the key on a window with none")
    void getBriefing_pinsTideFactsWireShape() throws Exception {
        LocationTideFact full = new LocationTideFact(7L, "Bamburgh", "HIGH", true, 0.61, true,
                "HW 06:12 · 25m after sunrise", 0.83, "RISING", "4.9 m", "wants higher",
                "Tide suits this spot");
        LocationTideFact sparse = new LocationTideFact(null, "Whitby", "LOW", false, null, null,
                null, null, null, null, null, null);
        BriefingEventSummary withFacts = new BriefingEventSummary(TargetType.SUNRISE, List.of(),
                List.of(), null, window(List.of(full, sparse)));
        BriefingEventSummary without = new BriefingEventSummary(TargetType.SUNSET, List.of(),
                List.of(), null, window(null));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(withFacts, without))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);

        String facts = "$.days[0].eventSummaries[0].window.tideFacts";
        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(facts + ".length()").value(2))
                .andExpect(jsonPath(facts + "[0].locationId").value(7))
                .andExpect(jsonPath(facts + "[0].locationName").value("Bamburgh"))
                .andExpect(jsonPath(facts + "[0].tideState").value("HIGH"))
                .andExpect(jsonPath(facts + "[0].tideAligned").value(true))
                .andExpect(jsonPath(facts + "[0].tideAlignmentQuality").value(0.61))
                .andExpect(jsonPath(facts + "[0].tideOnTheLight").value(true))
                .andExpect(jsonPath(facts + "[0].nearestSolarOffsetPhrase")
                        .value("HW 06:12 · 25m after sunrise"))
                .andExpect(jsonPath(facts + "[0].tideLevel").value(0.83))
                .andExpect(jsonPath(facts + "[0].tideDirection").value("RISING"))
                .andExpect(jsonPath(facts + "[0].tideHeight").value("4.9 m"))
                .andExpect(jsonPath(facts + "[0].tideShortfall").value("wants higher"))
                .andExpect(jsonPath(facts + "[0].tideFitPhrase").value("Tide suits this spot"))
                .andExpect(jsonPath(facts + "[1].locationName").value("Whitby"))
                .andExpect(jsonPath(facts + "[1].tideAligned").value(false))
                .andExpect(jsonPath(facts + "[1].locationId").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideAlignmentQuality").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideOnTheLight").doesNotExist())
                .andExpect(jsonPath(facts + "[1].nearestSolarOffsetPhrase").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideLevel").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideDirection").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideHeight").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideShortfall").doesNotExist())
                .andExpect(jsonPath(facts + "[1].tideFitPhrase").doesNotExist())
                .andExpect(jsonPath("$.days[0].eventSummaries[1].window").exists())
                .andExpect(jsonPath("$.days[0].eventSummaries[1].window.tideFacts")
                        .doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("P5: a served slot (tide null) serialises none of the unwrapped tide keys, "
            + "while window.tideFacts is present")
    void getBriefing_strippedSlotCarriesNoTideKeys() throws Exception {
        LocationTideFact fact = new LocationTideFact(7L, "Bamburgh", "HIGH", true, 0.61, true,
                "HW 06:12 · 25m after sunrise", 0.83, "RISING", "4.9 m", "wants higher",
                "Tide suits this spot");
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 11, 6, 0),
                Verdict.GO, null, null, List.of(), null, null, null, null, null, null,
                DisplayVerdict.AWAITING, null, false, null);
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(slot), null, null, null, null, null, null, DisplayVerdict.AWAITING, 0,
                null, false, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(slot), null, window(List.of(fact)));
        DailyBriefingResponse briefing = new DailyBriefingResponse(
                LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);

        String es = "$.days[0].eventSummaries[0]";
        ResultActions result = mockMvc.perform(get("/api/briefing")).andExpect(status().isOk());
        result.andExpect(jsonPath(es + ".window.tideFacts.length()").value(1))
                .andExpect(jsonPath(es + ".regions[0].slots[0].locationName").value("Bamburgh"))
                .andExpect(jsonPath(es + ".unregioned[0].locationName").value("Bamburgh"));
        for (String slotPath : List.of(es + ".regions[0].slots[0]", es + ".unregioned[0]")) {
            for (String key : List.of("tideState", "tideAligned", "tideOnTheLight", "tideLevel",
                    "tideFitPhrase", "tideDirection", "tideHeight", "tideShortfall",
                    "tideAlignmentQuality", "heightAboveP95", "heightAboveSpringThreshold",
                    "lunarTideType", "lunarPhase", "moonAtPerigee", "nearestHighTideTime",
                    "nearestHighTideHeight", "nearestSolarOffsetMinutes",
                    "nearestSolarOffsetPhrase", "nearestExtremeKind")) {
                result.andExpect(jsonPath(slotPath + "." + key).doesNotExist());
            }
        }
    }
}
