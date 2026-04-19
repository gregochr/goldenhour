package com.gregochr.goldenhour.model;

/**
 * Lightweight projection of a region with the count of enabled locations it contains.
 *
 * @param id            region primary key
 * @param name          human-readable region name
 * @param locationCount number of enabled locations in this region
 */
public record RegionSummaryDto(Long id, String name, int locationCount) {}
