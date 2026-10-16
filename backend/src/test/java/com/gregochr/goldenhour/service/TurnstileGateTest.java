package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.TurnstileGate.Endpoint;
import com.gregochr.goldenhour.service.TurnstileGate.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TurnstileGate} with a mocked verifier and a mocked limiter: the unconfigured and verified paths,
 * a failing token never being a fallback, and the fallback's three bounds checked outermost first with the
 * first refusal winning and the later keys never touched.
 */
@ExtendWith(MockitoExtension.class)
class TurnstileGateTest {

    @Mock
    private TurnstileService turnstileService;

    @Mock
    private AuthAttemptLimiter limiter;

    private TurnstileGate gate;

    @BeforeEach
    void setUp() {
        gate = new TurnstileGate(turnstileService, limiter);
    }

    @Test
    @DisplayName("Turnstile not configured: verified, whatever the token, and the limiter is never consulted")
    void unconfigured_isVerified_withoutTheLimiter() {
        when(turnstileService.isConfigured()).thenReturn(false);

        assertThat(gate.admit(null, "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_VERIFIED);
        assertThat(gate.admit("junk", "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_VERIFIED);

        verifyNoInteractions(limiter);
        verify(turnstileService, never()).verify("junk");
    }

    @Test
    @DisplayName("a token that verifies is OK_VERIFIED and the limiter is never consulted")
    void validToken_isVerified() {
        when(turnstileService.isConfigured()).thenReturn(true);
        when(turnstileService.verify("good")).thenReturn(true);

        assertThat(gate.admit("good", "alice", "203.0.113.7", Endpoint.CHANGE_PASSWORD))
                .isEqualTo(Outcome.OK_VERIFIED);

        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("a token that FAILS is CAPTCHA_FAILED and is never a fallback: the limiter is not consulted")
    void failingToken_isNeverAFallback() {
        when(turnstileService.isConfigured()).thenReturn(true);
        when(turnstileService.verify("bad")).thenReturn(false);

        assertThat(gate.admit("bad", "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.CAPTCHA_FAILED);

        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("a blank token is a fallback, not a verification: verify() is never called with it")
    void blankToken_goesToTheFallback() {
        when(turnstileService.isConfigured()).thenReturn(true);
        stubAllBoundsAdmit(Endpoint.LOGIN, "203.0.113.7", "alice");

        assertThat(gate.admit("   ", "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_FALLBACK);

        verify(turnstileService, never()).verify("   ");
    }

    private void stubAllBoundsAdmit(Endpoint endpoint, String client, String id) {
        String prefix = "fallback:" + endpoint.keyName();
        when(limiter.tryAcquire(prefix + ":global", endpoint.globalPerHour(), Window.HOUR)).thenReturn(true);
        when(limiter.tryAcquire(prefix + ":client:" + client, 20, Window.QUARTER_HOUR)).thenReturn(true);
        when(limiter.tryAcquire(prefix + ":id:" + id, 5, Window.QUARTER_HOUR)).thenReturn(true);
    }

    @Test
    @DisplayName("no token, every bound admits: OK_FALLBACK, with the three keys checked global, client, identifier")
    void fallback_checksTheThreeBoundsOutermostFirst() {
        when(turnstileService.isConfigured()).thenReturn(true);
        stubAllBoundsAdmit(Endpoint.LOGIN, "203.0.113.7", "alice");

        assertThat(gate.admit(null, "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_FALLBACK);

        InOrder order = inOrder(limiter);
        order.verify(limiter).tryAcquire("fallback:login:global", 60, Window.HOUR);
        order.verify(limiter).tryAcquire("fallback:login:client:203.0.113.7", 20, Window.QUARTER_HOUR);
        order.verify(limiter).tryAcquire("fallback:login:id:alice", 5, Window.QUARTER_HOUR);
        verifyNoMoreInteractions(limiter);
    }

    @Test
    @DisplayName("a refused global bound wins: RATE_LIMITED, and the client and identifier keys are never touched")
    void globalRefused_touchesNothingAfterIt() {
        when(turnstileService.isConfigured()).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:global", 60, Window.HOUR)).thenReturn(false);

        assertThat(gate.admit(null, "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.RATE_LIMITED);

        verify(limiter).tryAcquire("fallback:login:global", 60, Window.HOUR);
        verifyNoMoreInteractions(limiter);
    }

    @Test
    @DisplayName("a refused client bound wins: RATE_LIMITED, and the identifier key is never created")
    void clientRefused_neverCreatesTheIdentifierEntry() {
        when(turnstileService.isConfigured()).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:global", 60, Window.HOUR)).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:client:203.0.113.7", 20, Window.QUARTER_HOUR)).thenReturn(false);

        assertThat(gate.admit(null, "rotating-name-1", "203.0.113.7", Endpoint.LOGIN))
                .isEqualTo(Outcome.RATE_LIMITED);

        verify(limiter, never()).tryAcquire("fallback:login:id:rotating-name-1", 5, Window.QUARTER_HOUR);
        verifyNoMoreInteractions(limiter);
    }

    @Test
    @DisplayName("a refused identifier bound is RATE_LIMITED, after global and client have both admitted")
    void identifierRefused_isRateLimited() {
        when(turnstileService.isConfigured()).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:global", 60, Window.HOUR)).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:client:203.0.113.7", 20, Window.QUARTER_HOUR)).thenReturn(true);
        when(limiter.tryAcquire("fallback:login:id:alice", 5, Window.QUARTER_HOUR)).thenReturn(false);

        assertThat(gate.admit(null, "alice", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.RATE_LIMITED);
    }

    @Test
    @DisplayName("each endpoint has its own global ceiling: login 60, change-password 20, forgot 10")
    void globalCeilings_perEndpoint() {
        assertThat(Endpoint.LOGIN.globalPerHour()).isEqualTo(60);
        assertThat(Endpoint.CHANGE_PASSWORD.globalPerHour()).isEqualTo(20);
        assertThat(Endpoint.FORGOT.globalPerHour()).isEqualTo(10);

        when(turnstileService.isConfigured()).thenReturn(true);
        stubAllBoundsAdmit(Endpoint.CHANGE_PASSWORD, "198.51.100.2", "bob");
        stubAllBoundsAdmit(Endpoint.FORGOT, "198.51.100.2", "bob@example.com");

        assertThat(gate.admit(null, "bob", "198.51.100.2", Endpoint.CHANGE_PASSWORD))
                .isEqualTo(Outcome.OK_FALLBACK);
        assertThat(gate.admit(null, "bob@example.com", "198.51.100.2", Endpoint.FORGOT))
                .isEqualTo(Outcome.OK_FALLBACK);

        verify(limiter).tryAcquire("fallback:change-password:global", 20, Window.HOUR);
        verify(limiter).tryAcquire("fallback:forgot:global", 10, Window.HOUR);
    }

    @Test
    @DisplayName("the identifier is trimmed and lower-cased, so ' Alice ' and 'alice' share one bucket")
    void identifier_isNormalised() {
        when(turnstileService.isConfigured()).thenReturn(true);
        stubAllBoundsAdmit(Endpoint.LOGIN, "203.0.113.7", "alice");

        assertThat(gate.admit(null, "  Alice ", "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_FALLBACK);

        verify(limiter).tryAcquire("fallback:login:id:alice", 5, Window.QUARTER_HOUR);
    }

    @Test
    @DisplayName("a null identifier keys on the empty string rather than throwing")
    void nullIdentifier_isTheEmptyKey() {
        when(turnstileService.isConfigured()).thenReturn(true);
        stubAllBoundsAdmit(Endpoint.LOGIN, "203.0.113.7", "");

        assertThat(gate.admit(null, null, "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_FALLBACK);
    }

    @Test
    @DisplayName("an identifier over 256 characters is cut to 256 so a caller cannot choose an unbounded key")
    void longIdentifier_isCapped() {
        when(turnstileService.isConfigured()).thenReturn(true);
        String longId = "x".repeat(10_000);
        String capped = "x".repeat(256);
        stubAllBoundsAdmit(Endpoint.LOGIN, "203.0.113.7", capped);

        assertThat(gate.admit(null, longId, "203.0.113.7", Endpoint.LOGIN)).isEqualTo(Outcome.OK_FALLBACK);

        verify(limiter).tryAcquire("fallback:login:id:" + capped, 5, Window.QUARTER_HOUR);
    }
}
