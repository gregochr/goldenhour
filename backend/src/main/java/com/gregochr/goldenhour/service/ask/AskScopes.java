package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the region ids a question names to an {@link AskScope}: the one place a scope is built
 * from the database, so the typed endpoint, the admin dry-run, the Ready serve and both engines
 * cannot disagree about what a scope is, and the database is asked once per question.
 *
 * <p>Scope is a safety boundary. <b>An id that does not resolve, or resolves to a disabled region,
 * is not dropped</b>: silently dropping it would leave an empty scope, which every consumer reads as
 * "every region", so a typo would widen the question rather than fail it. Empty ids genuinely mean
 * "every region" and resolve to {@link AskScope#ALL}.
 */
public final class AskScopes {

    /** The most region ids a question may name; the roster is a handful, so more is a mistake. */
    public static final int MAX_REGION_IDS = 20;

    private AskScopes() {
    }

    /**
     * The scope a request may ask about, when every id is a distinct, enabled region.
     *
     * @param regionRepository resolves ids to regions
     * @param requested        the ids asked for; null or empty means every region
     * @return the scope (its ids distinct and in the order given), or empty when there are more than
     *         {@value #MAX_REGION_IDS}, one is null, or any is unknown or disabled
     */
    public static Optional<AskScope> resolve(RegionRepository regionRepository,
            Collection<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return Optional.of(AskScope.ALL);
        }
        // Not requested.contains(null): an immutable list throws on that, and a null id is a refusal.
        if (requested.size() > MAX_REGION_IDS || requested.stream().anyMatch(Objects::isNull)) {
            return Optional.empty();
        }
        Set<Long> ids = new LinkedHashSet<>(requested);
        List<RegionEntity> found = regionRepository.findAllById(ids);
        Set<Long> foundIds = found.stream().map(RegionEntity::getId).collect(Collectors.toSet());
        boolean allEnabled = found.size() == ids.size() && foundIds.containsAll(ids)
                && found.stream().allMatch(RegionEntity::isEnabled);
        if (!allEnabled) {
            return Optional.empty();
        }
        return Optional.of(AskScope.of(new ArrayList<>(ids),
                found.stream().map(RegionEntity::getName).toList()));
    }
}
