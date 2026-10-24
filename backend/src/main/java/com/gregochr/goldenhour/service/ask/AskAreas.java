package com.gregochr.goldenhour.service.ask;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The seam through which Ask reads the admin-owned named areas ("Hadrian's Wall") and the other names of single
 * locations ("the Heugh"). {@link AskSnapshotBuilder} calls it once per snapshot build.
 */
@FunctionalInterface
public interface AskAreas {

    /**
     * The named areas and location aliases.
     *
     * @param areas           the named areas
     * @param locationAliases other names by location id
     */
    record Names(List<AskSnapshot.Area> areas, Map<Long, List<String>> locationAliases) {

        /** No named areas and no aliases. */
        public static final Names NONE = new Names(List.of(), Map.of());

        /** Canonical constructor: takes immutable copies; null reads as empty. */
        public Names {
            areas = areas == null ? List.of() : List.copyOf(areas);
            locationAliases = locationAliases == null ? Map.of() : Map.copyOf(locationAliases);
        }
    }

    /**
     * Reads the current areas and aliases.
     *
     * @return the names; never null
     */
    Names load();

    /** Fewest characters first, then case-insensitive alphabetical: the one order aliases are reported in. */
    Comparator<String> ALIAS_ORDER = Comparator.<String>comparingInt(String::length)
            .thenComparing(String.CASE_INSENSITIVE_ORDER).thenComparing(Comparator.naturalOrder());

    /**
     * Sorts aliases into {@link #ALIAS_ORDER}, so the snapshot does not depend on set or query order.
     *
     * @param aliases the aliases
     * @return a new, sorted list
     */
    static List<String> sortAliases(java.util.Collection<String> aliases) {
        return aliases.stream().sorted(ALIAS_ORDER).toList();
    }
}
