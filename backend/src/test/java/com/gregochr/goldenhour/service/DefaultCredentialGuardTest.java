package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.CredentialChangeWriter.Change;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultCredentialGuard}: an ADMIN row still accepting the published password is
 * neutralised through the credential writer and a WARN is logged; anything else is left alone; the guard
 * never throws.
 */
@ExtendWith(MockitoExtension.class)
class DefaultCredentialGuardTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CredentialChangeWriter credentialChangeWriter;

    private DefaultCredentialGuard guard;
    private Logger guardLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        guard = new DefaultCredentialGuard(userRepository, passwordEncoder, credentialChangeWriter);
        guardLogger = (Logger) LoggerFactory.getLogger(DefaultCredentialGuard.class);
        logs = new ListAppender<>();
        logs.start();
        guardLogger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        guardLogger.detachAppender(logs);
    }

    private AppUserEntity admin(long id, String username, String hash) {
        return AppUserEntity.builder().id(id).username(username).password(hash).role(UserRole.ADMIN)
                .enabled(true).build();
    }

    @Test
    @DisplayName("an ADMIN whose hash matches the published password is neutralised through the writer with the "
            + "admin-temporary-password change and a trusted expectation, and a WARN names the user")
    void matchingAdmin_isNeutralisedAndWarned() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin(1L, "admin", "$2a$10$stored")));
        when(passwordEncoder.matches("golden2026", "$2a$10$stored")).thenReturn(true);

        guard.disableDefaultAdminPassword();

        verify(credentialChangeWriter).apply(1L, "!disabled-default-admin", Change.adminTemporaryPassword(),
                VersionExpectation.trusted());
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Default admin password was still in use for "
                    + "'admin'").contains("sign-in disabled").contains("DEVOPS.md");
        });
    }

    @Test
    @DisplayName("the replacement hash is not BCrypt-shaped, so no password can ever match it")
    void replacementHash_isNotBcryptShaped() {
        assertThat(DefaultCredentialGuard.DISABLED_HASH).doesNotStartWith("$2");
    }

    @Test
    @DisplayName("an ADMIN whose hash does not match is left alone, with no write and no WARN")
    void nonMatchingAdmin_isLeftAlone() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin(1L, "boss", "$2a$10$other")));
        when(passwordEncoder.matches("golden2026", "$2a$10$other")).thenReturn(false);

        guard.disableDefaultAdminPassword();

        verifyNoInteractions(credentialChangeWriter);
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("only ADMIN rows are asked for: a non-ADMIN row is never checked")
    void onlyAdminsAreChecked() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        guard.disableDefaultAdminPassword();

        verify(userRepository).findByRole(UserRole.ADMIN);
        verify(userRepository, never()).findAll();
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(credentialChangeWriter);
    }

    @Test
    @DisplayName("a renamed default admin is caught by its hash, not its name")
    void renamedAdmin_isCaughtByHash() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin(4L, "chris", "$2a$10$rnd")));
        when(passwordEncoder.matches("golden2026", "$2a$10$rnd")).thenReturn(true);

        guard.disableDefaultAdminPassword();

        verify(credentialChangeWriter).apply(4L, "!disabled-default-admin", Change.adminTemporaryPassword(),
                VersionExpectation.trusted());
    }

    @Test
    @DisplayName("a writer failure is logged at ERROR and not propagated, and the next admin is still checked")
    void writerFailure_isLoggedNotPropagated() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(
                admin(1L, "admin", "$2a$10$one"), admin(2L, "admin2", "$2a$10$two")));
        when(passwordEncoder.matches("golden2026", "$2a$10$one")).thenReturn(true);
        when(passwordEncoder.matches("golden2026", "$2a$10$two")).thenReturn(true);
        when(credentialChangeWriter.apply(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(new CredentialChangeOutcome(2L, "admin2", null, 1L));

        assertThatCode(() -> guard.disableDefaultAdminPassword()).doesNotThrowAnyException();

        verify(credentialChangeWriter).apply(2L, "!disabled-default-admin", Change.adminTemporaryPassword(),
                VersionExpectation.trusted());
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("a repository failure is logged at ERROR and not propagated")
    void repositoryFailure_isLoggedNotPropagated() {
        when(userRepository.findByRole(UserRole.ADMIN)).thenThrow(new IllegalStateException("no table"));

        assertThatCode(() -> guard.disableDefaultAdminPassword()).doesNotThrowAnyException();

        assertThat(logs.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("the guard is skipped only under the local profile: it is declared !local, so it fails closed")
    void profile_isEverythingButLocal() {
        Profile profile = DefaultCredentialGuard.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("!local");
    }
}
