package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupUnavailableException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.SaveHomeRequest;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The one-time sign-in postcode prompt's flag, end to end through the real {@link UserSettingsService}
 * and the real repository on H2 (login-registration plan §2 #3): an account with no postcode is due,
 * dismissing ends it with the instant set, the next read agrees, and saving a postcode ends it too.
 *
 * <p>Each service call commits on its own (no test transaction), as in production. The mocked-repository
 * tests in {@code UserSettingsServiceTest} cannot see the order the service reads and writes in, nor
 * that the scoped update's {@code clearAutomatically} is what makes the read-back fresh; this does.
 */
@DataJpaTest
@Import({UserSettingsService.class, UserDriveTimeWriter.class, PostcodePromptRoundTripTest.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostcodePromptRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:15:30Z");
    private static final String USERNAME = "round-trip";

    /** The one clock every service in this context reads. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private DriveDurationService driveDurationService;

    @MockitoBean
    private PostcodesIoClient postcodesIoClient;

    @Autowired
    private UserSettingsService settingsService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The dismissal instant as the row holds it: the response no longer serves it. */
    private Instant storedDismissal() {
        return userRepository.findByUsername(USERNAME).orElseThrow().getPostcodePromptDismissedAt();
    }

    private final Authentication auth = new TestingAuthenticationToken(USERNAME, "n/a");

    @BeforeEach
    void persistReaderWithNoPostcode() {
        userRepository.save(AppUserEntity.builder()
                .username(USERNAME)
                .password("{bcrypt}hash")
                .role(UserRole.LITE_USER)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                .email("round-trip@example.com")
                .build());
    }

    @AfterEach
    void deleteCommittedRows() {
        jdbcTemplate.update("DELETE FROM user_drive_time");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    @Test
    @DisplayName("due for a fresh account; dismissing answers not due WITH the instant, and the next read agrees")
    void dismissEndsThePromptAndTheNextReadAgrees() {
        UserSettingsResponse before = settingsService.getSettings(auth);
        assertThat(before.postcodePromptDue()).isTrue();
        assertThat(storedDismissal()).isNull();

        // The response of the write itself must be built from the row AFTER the write: a service that
        // read first and wrote second would answer due=true with no instant here.
        UserSettingsResponse dismissed = settingsService.markPostcodePromptDismissed(auth);
        assertThat(dismissed.postcodePromptDue()).isFalse();
        assertThat(storedDismissal()).isEqualTo(NOW);

        UserSettingsResponse after = settingsService.getSettings(auth);
        assertThat(after.postcodePromptDue()).isFalse();
        assertThat(storedDismissal()).isEqualTo(NOW);
        assertThat(after.homePostcode()).isNull();
    }

    @Test
    @DisplayName("a second dismissal is idempotent and still answers not due")
    void dismissTwice() {
        settingsService.markPostcodePromptDismissed(auth);
        UserSettingsResponse second = settingsService.markPostcodePromptDismissed(auth);

        assertThat(second.postcodePromptDue()).isFalse();
        assertThat(storedDismissal()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("saving a postcode ends the question without a dismissal; a later dismissal keeps the "
            + "postcode and does not blank it")
    void savingAPostcodeEndsIt() {
        UserSettingsResponse saved = settingsService.saveHome(auth,
                new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));
        assertThat(saved.postcodePromptDue()).isFalse();
        assertThat(storedDismissal()).isNull();

        UserSettingsResponse dismissed = settingsService.markPostcodePromptDismissed(auth);
        assertThat(dismissed.homePostcode()).isEqualTo("DH1 3LE");
        assertThat(dismissed.homeLatitude()).isEqualTo(54.7761);
        assertThat(dismissed.postcodePromptDue()).isFalse();
    }

    @Test
    @DisplayName("a dismissal survives a later home save: the response carries the instant and not-due")
    void dismissalSurvivesAHomeSave() {
        settingsService.markPostcodePromptDismissed(auth);

        UserSettingsResponse saved = settingsService.saveHome(auth,
                new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));

        assertThat(storedDismissal()).isEqualTo(NOW);
        assertThat(saved.postcodePromptDue()).isFalse();
    }

    @Test
    @DisplayName("a postcodes.io OUTAGE on the place-name lookup leaves the settings read working, "
            + "placeName null and the flag still computed — the settings read is the prompt's only source")
    void outageDuringTheSettingsReadDoesNotFailIt() {
        settingsService.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));
        when(postcodesIoClient.lookup(anyString())).thenThrow(new PostcodeLookupUnavailableException());

        UserSettingsResponse response = settingsService.getSettings(auth);

        assertThat(response.homePostcode()).isEqualTo("DH1 3LE");
        assertThat(response.homePlaceName()).isNull();
        assertThat(response.postcodePromptDue()).isFalse();
    }

    @Test
    @DisplayName("the place name from a successful lookup rides the settings read")
    void placeNameRidesTheRead() {
        settingsService.saveHome(auth, new SaveHomeRequest("DH1 3LE", 54.7761, -1.5733, null));
        when(postcodesIoClient.lookup("DH1 3LE")).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));

        assertThat(settingsService.getSettings(auth).homePlaceName()).isEqualTo("Durham, County Durham");
    }
}
