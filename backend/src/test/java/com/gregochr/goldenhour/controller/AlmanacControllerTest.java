package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpDayMark;
import com.gregochr.goldenhour.model.comingup.ComingUpDayRange;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.comingup.ComingUpRoleShaper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AlmanacController} — payload shape and, more importantly, who can
 * reach it.
 */
class AlmanacControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final LocalDate BUILT_FOR = LocalDate.of(2026, 8, 16);

    private static ComingUpEntry springRun() {
        AlmanacEvent event = new AlmanacEvent(
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 18),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run",
                "The moon's alignment pulls the tide further out and further in than usual.",
                Map.of("range", "4.6 m"), List.of("Northumberland"));
        return ComingUpEntry.from(event, LocalDate.of(2026, 5, 19));
    }

    private static ComingUpEntry datesOnly() {
        AlmanacEvent event = new AlmanacEvent(
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run", "detail",
                Map.of(), List.of());
        return ComingUpEntry.from(event, LocalDate.of(2026, 8, 3));
    }

    private static ComingUpResponse response(ComingUpEntry... entries) {
        return new ComingUpResponse(BUILT_FOR, null, null, List.of(entries));
    }

    @Test
    @DisplayName("GET /api/almanac returns the wrapped feed with spans and derived facts")
    @WithMockUser(roles = {"PRO_USER"})
    void returnsTheFeed() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(response(springRun()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtFor").value("2026-08-16"))
                .andExpect(jsonPath("$.conditions").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].startDate").value("2026-08-16"))
                .andExpect(jsonPath("$.entries[0].endDate").value("2026-08-18"))
                .andExpect(jsonPath("$.entries[0].kind").value("ALMANAC"))
                .andExpect(jsonPath("$.entries[0].type").value("spring-tide"))
                .andExpect(jsonPath("$.entries[0].title").value("Spring tide run"))
                .andExpect(jsonPath("$.entries[0].meta.range").value("4.6 m"))
                .andExpect(jsonPath("$.entries[0].regions[0]").value("Northumberland"))
                .andExpect(jsonPath("$.entries[0].enteredWindow").value("2026-05-19"));
    }

    @Test
    @DisplayName("a degraded entry omits meta and regions entirely rather than sending empty "
            + "objects a client might render as a blank fact row")
    @WithMockUser(roles = {"PRO_USER"})
    void degradedEntriesOmitEmptyCollections() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(response(datesOnly()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].meta").doesNotExist())
                .andExpect(jsonPath("$.entries[0].regions").doesNotExist())
                .andExpect(jsonPath("$.entries[0].startDate").value("2026-10-30"));
    }

    @Test
    @DisplayName("a merged entry serves its day marks and typed coincidence lines through the real web "
            + "mapper; an unmerged entry serves neither key at all")
    @WithMockUser(roles = {"PRO_USER"})
    void mergedEntryServesDayMarks() throws Exception {
        ComingUpEntry base = springRun();
        ComingUpEntry merged = new ComingUpEntry(base.startDate(), base.endDate(), base.kind(),
                base.type(), "Spring tide run, on a supermoon", base.detail(), base.meta(),
                base.regions(), base.enteredWindow(), base.id(), "coastal", base.kindTag(), null, null,
                null, List.of(), null, null, null, base.action(), 8.0, false, null,
                List.of(new ComingUpCoincidenceLine("coastal", "Spring tide run", "Sun 16 – Tue 18 Aug",
                                "spring-tide"),
                        new ComingUpCoincidenceLine("sun-moon", "Supermoon", "Mon 17 Aug", "supermoon"),
                        new ComingUpCoincidenceLine("sun-moon", "Untyped", "Tue 18 Aug", null)),
                "Same cause, two effects.",
                List.of(new ComingUpDayMark(LocalDate.of(2026, 8, 17), "full moon", "sun-moon")),
                List.of(new ComingUpDayRange(LocalDate.of(2026, 8, 16), 4.3, false, null),
                        new ComingUpDayRange(LocalDate.of(2026, 8, 17), 4.9, true, 2)),
                3.4);
        when(almanacService.getFeed(90)).thenReturn(response(merged, springRun()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].dayMarks[0].date").value("2026-08-17"))
                .andExpect(jsonPath("$.entries[0].dayMarks[0].label").value("full moon"))
                .andExpect(jsonPath("$.entries[0].dayMarks[0].family").value("sun-moon"))
                .andExpect(jsonPath("$.entries[0].coincidence[1].type").value("supermoon"))
                .andExpect(jsonPath("$.entries[0].coincidence[2].name").value("Untyped"))
                .andExpect(jsonPath("$.entries[0].coincidence[2].type").doesNotExist())
                .andExpect(jsonPath("$.entries[0].dayRanges[1].date").value("2026-08-17"))
                .andExpect(jsonPath("$.entries[0].dayRanges[1].rangeMetres").value(4.9))
                .andExpect(jsonPath("$.entries[0].dayRanges[1].peak").value(true))
                .andExpect(jsonPath("$.entries[0].dayRanges[0].peak").value(false))
                .andExpect(jsonPath("$.entries[0].dayRanges[1].rangePercentile").value(2))
                .andExpect(jsonPath("$.entries[0].dayRanges[0].rangePercentile").doesNotExist())
                .andExpect(jsonPath("$.entries[0].rangeAverageMetres").value(3.4))
                .andExpect(jsonPath("$.entries[1].rangeAverageMetres").doesNotExist())
                .andExpect(jsonPath("$.entries[1].dayMarks").doesNotExist())
                .andExpect(jsonPath("$.entries[1].dayRanges").doesNotExist())
                .andExpect(jsonPath("$.entries[1].coincidence").doesNotExist());
    }

    @Test
    @DisplayName("the days parameter is passed through to the service")
    @WithMockUser(roles = {"PRO_USER"})
    void passesTheRequestedHorizon() throws Exception {
        when(almanacService.getFeed(30)).thenReturn(response());

        mockMvc.perform(get("/api/almanac").param("days", "30"))
                .andExpect(status().isOk());

        verify(almanacService).getFeed(30);
    }

    @Test
    @DisplayName("defaults to 90 days when the parameter is absent")
    @WithMockUser(roles = {"PRO_USER"})
    void defaultsToNinetyDays() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(response());

        mockMvc.perform(get("/api/almanac")).andExpect(status().isOk());

        verify(almanacService).getFeed(AlmanacService.DEFAULT_DAYS);
    }

    @Test
    @DisplayName("an out-of-range days value reaches the service to be clamped, rather than 400 — "
            + "a feed is more use to a miscoded client than a validation error")
    @WithMockUser(roles = {"PRO_USER"})
    void outOfRangeDaysIsNotRejected() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(response());

        mockMvc.perform(get("/api/almanac").param("days", "100000"))
                .andExpect(status().isOk());

        verify(almanacService).getFeed(100000);
    }

    // -------------------------------------------------------------------------
    // Access — the Javadoc claims Bearer with no role gate. These pin that claim,
    // because "no @PreAuthorize" is the same source code as "someone forgot one".
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a LITE user may read the feed — it is ephemeris, not premium detail")
    @WithMockUser(roles = {"LITE_USER"})
    void liteUsersMayRead() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(response(springRun()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    @DisplayName("an admin may read it too — no role is excluded")
    @WithMockUser(roles = {"ADMIN"})
    void adminsMayRead() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(response());

        mockMvc.perform(get("/api/almanac")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unauthenticated request is rejected — Bearer is inherited from /api/**, so "
            + "this fails if that inheritance ever stops applying")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/almanac")).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Role shaping (tide-light-pro-plan.md §3 B2): the tide against the light is Pro and Admin.
    // -------------------------------------------------------------------------

    private static final String PRO_LABEL = "Sun 16 – Tue 18 Aug · biggest Mon 17 · 4.9 m · HW 15:48";
    private static final String LITE_LABEL = "Sun 16 – Tue 18 Aug · biggest Mon 17 · 4.9 m";

    /** A run carrying a range row, an event-water row and an aligned clock, so the roles differ. */
    private static ComingUpEntry lightEntry() {
        return springRun()
                .withFacts(List.of(
                        new ComingUpFact(List.of(new ComingUpFact.Segment("range 4.9 m", "base"))),
                        new ComingUpFact(List.of(new ComingUpFact.Segment("sunrise · HW 07:40, 10m before",
                                "base")), ComingUpFact.Kind.EVENT_WATER)))
                .withCoincidence(List.of(new ComingUpCoincidenceLine("coastal", "Spring tide run", PRO_LABEL,
                        "spring-tide", LITE_LABEL)));
    }

    @Test
    @DisplayName("a LITE reader gets no event-water rows and no aligned clock, and the entry says so")
    @WithMockUser(roles = {"LITE_USER"})
    void liteBodyHasNoLightRows() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(response(lightEntry()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].facts.length()").value(1))
                .andExpect(jsonPath("$.entries[0].facts[0].segments[0].text").value("range 4.9 m"))
                .andExpect(jsonPath("$.entries[0].coincidence[0].factsLabel").value(LITE_LABEL))
                .andExpect(jsonPath("$.entries[0].lightRowsWithheld").value(true))
                .andExpect(jsonPath("$.entries[0].coincidence[0].liteFactsLabel").doesNotExist())
                .andExpect(jsonPath("$.entries[0].facts[0].kind").doesNotExist());
    }

    @Test
    @DisplayName("a PRO reader gets the full rows and clock, and no lightRowsWithheld key")
    @WithMockUser(roles = {"PRO_USER"})
    void proBodyKeepsTheLightRows() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(response(lightEntry()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(jsonPath("$.entries[0].facts.length()").value(2))
                .andExpect(jsonPath("$.entries[0].coincidence[0].factsLabel").value(PRO_LABEL))
                .andExpect(jsonPath("$.entries[0].lightRowsWithheld").doesNotExist());
    }

    @Test
    @DisplayName("an ADMIN gets exactly the Pro body (Admin includes Pro)")
    @WithMockUser(roles = {"ADMIN"})
    void adminBodyKeepsTheLightRows() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(response(lightEntry()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(jsonPath("$.entries[0].facts.length()").value(2))
                .andExpect(jsonPath("$.entries[0].coincidence[0].factsLabel").value(PRO_LABEL))
                .andExpect(jsonPath("$.entries[0].lightRowsWithheld").doesNotExist());
    }

    @Test
    @DisplayName("Pro and Admin are handed the service's feed itself, never a copy")
    void proAndAdminAreTheServiceFeedItself() {
        AlmanacService service = mock(AlmanacService.class);
        ComingUpResponse feed = response(lightEntry());
        when(service.getFeed(90)).thenReturn(feed);
        AlmanacController controller = new AlmanacController(service, new ComingUpRoleShaper());

        assertThat(controller.getAlmanac(90, authWith("ROLE_PRO_USER"))).isSameAs(feed);
        assertThat(controller.getAlmanac(90, authWith("ROLE_ADMIN"))).isSameAs(feed);
    }

    @Test
    @DisplayName("fail closed: an unknown role, a missing authentication and an unauthenticated token are Lite")
    void anythingElseIsLite() {
        AlmanacService service = mock(AlmanacService.class);
        ComingUpResponse feed = response(lightEntry());
        when(service.getFeed(90)).thenReturn(feed);
        AlmanacController controller = new AlmanacController(service, new ComingUpRoleShaper());
        TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken("u", "p", "ROLE_PRO_USER");
        unauthenticated.setAuthenticated(false);

        for (Authentication auth : new Authentication[] {authWith("ROLE_LITE_USER"),
                authWith("ROLE_MYSTERY"), unauthenticated, null}) {
            ComingUpEntry served = controller.getAlmanac(90, auth).entries().getFirst();
            assertThat(served.facts()).hasSize(1);
            assertThat(served.coincidence().getFirst().factsLabel()).isEqualTo(LITE_LABEL);
            assertThat(served.lightRowsWithheld()).isTrue();
        }
        // The cached feed is never mutated by shaping.
        assertThat(feed.entries().getFirst().facts()).hasSize(2);
    }

    @Test
    @DisplayName("the days value reaches the service unchanged for every role")
    void daysReachesTheServiceForEveryRole() {
        AlmanacService service = mock(AlmanacService.class);
        when(service.getFeed(30)).thenReturn(response());
        AlmanacController controller = new AlmanacController(service, new ComingUpRoleShaper());

        controller.getAlmanac(30, authWith("ROLE_LITE_USER"));
        controller.getAlmanac(30, authWith("ROLE_PRO_USER"));

        verify(service, times(2)).getFeed(30);
    }

    private static Authentication authWith(String authority) {
        return new TestingAuthenticationToken("u", "p", authority);
    }
}
