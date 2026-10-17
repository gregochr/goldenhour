package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.RewindFilter;
import com.gregochr.goldenhour.util.Rewind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RewindFilter} through the real security chain (here, beside the other controller tests,
 * because the shared {@code AbstractControllerTest} base is package-private): the {@code X-Rewind-To} header turns the
 * application {@link Clock} bean back for the span of one admin GET, and for nothing else.
 *
 * <p>The clock is read INSIDE the handler — from the mocked {@code BriefingService}'s answer, which
 * runs on the request thread while the filter's {@code try} is open — because that is the only
 * place a rewind can be observed: the filter clears it before the response returns.
 */
class RewindFilterTest extends AbstractControllerTest {

    /** Four hours ago, to the second: inside the window whatever day the suite runs. */
    private static final String REWOUND = Instant.now().minusSeconds(4 * 3600).toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Autowired
    private ListableBeanFactory beanFactory;

    @AfterEach
    void rewindIsNeverLeftBehind() {
        assertThat(Rewind.isActive()).as("a rewind must not outlive its request").isFalse();
    }

    @Test
    @DisplayName("the filter is registered once, in the security chain: its servlet-container "
            + "registration is disabled, not merely ordered")
    void registeredOnlyInTheChain() {
        // What Boot hands the servlet container at startup. With no registration bean of its own,
        // a @Component Filter is wrapped and registered as an outer filter; with one, Boot defers to
        // it — and a disabled one registers nothing. So the proof is: exactly one, and disabled.
        List<FilterRegistrationBean<?>> registrations = new ServletContextInitializerBeans(beanFactory).stream()
                .filter(FilterRegistrationBean.class::isInstance)
                .<FilterRegistrationBean<?>>map(b -> (FilterRegistrationBean<?>) b)
                .filter(r -> r.getFilter() instanceof RewindFilter)
                .toList();
        assertThat(registrations).hasSize(1);
        assertThat(registrations.get(0).isEnabled())
                .as("a second, outer run of the filter sees an empty SecurityContext and marks the "
                        + "request filtered before the chain's run can apply the rewind")
                .isFalse();
    }

    private AtomicReference<Instant> captureClockInsideHandler() {
        AtomicReference<Instant> seen = new AtomicReference<>();
        when(briefingService.getCachedBriefingForApi()).thenAnswer(inv -> {
            seen.set(clock.instant());
            return null;
        });
        return seen;
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin GET with X-Rewind-To reads the rewound instant off the clock bean")
    void adminGet_rewindsTheClock() throws Exception {
        AtomicReference<Instant> seen = captureClockInsideHandler();

        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, REWOUND))
                .andExpect(status().isNoContent());

        assertThat(seen.get()).isEqualTo(Instant.parse(REWOUND));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin GET without the header reads the real clock")
    void adminGet_noHeader_realClock() throws Exception {
        AtomicReference<Instant> seen = captureClockInsideHandler();
        Instant before = Instant.now();

        mockMvc.perform(get("/api/briefing")).andExpect(status().isNoContent());

        assertThat(seen.get()).isAfterOrEqualTo(before);
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a non-admin's X-Rewind-To is ignored: served at the real time, 200 not 403")
    void nonAdmin_headerIgnored() throws Exception {
        AtomicReference<Instant> seen = captureClockInsideHandler();
        Instant before = Instant.now();

        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, REWOUND))
                .andExpect(status().isNoContent());

        assertThat(seen.get()).isAfterOrEqualTo(before);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a malformed X-Rewind-To from an admin is a 400 naming the header")
    void admin_malformed_400() throws Exception {
        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, "yesterday at six"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Rewind-To")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an instant in the future is a 400 — it would stamp clock-dated caches ahead of everyone's now")
    void admin_future_400() throws Exception {
        String future = Instant.now().plus(RewindFilter.FUTURE_TOLERANCE).plusSeconds(60).toString();
        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, future))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("future")));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an instant a few seconds ahead of the server clock is tolerated — a browser clock can run ahead")
    void admin_slightlyAhead_tolerated() throws Exception {
        AtomicReference<Instant> seen = captureClockInsideHandler();
        Instant slightlyAhead = Instant.now().plusSeconds(20);

        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, slightlyAhead.toString()))
                .andExpect(status().isNoContent());

        assertThat(seen.get()).isEqualTo(slightlyAhead);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an instant older than the serve window is a 400 — there is nothing to render there")
    void admin_tooOld_400() throws Exception {
        String old = Instant.now().minus(Rewind.MAX_AGE).minusSeconds(60).toString();
        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, old))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("within the last 3 days")));
    }

    @Test
    @DisplayName("an anonymous request carrying X-Rewind-To is not rewound and is refused downstream as usual")
    void anonymous_headerIgnored_401() throws Exception {
        mockMvc.perform(get("/api/briefing").header(RewindFilter.HEADER, REWOUND))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("a POST carrying X-Rewind-To is not rewound — a rewind only reads")
    void post_headerIgnored() throws Exception {
        AtomicReference<Instant> seen = new AtomicReference<>();
        when(briefingService.refreshBriefingIfIdle()).thenAnswer(inv -> {
            seen.set(clock.instant());
            return true;
        });
        Instant before = Instant.now();

        mockMvc.perform(post("/api/briefing/run").header(RewindFilter.HEADER, REWOUND))
                .andExpect(status().isOk());

        assertThat(seen.get()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("an admin GET under /api/admin/ is never rewound — the admin screens read the real clock")
    void adminPrefix_headerIgnored() throws Exception {
        AtomicReference<Instant> seen = new AtomicReference<>();
        when(hotTopicSimulationService.isEnabled()).thenAnswer(inv -> {
            seen.set(clock.instant());
            return false;
        });
        when(hotTopicSimulationService.getAllTypes()).thenReturn(java.util.List.of());
        Instant before = Instant.now();

        mockMvc.perform(get("/api/admin/hot-topics/simulation").header(RewindFilter.HEADER, REWOUND))
                .andExpect(status().isOk());

        assertThat(seen.get()).isNotNull().isAfterOrEqualTo(before);
    }
}
