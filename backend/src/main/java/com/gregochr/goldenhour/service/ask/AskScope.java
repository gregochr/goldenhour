package com.gregochr.goldenhour.service.ask;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The regions a question is about, resolved once and carried as an immutable value.
 *
 * <p>Scope is a safety boundary, so an {@code AskScope} cannot be built from an id that does not
 * resolve to an enabled region: it has no public constructor, and its only public source is
 * {@link AskScopes#resolve}, which refuses an unknown or disabled id rather than widening the
 * question to "every region" (the Ready precompute, in this package, builds the scope of a region it
 * has just read). Everything downstream (the tools, the validator, the prompt, the Ready freshness
 * check, the typed cache) asks the one {@link #contains} question and never re-normalises names or
 * goes back to the database. It is a class, not a record, for exactly that reason: a record's
 * canonical constructor is as public as the record.
 *
 * <p>The scope has two keys, which agree except for a question about several regions. {@link #key}
 * names the scope for the typed cache and is the sorted region ids joined with commas, or
 * {@value #ALL_KEY}. The Ready catalogue is precomputed for one region or for everywhere only, so a
 * question about several regions is served the whole catalogue's Ready set: {@link #readyScope}.
 */
public final class AskScope {

    /** The scope key of every region, shared by the Ready store and the typed cache. */
    public static final String ALL_KEY = "ALL";

    /** Every region: no ids, no names, nothing excluded. */
    public static final AskScope ALL = new AskScope(ALL_KEY, List.of(), Set.of());

    private final String key;
    private final List<Long> regionIds;
    private final Set<String> names;

    private AskScope(String key, List<Long> regionIds, Set<String> names) {
        this.key = key;
        this.regionIds = regionIds;
        this.names = names;
    }

    /**
     * The scope of the given regions. Package-private: {@link AskScopes#resolve} is the way in for
     * everything that has a database to ask, and the Ready precompute builds the scope of a region
     * entity it has just read.
     *
     * @param regionIds the distinct region ids in the order asked
     * @param names     the same regions' names
     * @return the scope; {@link #ALL} when there are no ids
     */
    static AskScope of(List<Long> regionIds, Collection<String> names) {
        if (regionIds == null || regionIds.isEmpty()) {
            return ALL;
        }
        String key = regionIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        Set<String> sorted = names == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(names));
        return new AskScope(key, List.copyOf(regionIds), sorted);
    }

    /**
     * The scope's cache key.
     *
     * @return {@value #ALL_KEY}, or the sorted, distinct region ids joined with commas
     */
    public String key() {
        return key;
    }

    /**
     * The region ids in the order asked.
     *
     * @return the distinct ids; empty means every region
     */
    public List<Long> regionIds() {
        return regionIds;
    }

    /**
     * The regions' names as stored, sorted (the prompt prints them).
     *
     * @return the names; empty means every region
     */
    public Set<String> names() {
        return names;
    }

    /**
     * Whether this scope names every region.
     *
     * @return true when no region is singled out
     */
    public boolean isEverywhere() {
        return regionIds.isEmpty();
    }

    /**
     * Whether a region is within this scope: the one comparison every scope check uses.
     *
     * @param regionName the region to test; matched case-insensitively
     * @return true when the scope is open or names the region
     */
    public boolean contains(String regionName) {
        return isEverywhere() || (regionName != null && names.stream().anyMatch(regionName::equalsIgnoreCase));
    }

    /**
     * The scope whose Ready answers a question about this scope is given: itself for one region, and
     * {@link #ALL} for none or for several (a "My area" spanning more than one region uses the whole
     * catalogue's Ready set).
     *
     * @return the Ready scope
     */
    public AskScope readyScope() {
        return regionIds.size() == 1 ? this : ALL;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AskScope that && key.equals(that.key) && regionIds.equals(that.regionIds)
                && names.equals(that.names);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, regionIds, names);
    }

    @Override
    public String toString() {
        return "AskScope[" + key + (names.isEmpty() ? "" : " " + names) + "]";
    }
}
