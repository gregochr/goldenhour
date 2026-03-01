package com.gregochr.goldenhour.model;

/**
 * Request body for updating a region via {@code PUT /api/regions/{id}}.
 *
 * @param name new region name
 */
public record UpdateRegionRequest(String name) {
}
