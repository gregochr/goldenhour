package com.gregochr.goldenhour.model;

/**
 * One reader's pick as a name, for the admin user list.
 *
 * @param userId   the reader
 * @param areaName the picked sub-region's name
 */
public record UserPickNameRow(Long userId, String areaName) {
}
