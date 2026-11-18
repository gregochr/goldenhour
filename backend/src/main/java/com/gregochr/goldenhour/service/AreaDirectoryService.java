package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.model.AreaAliasRow;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.AreaDirectoryRow;
import com.gregochr.goldenhour.model.SubRegionMemberRow;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.ask.AskAreas;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reader-facing directory of areas behind Find: every named area and sub-region, its other names, and its
 * ENABLED members (docs/engineering/find-and-favourites-plan.md D1).
 *
 * <p>Built from four flat projection reads (areas with their region, aliases, named members, enabled
 * location ids) plus the sub-region membership read, so the statement count is constant however many areas
 * there are. Member mapping matches {@code RepositoryAskAreas}: a named area's members are
 * {@code area_location}, a sub-region's are {@code location_sub_region}.
 *
 * <p>Every reader gets the whole directory in v1, but the entry point takes the reader on purpose: tightening
 * a Lite reader to their own sub-regions later is a change to this one method (and moves the path off the
 * ETag-shared list).
 */
@Service
public class AreaDirectoryService {

    private final AreaRepository areaRepository;
    private final LocationRepository locationRepository;
    private final SubRegionMembershipService subRegionMembership;

    /**
     * Constructs the service.
     *
     * @param areaRepository      the areas, their aliases and named-area members
     * @param locationRepository  the locations, for which ones are enabled
     * @param subRegionMembership where a sub-region's members are read from
     */
    public AreaDirectoryService(AreaRepository areaRepository, LocationRepository locationRepository,
            SubRegionMembershipService subRegionMembership) {
        this.areaRepository = areaRepository;
        this.locationRepository = locationRepository;
        this.subRegionMembership = subRegionMembership;
    }

    /**
     * The areas a reader may search for, by name.
     *
     * @param reader the caller's authentication; ignored in v1 (every reader sees every area), accepted so
     *               the directory can later be narrowed per reader without touching the callers
     * @return every area with its enabled members, case-insensitively by name
     */
    @Transactional(readOnly = true)
    public List<AreaDirectoryEntry> directoryFor(Authentication reader) {
        Set<Long> enabled = new HashSet<>(locationRepository.findEnabledIds());
        Map<Long, List<String>> aliases = new HashMap<>();
        for (AreaAliasRow row : areaRepository.findAliasRows()) {
            aliases.computeIfAbsent(row.areaId(), id -> new ArrayList<>()).add(row.alias());
        }
        Map<Long, List<Long>> members = new HashMap<>();
        for (SubRegionMemberRow row : areaRepository.findNamedMemberRows()) {
            members.computeIfAbsent(row.areaId(), id -> new ArrayList<>()).add(row.locationId());
        }
        Map<Long, List<Long>> subRegionMembers = subRegionMembership.membersByArea();
        List<AreaDirectoryEntry> entries = new ArrayList<>();
        for (AreaDirectoryRow area : areaRepository.findDirectoryRows()) {
            AreaKind kind = area.kind() == null ? AreaKind.NAMED : area.kind();
            Map<Long, List<Long>> source = kind == AreaKind.SUB_REGION ? subRegionMembers : members;
            List<Long> ids = source.getOrDefault(area.id(), List.of()).stream()
                    .filter(enabled::contains).distinct().sorted().toList();
            if (ids.isEmpty()) {
                continue; // nothing in it to open: an area whose spots are all disabled is not offered
            }
            entries.add(new AreaDirectoryEntry(area.id(), area.name(), kind,
                    AskAreas.sortAliases(aliases.getOrDefault(area.id(), List.of())),
                    area.regionId(), area.regionName(), ids));
        }
        entries.sort(Comparator.comparing(AreaDirectoryEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AreaDirectoryEntry::id));
        return entries;
    }
}
