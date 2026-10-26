package com.gregochr.goldenhour.model;

/**
 * The body of {@code PUT /api/admin/sub-region-policy}.
 *
 * @param liteLimit the new limit on a Lite reader's picks
 */
public record SubRegionPolicyRequest(Integer liteLimit) {
}
