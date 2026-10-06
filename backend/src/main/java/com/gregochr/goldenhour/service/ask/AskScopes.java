package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves a question's region ids to the region names every Ask tool and the validator match on.
 *
 * <p>One definition for both engines (and the dry-run), so the Claude engine, the stub and the
 * admin endpoint cannot disagree about what a scope is. <b>An id that does not resolve is not
 * dropped</b>: scope is a safety boundary, and silently dropping the unknown id would leave an empty
 * set, which every consumer reads as "every region" — a typo would widen the question rather than
 * fail it. Empty ids genuinely mean "every region" and resolve to an empty set.
 */
public final class AskScopes {

    /** The most region ids a question may name; the roster is a handful, so more is a mistake. */
    public static final int MAX_REGION_IDS = 20;

    private AskScopes() {
    }

    /**
     * The region ids a request may ask about, when every one is a distinct, enabled region. The one
     * definition for the typed endpoint and the admin dry-run, so a region that one refuses the other
     * cannot accept.
     *
     * @param regionRepository resolves ids to regions
     * @param requested        the ids asked for; null or empty means every region
     * @return the distinct ids in the order given (empty for "every region"), or empty when there are
     *         more than {@value #MAX_REGION_IDS}, one is null, or any is unknown or disabled
     */
    public static Optional<List<Long>> validRegionIds(RegionRepository regionRepository,
            List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return Optional.of(List.of());
        }
        // Not requested.contains(null): an immutable list throws on that, and a null id is a refusal.
        if (requested.size() > MAX_REGION_IDS || requested.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        Set<Long> ids = new LinkedHashSet<>(requested);
        List<RegionEntity> found = regionRepository.findAllById(ids);
        boolean allEnabled = found.size() == ids.size()
                && found.stream().allMatch(RegionEntity::isEnabled);
        return allEnabled ? Optional.of(new ArrayList<>(ids)) : Optional.empty();
    }

    /**
     * The names of a question's regions.
     *
     * @param regionRepository resolves ids to regions
     * @param question         the question; its {@code regionIds} may be empty (all regions)
     * @return the region names (empty for "all"), or empty when any id does not resolve
     */
    public static Optional<Set<String>> resolve(RegionRepository regionRepository,
            AskQuestion question) {
        Set<Long> ids = new LinkedHashSet<>(question.regionIds());
        if (ids.isEmpty()) {
            return Optional.of(Set.of());
        }
        List<RegionEntity> found = regionRepository.findAllById(ids);
        Set<String> names = new TreeSet<>();
        Set<Long> foundIds = new LinkedHashSet<>();
        for (RegionEntity region : found) {
            foundIds.add(region.getId());
            names.add(region.getName());
        }
        return foundIds.containsAll(ids) ? Optional.of(names) : Optional.empty();
    }
}
