package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.SubRegionPolicyEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.SubRegionPolicyResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.SubRegionPolicyRepository;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The admin-set limit on how many sub-regions a Lite reader may pick (sub-regions-plan D5, §9 Q1).
 *
 * <p>One row in {@code sub_region_policy}, read through an in-memory snapshot (the limit is read on every
 * picks request and changes a few times in the product's life; the app is single-instance, so a write simply
 * drops the snapshot, again when its transaction ends). A missing row reads as {@link #DEFAULT_LITE_LIMIT},
 * which is what a local Hibernate-built schema, with no seeded row, has.
 */
@Service
public class SubRegionPolicyService {

    /** The limit when the table holds no row, and the value V192 seeds. */
    public static final int DEFAULT_LITE_LIMIT = 3;

    /** The largest limit the table's CHECK accepts. */
    public static final int MAX_LIMIT = 24;

    private static final Logger LOG = LoggerFactory.getLogger(SubRegionPolicyService.class);

    private final SubRegionPolicyRepository policyRepository;
    private final AreaRepository areaRepository;
    private final AppUserRepository userRepository;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    /**
     * Constructs the service.
     *
     * @param policyRepository the policy row
     * @param areaRepository   the areas, to count the sub-regions
     * @param userRepository   the users, to count readers over a limit
     * @param clock            supplies the save time
     */
    public SubRegionPolicyService(SubRegionPolicyRepository policyRepository, AreaRepository areaRepository,
            AppUserRepository userRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.areaRepository = areaRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * The limit on a Lite reader's picks.
     *
     * @return the saved limit, or {@link #DEFAULT_LITE_LIMIT} when there is none
     */
    public int getLiteLimit() {
        return current().limit();
    }

    /**
     * The policy as the admin control shows it.
     *
     * @param candidate a limit the admin is considering, so the response counts the readers it would leave
     *                  over their limit; null for the saved one
     * @return the saved limit, the largest limit allowed, and the over-limit count for the candidate
     * @throws IllegalArgumentException if the candidate is outside 1..the number of sub-regions (HTTP 400)
     */
    @Transactional(readOnly = true)
    public SubRegionPolicyResponse describe(Integer candidate) {
        Snapshot now = current();
        if (candidate != null) {
            requireInRange(candidate, maxLimit());
        }
        int against = candidate == null ? now.limit() : candidate;
        return new SubRegionPolicyResponse(now.limit(), maxLimit(), countOver(against), against,
                now.updatedAt(), now.updatedBy());
    }

    /**
     * Saves a new limit.
     *
     * @param liteLimit the new limit
     * @param updatedBy the admin's username
     * @return the saved policy, with the number of Lite readers who now hold more picks than the limit (they
     *         keep them until they next change, sub-regions-plan §4.3)
     * @throws IllegalArgumentException if the limit is missing or outside 1..the number of sub-regions
     *                                  (HTTP 400)
     */
    @Transactional
    public SubRegionPolicyResponse update(Integer liteLimit, String updatedBy) {
        int max = maxLimit();
        if (liteLimit == null) {
            throw new IllegalArgumentException("liteLimit is required");
        }
        requireInRange(liteLimit, max);
        SubRegionPolicyEntity row = policyRepository.findById(SubRegionPolicyEntity.ONLY_ID)
                .orElseGet(SubRegionPolicyEntity::new);
        row.setLiteLimit(liteLimit.shortValue());
        row.setUpdatedAt(LocalDateTime.now(clock));
        row.setUpdatedBy(updatedBy);
        policyRepository.save(row);
        invalidateSnapshot();
        LOG.info("Lite sub-region pick limit set to {} by {}", liteLimit, LogSanitizer.sanitize(updatedBy));
        return new SubRegionPolicyResponse(liteLimit, max, countOver(liteLimit), liteLimit, row.getUpdatedAt(),
                row.getUpdatedBy());
    }

    private static void requireInRange(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException("The limit must be between 1 and " + max
                    + " (the number of sub-regions); " + limit + " is not");
        }
    }

    /** The largest limit that can be saved: the number of sub-regions, never above the table's CHECK. */
    private int maxLimit() {
        return (int) Math.min(MAX_LIMIT, areaRepository.countByKind(AreaKind.SUB_REGION));
    }

    private long countOver(int limit) {
        return userRepository.countHoldingMorePicksThan(UserRole.LITE_USER, limit);
    }

    private Snapshot current() {
        Snapshot cached = snapshot.get();
        if (cached != null) {
            return cached;
        }
        Snapshot loaded = policyRepository.findById(SubRegionPolicyEntity.ONLY_ID)
                .map(row -> new Snapshot(row.getLiteLimit(), row.getUpdatedAt(), row.getUpdatedBy()))
                .orElseGet(() -> new Snapshot(DEFAULT_LITE_LIMIT, null, null));
        snapshot.set(loaded);
        return loaded;
    }

    /**
     * Drops the snapshot now, and again once the saving transaction ends, committed or rolled back: a reader
     * that re-cached a value between the two is corrected by the second drop.
     */
    private void invalidateSnapshot() {
        snapshot.set(null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    snapshot.set(null);
                }
            });
        }
    }

    private record Snapshot(int limit, LocalDateTime updatedAt, String updatedBy) {
    }
}
