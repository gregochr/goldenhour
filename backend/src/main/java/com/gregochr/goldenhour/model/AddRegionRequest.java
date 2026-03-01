package com.gregochr.goldenhour.model;

/**
 * Request body for adding a new region via {@code POST /api/regions}.
 *
 * @param name human-readable region identifier (e.g. "Northumberland")
 */
public record AddRegionRequest(String name) {
}
