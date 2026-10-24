package com.gregochr.goldenhour.model;

/**
 * A location's id and name only, so the name-namespace guard need not load whole locations.
 *
 * @param id   the location's primary key
 * @param name the location's name
 */
public record LocationNameRow(Long id, String name) {
}
