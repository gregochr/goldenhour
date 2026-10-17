package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;

/**
 * Who is asking. A null {@code userId} is a user-less conversation (Ready precompute): nothing
 * personal — no home, no drive time — may reach it.
 *
 * @param userId        the asker's id, or null for a user-less conversation
 * @param role          the asker's role, or null when user-less
 * @param hasDriveTimes whether the asker has stored drive times
 * @param home          the asker's saved home point and local radius, or null when no home postcode is
 *                      saved (and always null for a user-less conversation). Read once from the user
 *                      row by {@code AskService}; it is what {@code rank_spots.nearHome} resolves
 */
public record AskUserContext(Long userId, UserRole role, boolean hasDriveTimes, Home home) {

    /**
     * An asker's saved home, for {@code nearHome}.
     *
     * @param latitude    the home latitude
     * @param longitude   the home longitude
     * @param radiusMiles the straight-line radius that counts as "close to home": the asker's saved
     *                    local radius, or the configured default when none is saved
     */
    public record Home(double latitude, double longitude, int radiusMiles) {
    }

    /**
     * A context with no saved home.
     *
     * @param userId        the asker's id, or null
     * @param role          the asker's role, or null
     * @param hasDriveTimes whether the asker has stored drive times
     */
    public AskUserContext(Long userId, UserRole role, boolean hasDriveTimes) {
        this(userId, role, hasDriveTimes, null);
    }

    /**
     * The context of a user-less (Ready) conversation.
     *
     * @return a context with no user
     */
    public static AskUserContext userLess() {
        return new AskUserContext(null, null, false, null);
    }

    /**
     * Whether this conversation has a user behind it.
     *
     * @return true when a user id is present
     */
    public boolean hasUser() {
        return userId != null;
    }

    /**
     * Whether an aurora figure may reach this conversation: the aurora forecast results are served
     * to PRO and ADMIN accounts only ({@code AuroraForecastController}), so Ask withholds them from
     * every other asker, and from a user-less (Ready) conversation, whose answer is shared by every
     * reader. The one definition, derived from the role: it is threaded explicitly to the night tool,
     * the validator, the thread re-join and the Ready freshness re-check, none of which may decide it
     * for themselves.
     *
     * @return true when a user is present and holds the ADMIN or PRO_USER role
     */
    public boolean canSeeAurora() {
        return hasUser() && mayViewAurora(role);
    }

    /**
     * The role rule behind {@link #canSeeAurora}, for the places that hold a role and no context yet: ADMIN
     * and PRO_USER, the roles {@code AuroraForecastController} serves to.
     *
     * @param role the account's role; may be null
     * @return true when the role may see aurora figures
     */
    public static boolean mayViewAurora(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.PRO_USER;
    }

    /**
     * Whether the asker has a saved home point, so {@code nearHome} can be resolved.
     *
     * @return true when a user is present and a home is saved
     */
    public boolean hasHome() {
        return hasUser() && home != null;
    }
}
