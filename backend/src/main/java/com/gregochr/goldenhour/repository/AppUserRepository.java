package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AppUserEntity;
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
}
