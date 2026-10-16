package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEmailClaimEntity;
import com.gregochr.goldenhour.repository.AppUserEmailClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailClaims}: the order of its steps and which repository question it asks for a
 * new account and for an edit (login-registration plan §5 #6, §2 #14 rules 2 and 3). The database
 * behaviour itself is proven in {@code AppUserEmailClaimRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class EmailClaimsTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Mock
    private AppUserEmailClaimRepository claims;

    private EmailClaims emailClaims;

    @BeforeEach
    void setUp() {
        emailClaims = new EmailClaims(claims, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AppUserEmailClaimEntity reservation(String key) {
        return AppUserEmailClaimEntity.builder().emailLower(key).claimedAt(NOW).build();
    }

    @Test
    @DisplayName("a brand-new address is looked up under lock, then inserted immediately with the normalised "
            + "key and the clock's instant")
    void brandNewAddress_lockThenInsert() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.empty());

        emailClaims.claim("  Foo@X.com ", null);

        InOrder order = inOrder(claims);
        order.verify(claims).findByEmailLowerForUpdate("foo@x.com");
        order.verify(claims).insertClaim("foo@x.com", NOW);
        verify(claims, never()).existsAnyOccupant(anyString());
    }

    @Test
    @DisplayName("an existing reservation held by a live account (new account) is refused with the shared "
            + "sentence, and nothing is inserted")
    void reservedAndOccupied_newAccount_isRefused() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.of(reservation("foo@x.com")));
        when(claims.existsAnyOccupant("foo@x.com")).thenReturn(true);

        assertThatThrownBy(() -> emailClaims.claim("FOO@x.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");

        verify(claims, never()).insertClaim(anyString(), any());
        verify(claims, never()).existsOtherOccupant(anyString(), anyLong());
    }

    @Test
    @DisplayName("an orphaned reservation is reused silently: no insert, no exception — what lets a retried "
            + "pending sign-up succeed against its replaced row's reservation")
    void reservedButOrphaned_isReusedSilently() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.of(reservation("foo@x.com")));
        when(claims.existsAnyOccupant("foo@x.com")).thenReturn(false);

        assertThatCode(() -> emailClaims.claim("foo@x.com", null)).doesNotThrowAnyException();

        verify(claims, never()).insertClaim(anyString(), any());
    }

    @Test
    @DisplayName("an EDIT asks whether ANOTHER account holds the key, never with a null id: "
            + "the account being edited is not its own occupant")
    void edit_asksTheOtherOccupantQuestion() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.of(reservation("foo@x.com")));
        when(claims.existsOtherOccupant("foo@x.com", 42L)).thenReturn(false);

        assertThatCode(() -> emailClaims.claim("FOO@x.com", 42L)).doesNotThrowAnyException();

        verify(claims).existsOtherOccupant("foo@x.com", 42L);
        verify(claims, never()).existsAnyOccupant(anyString());
    }

    @Test
    @DisplayName("an edit onto an address another account holds is refused")
    void edit_ontoAnotherAccountsAddress_isRefused() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.of(reservation("foo@x.com")));
        when(claims.existsOtherOccupant("foo@x.com", 42L)).thenReturn(true);

        assertThatThrownBy(() -> emailClaims.claim("foo@x.com", 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");
    }

    @Test
    @DisplayName("the one violation the insert can still raise (a concurrent first claim) propagates "
            + "untouched: it is never caught and continued from")
    void concurrentFirstClaim_violationPropagates() {
        when(claims.findByEmailLowerForUpdate("foo@x.com")).thenReturn(Optional.empty());
        DataIntegrityViolationException violation = new DataIntegrityViolationException("duplicate key");
        when(claims.insertClaim("foo@x.com", NOW)).thenThrow(violation);

        assertThatThrownBy(() -> emailClaims.claim("foo@x.com", null)).isSameAs(violation);
    }

    @Test
    @DisplayName("release delegates to the conditional delete and reports whether it freed the key")
    void release_reportsTheRowCount() {
        when(claims.releaseIfUnused("foo@x.com")).thenReturn(1);
        when(claims.releaseIfUnused("kept@x.com")).thenReturn(0);

        assertThat(emailClaims.release(" Foo@X.com ")).isTrue();
        assertThat(emailClaims.release("kept@x.com")).isFalse();
    }
}
