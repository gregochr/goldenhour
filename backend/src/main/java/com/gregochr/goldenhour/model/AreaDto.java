package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * A named area as the admin API serves it.
 *
 * @param id          the area's primary key
 * @param name        the area's name
 * @param aliases     other names for it, sorted
 * @param locationIds the member locations' ids, ascending
 */
public record AreaDto(Long id, String name, List<String> aliases, List<Long> locationIds) {
}
