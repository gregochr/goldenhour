package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link AppUserEntity}.
 */
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    /**
     * Finds a user by their unique login name.
     *
     * @param username the username to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByUsername(String username);

    /**
     * Returns whether a user with the given username already exists.
     *
     * @param username the username to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByUsername(String username);

    /**
     * Returns whether a user with the given email already exists.
     *
     * @param email the email to check
     * @return {@code true} if a matching user exists
     */
    boolean existsByEmail(String email);

    /**
     * Finds a user by their email address.
     *
     * @param email the email to search for
     * @return an {@link Optional} containing the user, or empty if not found
     */
    Optional<AppUserEntity> findByEmail(String email);

    /**
     * Counts the number of users whose role is not the specified value.
     *
     * <p>Used to count non-admin users for the early-access registration cap.
     *
     * @param role the role to exclude from the count
     * @return the number of users with a different role
     */
    long countByRoleNot(UserRole role);
}
