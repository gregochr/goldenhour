package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AskAdmissionInterceptor;
import com.gregochr.goldenhour.config.AskBodyLimitFilter;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import com.gregochr.goldenhour.service.ask.AskDenialCounter;
import com.gregochr.goldenhour.service.ask.AskEngine;
import com.gregochr.goldenhour.service.ask.AskJobRunService;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskRateLimiter;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskService;
import com.gregochr.goldenhour.service.ask.AskSnapshotBuilder;
import com.gregochr.goldenhour.service.ask.AskSpendGuard;
import com.gregochr.goldenhour.service.ask.AskUsageStore;
import com.gregochr.goldenhour.service.ask.NoOpAskAnswerCache;
import com.gregochr.goldenhour.service.ask.NoOpAskIntentMatcher;
import com.gregochr.goldenhour.service.ask.NoOpAskLog;
import com.gregochr.goldenhour.service.ask.NoOpAskPreFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The typed endpoint's rate limit where it now sits: the REAL {@link AskAdmissionInterceptor}, the
 * REAL {@link AskController}, the REAL {@link AskService} and the REAL {@link AskRateLimiter} (5 a
 * minute), with the rest of the service's collaborators mocked so that a valid request ends in a
 * quick 503 (no briefing built) after the limiter has counted it.
 *
 * <p>That makes the counting observable from outside: if a request were counted twice (once by the
 * interceptor, once by the controller or the service), the third valid request would already be
 * refused; if a request that fails body conversion were not counted, the sixth malformed one would
 * still be 400. The assertions are on exactly those boundaries.
 */
class AskAdmissionTest {

    private static final String URL = "/api/ask";
    private static final String VALID = "{\"question\":\"Best spot tonight?\",\"view\":\"plan\"}";

    private final AskProperties properties = new AskProperties();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final AskEngine engine = mock(AskEngine.class);
    private final AskUsageStore usageStore = mock(AskUsageStore.class);
    private MockMvc mockMvc;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(users.findByUsername("reader")).thenReturn(Optional.of(
                AppUserEntity.builder().id(41L).username("reader").role(UserRole.LITE_USER).build()));
        when(snapshotBuilder.current()).thenReturn(Optional.empty());
        Clock clock = Clock.systemUTC();
        AskJobRunService jobRuns = mock(AskJobRunService.class);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        AskService service = new AskService(properties, new AskRateLimiter(properties, clock), users,
                mock(RegionRepository.class), snapshotBuilder, engine, usageStore,
                new AskSpendGuard(properties, jobRuns, mock(AdminAlertService.class), clock),
                mock(AskReadyService.class), mock(DriveTimeResolver.class), new NoOpAskPreFilter(),
                new NoOpAskIntentMatcher(), new NoOpAskAnswerCache(), new NoOpAskLog(),
                new AskDenialCounter(clock), clock);
        AskController controller = new AskController(properties, mock(AskReadyService.class),
                mock(RegionRepository.class), service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(new String[] {URL}, new AskAdmissionInterceptor(properties, service))
                .addFilters(new AskBodyLimitFilter())
                .build();
        auth = new TestingAuthenticationToken("reader", "n/a", "ROLE_LITE_USER");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(URL).principal(auth).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("five malformed bodies are 400 and the sixth is 429 RATE_LIMITED, refused before it is parsed")
    void sixthMalformedRequestIsLimitedBeforeParsing() throws Exception {
        for (int i = 1; i <= 5; i++) {
            send("{not json " + i).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID"));
        }

        send("{not json 6").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error").value("Slow down a moment."));
        verifyNoInteractions(engine, usageStore);
    }

    @Test
    @DisplayName("a valid request is counted exactly once: five are answered, the sixth is limited, and the third "
            + "is not (which a double count would make it)")
    void validRequestCountsOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            send(VALID).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("TYPED_UNAVAILABLE"));
        }

        send(VALID).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("valid, malformed, mistyped and oversized requests share one window")
    void mixedRequestsShareTheWindow() throws Exception {
        send(VALID).andExpect(status().isServiceUnavailable());
        send("{not json").andExpect(status().isBadRequest());
        send("{\"question\":\"Q\",\"regionIds\":\"x\",\"view\":\"plan\"}").andExpect(status().isBadRequest());
        String oversized = "{\"question\":\"Q\",\"view\":\"plan\"," + " ".repeat(AskBodyLimitFilter.MAX_BODY_BYTES)
                + "\"x\":1}";
        send(oversized).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
        send(VALID).andExpect(status().isServiceUnavailable());

        send(VALID).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the body bound: exactly 8 KiB is read, one byte more is an unreadable body")
    void bodyBoundary() throws Exception {
        String core = "{\"question\":\"Best spot tonight?\",\"view\":\"plan\"}";
        String atLimit = core + " ".repeat(AskBodyLimitFilter.MAX_BODY_BYTES - core.length());
        String overLimit = atLimit + " ";
        assertThat(atLimit.length()).isEqualTo(AskBodyLimitFilter.MAX_BODY_BYTES);

        send(atLimit).andExpect(status().isServiceUnavailable());
        send(overLimit).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The request body could not be read."));
    }

    @Test
    @DisplayName("with Ask off nothing is counted: any number of requests, then on, and the first is not limited")
    void flagOffCountsNothing() throws Exception {
        properties.setEnabled(false);
        for (int i = 0; i < 10; i++) {
            send(VALID).andExpect(status().isNotFound());
        }
        properties.setEnabled(true);

        send(VALID).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("a token for a user that no longer exists is 401 UNAUTHENTICATED and counts nothing")
    void unknownUserIsNotCounted() throws Exception {
        Authentication ghost = new TestingAuthenticationToken("ghost", "n/a", "ROLE_LITE_USER");
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(ghost);

        mockMvc.perform(post(URL).principal(ghost).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("an anonymous authentication is never counted by the interceptor")
    void anonymousIsNeverCounted() throws Exception {
        Authentication anonymous = new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymousUser", java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);
        AskAdmissionInterceptor interceptor = new AskAdmissionInterceptor(properties, mock(AskService.class));
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", URL);

        assertThat(interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(),
                new Object())).isTrue();

        assertThat(request.getAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("only POST is admitted: a GET to the same path is not counted")
    void getIsNotCounted() throws Exception {
        AskService askService = mock(AskService.class);
        AskAdmissionInterceptor interceptor = new AskAdmissionInterceptor(properties, askService);
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", URL);

        assertThat(interceptor.preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(),
                new Object())).isTrue();

        verifyNoInteractions(askService);
    }
}
