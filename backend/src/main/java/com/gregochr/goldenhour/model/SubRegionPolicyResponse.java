package com.gregochr.goldenhour.model;

import java.time.LocalDateTime;

/**
 * The body of {@code GET}/{@code PUT /api/admin/sub-region-policy}.
 *
 * @param liteLimit          the saved limit on a Lite reader's picks
 * @param maxLimit           the largest limit that can be saved: the number of sub-regions (at most 24)
 * @param liteUsersOverLimit how many Lite readers hold more picks than {@code candidateLimit}
 * @param candidateLimit     the limit {@code liteUsersOverLimit} was counted against: the saved one, or the one
 *                           asked about
 * @param updatedAt          when the limit was last saved
 * @param updatedBy          who saved it
 */
public record SubRegionPolicyResponse(int liteLimit, int maxLimit, long liteUsersOverLimit, int candidateLimit,
        LocalDateTime updatedAt, String updatedBy) {
}
