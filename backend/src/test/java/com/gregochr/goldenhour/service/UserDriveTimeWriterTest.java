package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.UserDriveTimeEntity;
import com.gregochr.goldenhour.repository.UserDriveTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link UserDriveTimeWriter}.
 */
@ExtendWith(MockitoExtension.class)
class UserDriveTimeWriterTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserDriveTimeRepository userDriveTimeRepository;

    private UserDriveTimeWriter writer;

    @BeforeEach
    void setUp() {
        writer = new UserDriveTimeWriter(userDriveTimeRepository);
    }

    @Test
    @DisplayName("replaceForUser deletes the user's rows before inserting the new ones")
    void replaceForUser_deletesThenSaves() {
        UserDriveTimeEntity driveTime = new UserDriveTimeEntity(USER_ID, 1L, 2700);

        writer.replaceForUser(USER_ID, List.of(driveTime));

        InOrder order = inOrder(userDriveTimeRepository);
        order.verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
        order.verify(userDriveTimeRepository).saveAll(List.of(driveTime));
    }

    @Test
    @DisplayName("an empty list clears the user's drive times without inserting anything")
    void replaceForUser_emptyList_deletesOnly() {
        writer.replaceForUser(USER_ID, List.of());

        verify(userDriveTimeRepository).deleteAllByUserId(USER_ID);
        verify(userDriveTimeRepository, never()).saveAll(anyList());
    }
}
