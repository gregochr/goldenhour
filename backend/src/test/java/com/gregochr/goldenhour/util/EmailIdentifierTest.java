package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.util.EmailIdentifier.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailIdentifier}: the one rule every email match in the auth flows follows
 * (login-registration plan §1 #5).
 */
@ExtendWith(MockitoExtension.class)
class EmailIdentifierTest {

    @Mock
    private AppUserRepository users;

    private static AppUserEntity account(long id, String email, boolean enabled, String password) {
        return AppUserEntity.builder().id(id).username("u" + id).email(email).enabled(enabled)
                .password(password).role(UserRole.LITE_USER).build();
    }

    @Test
    @DisplayName("normalise trims and lower-cases with Locale.ROOT: a Turkish dotless i is not folded to I")
    void normalise_isTrimAndRootLowerCase() {
        assertThat(EmailIdentifier.normalise("  Foo@Example.COM ")).isEqualTo("foo@example.com");
        assertThat(EmailIdentifier.normalise("bob@GMAİL.com")).isNotEqualTo("bob@gmail.com");
        assertThat(EmailIdentifier.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("isAscii accepts 7-bit text and refuses anything wider, including a null")
    void isAscii() {
        assertThat(EmailIdentifier.isAscii("bob@gmail.com")).isTrue();
        assertThat(EmailIdentifier.isAscii("")).isTrue();
        assertThat(EmailIdentifier.isAscii("bob@gmaıl.com")).isFalse();
        assertThat(EmailIdentifier.isAscii("josé@example.com")).isFalse();
        assertThat(EmailIdentifier.isAscii(null)).isFalse();
    }

    @Test
    @DisplayName("a null or blank identifier resolves to nothing without a query")
    void blank_resolvesToNothing() {
        assertThat(EmailIdentifier.resolve(null, users, Kind.ACTIVE)).isEmpty();
        assertThat(EmailIdentifier.resolve("   ", users, Kind.ACTIVE)).isEmpty();

        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("an exact match is returned trimmed and the case-insensitive query is not asked")
    void exactMatch_winsAndStopsThere() {
        AppUserEntity foo = account(1, "Foo@x.com", true, "hash");
        when(users.findByEmail("Foo@x.com")).thenReturn(Optional.of(foo));

        assertThat(EmailIdentifier.resolve("  Foo@x.com ", users, Kind.ACTIVE)).contains(foo);

        verify(users, never()).findActiveByEmailLower(anyString());
    }

    @Test
    @DisplayName("an exact match that is not of the requested kind is no match, and does not fall through to "
            + "a different account")
    void exactMatchOfTheWrongKind_isNoMatch() {
        AppUserEntity disabled = account(1, "Foo@x.com", false, "hash");
        when(users.findByEmail("Foo@x.com")).thenReturn(Optional.of(disabled));

        assertThat(EmailIdentifier.resolve("Foo@x.com", users, Kind.ACTIVE)).isEmpty();
        verify(users, never()).findActiveByEmailLower(anyString());

        AppUserEntity active = account(2, "Bar@x.com", true, "hash");
        when(users.findByEmail("Bar@x.com")).thenReturn(Optional.of(active));
        assertThat(EmailIdentifier.resolve("Bar@x.com", users, Kind.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE counts only enabled accounts holding a password; PENDING only the disabled-with-empty-"
            + "password shape")
    void kindsAdmitTheirOwnShapeOnly() {
        AppUserEntity active = account(1, "a@x.com", true, "hash");
        AppUserEntity pending = account(2, "b@x.com", false, "");
        AppUserEntity disabled = account(3, "c@x.com", false, "hash");
        AppUserEntity enabledNoPassword = account(4, "d@x.com", true, "");

        assertThat(EmailIdentifier.admits(active, Kind.ACTIVE)).isTrue();
        assertThat(EmailIdentifier.admits(pending, Kind.ACTIVE)).isFalse();
        assertThat(EmailIdentifier.admits(disabled, Kind.ACTIVE)).isFalse();
        assertThat(EmailIdentifier.admits(enabledNoPassword, Kind.ACTIVE)).isFalse();
        assertThat(EmailIdentifier.admits(pending, Kind.PENDING)).isTrue();
        assertThat(EmailIdentifier.admits(active, Kind.PENDING)).isFalse();
        assertThat(EmailIdentifier.admits(disabled, Kind.PENDING)).isFalse();
    }

    @Test
    @DisplayName("with no exact match an ASCII identifier falls to the case-insensitive query, keyed lower-case")
    void asciiIdentifier_fallsToTheLowerCaseQuery() {
        AppUserEntity foo = account(1, "Foo@X.com", true, "hash");
        when(users.findByEmail("FOO@x.com")).thenReturn(Optional.empty());
        when(users.findActiveByEmailLower("foo@x.com")).thenReturn(List.of(foo));

        assertThat(EmailIdentifier.resolve("FOO@x.com", users, Kind.ACTIVE)).contains(foo);
    }

    @Test
    @DisplayName("PENDING uses the pending query, not the active one")
    void pendingKind_usesThePendingQuery() {
        AppUserEntity pending = account(1, "Foo@X.com", false, "");
        when(users.findByEmail("foo@x.com")).thenReturn(Optional.empty());
        when(users.findPendingByEmailLower("foo@x.com")).thenReturn(List.of(pending));

        assertThat(EmailIdentifier.resolve("foo@x.com", users, Kind.PENDING)).contains(pending);
        verify(users, never()).findActiveByEmailLower(anyString());
    }

    @Test
    @DisplayName("two case-insensitive matches are no match; none is no match")
    void ambiguousOrAbsent_isNoMatch() {
        when(users.findByEmail("foo@x.com")).thenReturn(Optional.empty());
        when(users.findActiveByEmailLower("foo@x.com")).thenReturn(
                List.of(account(1, "Foo@x.com", true, "h"), account(2, "FOO@x.com", true, "h")));
        assertThat(EmailIdentifier.resolve("foo@x.com", users, Kind.ACTIVE)).isEmpty();

        when(users.findByEmail("none@x.com")).thenReturn(Optional.empty());
        when(users.findActiveByEmailLower("none@x.com")).thenReturn(List.of());
        assertThat(EmailIdentifier.resolve("none@x.com", users, Kind.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("a non-ASCII identifier gets the exact match only: a confusable address is never folded")
    void nonAscii_isExactOnly() {
        String confusable = "bob@gmaıl.com";
        when(users.findByEmail(confusable)).thenReturn(Optional.empty());

        assertThat(EmailIdentifier.resolve(confusable, users, Kind.ACTIVE)).isEmpty();

        verify(users, never()).findActiveByEmailLower(anyString());
        verify(users, never()).findPendingByEmailLower(anyString());

        AppUserEntity registered = account(1, confusable, true, "hash");
        when(users.findByEmail(confusable)).thenReturn(Optional.of(registered));
        assertThat(EmailIdentifier.resolve(confusable, users, Kind.ACTIVE)).contains(registered);
    }
}
