package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Request body for admin batch submission endpoints.
 *
 * @param regionIds optional list of region IDs to include — null or empty means all regions
 */
public record BatchSubmitRequest(List<Long> regionIds) {}
