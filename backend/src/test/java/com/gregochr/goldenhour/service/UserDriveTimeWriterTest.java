package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserDriveTimeWriter}.
 */
@ExtendWith(MockitoExtension.class)
class UserDriveTimeWriterTest {

    private static final Long USER_ID = 42L;
    private static final double LAT = 54.7761;
    private static final double LON = -1.5733;
    private static final Instant CALCULATED_AT = Instant.parse("2026-09-02T08:15:00Z");

    @Mock
    private UserDriveTimeRepository userDriveTimeRepository;

    @Mock
    private AppUserRepository userRepository;

    private UserDriveTimeWriter writer;

    @BeforeEach
    void setUp() {
        writer = new UserDriveTimeWriter(userDriveTimeRepository, userRepository);
    }

    @Nested
    @DisplayName("storeIfHomeUnchanged")
    class StoreIfHomeUnchanged {

        @Test
        @DisplayName("stamps first, then replaces the rows — the stamp's row lock is what orders "
                + "it against a home save")
        void homeUnchanged_stampsThenReplaces() {
            UserDriveTimeEntity driveTime = new UserDriveTimeEntity(USER_ID, 1L, 2700);
            when(userRepository.stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT)).thenReturn(1);

            boolean stored = writer.storeIfHomeUnchanged(USER_ID, LAT, LON, List.of(driveTime),
                    CALCULATED_AT);

            assertThat(stored).isTrue();
            // Order is the guard, twice over. The compare-and-set decides whether anything is
            // written, so it runs before anything is: delete first, and a refresh that loses the
            // race has already deleted drive times a later refresh measured from the new home.
            // And a home save takes the user row before the drive-time rows; delete first here and
            // each transaction can end up waiting on a lock the other holds.
            InOrder order = inOrder(userRepository, userDriveTimeRepository);
            order.verify(userRepository).stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT);
            order.verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
            order.verify(userDriveTimeRepository).saveAll(List.of(driveTime));
            verifyNoMoreInteractions(userRepository, userDriveTimeRepository);
        }

        @Test
        @DisplayName("an empty answer clears the user's drive times without inserting anything")
        void emptyList_stampsAndDeletesOnly() {
            when(userRepository.stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT)).thenReturn(1);

            assertThat(writer.storeIfHomeUnchanged(USER_ID, LAT, LON, List.of(), CALCULATED_AT)).isTrue();

            verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
            verify(userDriveTimeRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("when the home has moved since measuring, writes nothing and says so")
        void homeMoved_writesNoRows() {
            // Deleting here would wipe drive times measured from the NEW home by a refresh that
            // got in first; inserting would store journeys from a house the user has left.
            when(userRepository.stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT)).thenReturn(0);

            boolean stored = writer.storeIfHomeUnchanged(USER_ID, LAT, LON,
                    List.of(new UserDriveTimeEntity(USER_ID, 1L, 2700)), CALCULATED_AT);

            assertThat(stored).isFalse();
            verifyNoInteractions(userDriveTimeRepository);
        }
    }

    @Nested
    @DisplayName("stampIfHomeUnchanged")
    class StampIfHomeUnchanged {

        @Test
        @DisplayName("stamps through the compare-and-set and leaves the stored rows alone")
        void homeUnchanged_stampsOnly() {
            when(userRepository.stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT)).thenReturn(1);

            assertThat(writer.stampIfHomeUnchanged(USER_ID, LAT, LON, CALCULATED_AT)).isTrue();

            verifyNoInteractions(userDriveTimeRepository);
        }

        @Test
        @DisplayName("reports a moved home as not stamped")
        void homeMoved_reportsNotStamped() {
            when(userRepository.stampDriveTimesIfHomeIs(USER_ID, LAT, LON, CALCULATED_AT)).thenReturn(0);

            assertThat(writer.stampIfHomeUnchanged(USER_ID, LAT, LON, CALCULATED_AT)).isFalse();

            verifyNoInteractions(userDriveTimeRepository);
        }
    }

    @Test
    @DisplayName("clearForUser discards that user's rows and inserts nothing in their place")
    void clearForUser_deletesAllRowsForThatUser() {
        // The action, not the decision. UserSettingsServiceTest mocks this writer, so its tests
        // assert only that the service ASKED for a clear — emptying this method's body left every
        // one of them green while a moved-house user kept every drive time measured from their old
        // address, with the log line and the cleared timestamp both claiming otherwise.
        writer.clearForUser(USER_ID);

        verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
        // A discard, not a replace: nothing goes back.
        verify(userDriveTimeRepository, never()).saveAll(anyList());
        // And the stamp is the caller's to clear, under the lock it already holds.
        verifyNoInteractions(userRepository);
    }
}
