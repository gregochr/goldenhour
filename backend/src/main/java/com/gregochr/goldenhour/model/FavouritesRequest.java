package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * The body of {@code PUT /api/user/settings/favourites}: the whole list a reader wants to hold.
 *
 * @param locationIds the starred location ids (null reads as none)
 * @param areaIds     the starred area ids (null reads as none)
 */
public record FavouritesRequest(List<Long> locationIds, List<Long> areaIds) {
}
