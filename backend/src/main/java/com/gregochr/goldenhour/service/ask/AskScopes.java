package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;

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

    private AskScopes() {
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
