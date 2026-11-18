package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.FavouriteLocations;

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
 * @param favourites    the asker's favourite locations and starred-area names (find-and-favourites-plan F4b);
 *                      never null (empty for a user-less conversation and for any asker who may not have favourites)
 */
public record AskUserContext(Long userId, UserRole role, boolean hasDriveTimes, Home home,
        FavouriteLocations favourites) {

    /** Canonical constructor: a null {@code favourites} reads as none. */
    public AskUserContext {
        favourites = favourites == null ? FavouriteLocations.NONE : favourites;
    }

    /**
     * A context with a home and no favourites.
     *
     * @param userId        the asker's id, or null
     * @param role          the asker's role, or null
     * @param hasDriveTimes whether the asker has stored drive times
     * @param home          the saved home, or null
     */
    public AskUserContext(Long userId, UserRole role, boolean hasDriveTimes, Home home) {
        this(userId, role, hasDriveTimes, home, FavouriteLocations.NONE);
    }

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
        this(userId, role, hasDriveTimes, null, FavouriteLocations.NONE);
    }

    /**
     * The context of a user-less (Ready) conversation.
     *
     * @return a context with no user
     */
    public static AskUserContext userLess() {
        return new AskUserContext(null, null, false, null, FavouriteLocations.NONE);
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
     * Whether a role may use Ask PhotoCast at all: ADMIN and PRO_USER. The one predicate behind the
     * security chain's gate, the service guard and the settings read.
     *
     * @param role the account's role; may be null
     * @return true when the role may use Ask
     */
    public static boolean mayUseAsk(UserRole role) {
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

    /**
     * Whether favourites apply to this conversation: a user is present, holds a role that may have favourites
     * (ADMIN or PRO_USER; a Lite reader never does) and has at least one. The one gate behind the {@code favourite}
     * tie-break, {@code rank_spots.favourites} and the cache's favourites fingerprint.
     *
     * @return true when the asker has favourite locations they may use
     */
    public boolean hasFavourites() {
        return hasUser() && mayUseAsk(role) && !favourites.isEmpty();
    }

    /**
     * Whether a location is one of this asker's favourites.
     *
     * @param locationId the location
     * @return true when {@link #hasFavourites} and the location is among them
     */
    public boolean isFavourite(Long locationId) {
        return hasFavourites() && favourites.locationIds().contains(locationId);
    }

    /**
     * The name of the starred area a location belongs to, for {@code favouriteArea}.
     *
     * @param locationId the location
     * @return the area name, or null when none applies
     */
    public String favouriteAreaOf(Long locationId) {
        return hasFavourites() ? favourites.areaByLocation().get(locationId) : null;
    }

    /**
     * The favourites fingerprint a personal answer is cached under, or null when this asker has no usable favourites.
     *
     * @return the fingerprint, or null
     */
    public String favouritesFingerprint() {
        return hasFavourites() ? favourites.fingerprint() : null;
    }
}
