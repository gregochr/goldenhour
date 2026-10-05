package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;

/**
 * Who is asking. A null {@code userId} is a user-less conversation (Ready precompute): nothing
 * personal — no home, no drive time — may reach it.
 *
 * @param userId        the asker's id, or null for a user-less conversation
 * @param role          the asker's role, or null when user-less
 * @param hasDriveTimes whether the asker has stored drive times
 */
public record AskUserContext(Long userId, UserRole role, boolean hasDriveTimes) {

    /**
     * The context of a user-less (Ready) conversation.
     *
     * @return a context with no user
     */
    public static AskUserContext userLess() {
        return new AskUserContext(null, null, false);
    }

    /**
     * Whether this conversation has a user behind it.
     *
     * @return true when a user id is present
     */
    public boolean hasUser() {
        return userId != null;
    }
}
