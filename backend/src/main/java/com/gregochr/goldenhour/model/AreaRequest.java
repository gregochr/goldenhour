package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Request body for creating or renaming a named area. Membership is not part of it: the membership endpoint is
 * the only writer of an area's locations.
 *
 * @param name    the area's name
 * @param aliases other names for it (null reads as none)
 */
public record AreaRequest(String name, List<String> aliases) {
}
