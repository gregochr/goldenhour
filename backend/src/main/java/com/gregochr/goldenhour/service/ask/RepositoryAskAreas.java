package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.service.SubRegionMembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link AskAreas} over the area and location-alias tables (and, for a sub-region, {@code location_sub_region}).
 * The entities' lazy collections are read and mapped
 * inside one read-only transaction, so nothing lazy escapes. A failure is logged and read as no names: Ask must
 * still answer.
 */
@Component
public class RepositoryAskAreas implements AskAreas {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryAskAreas.class);

    private final AreaRepository areaRepository;
    private final LocationAliasRepository aliasRepository;
    private final SubRegionMembershipService subRegionMembership;

    /**
     * Creates the reader.
     *
     * @param areaRepository      the named areas
     * @param aliasRepository     the location aliases
     * @param subRegionMembership where a sub-region's members are read from ({@code location_sub_region})
     */
    public RepositoryAskAreas(AreaRepository areaRepository, LocationAliasRepository aliasRepository,
            SubRegionMembershipService subRegionMembership) {
        this.areaRepository = areaRepository;
        this.aliasRepository = aliasRepository;
        this.subRegionMembership = subRegionMembership;
    }

    @Override
    @Transactional(readOnly = true)
    public Names load() {
        try {
            // A sub-region is an area like any other to Ask, but its members are not in area_location.
            Map<Long, List<Long>> subRegionMembers = subRegionMembership.membersByArea();
            List<AskSnapshot.Area> areas = new ArrayList<>();
            for (AreaEntity area : areaRepository.findAll()) {
                Set<Long> members = area.isSubRegion()
                        ? new HashSet<>(subRegionMembers.getOrDefault(area.getId(), List.of()))
                        : area.getLocationIds();
                areas.add(new AskSnapshot.Area(area.getName(), AskAreas.sortAliases(area.getAliases()), members));
            }
            areas.sort(Comparator.comparing(AskSnapshot.Area::name, String.CASE_INSENSITIVE_ORDER));
            Map<Long, List<String>> byLocation = new LinkedHashMap<>();
            for (LocationAliasEntity alias : aliasRepository.findAll()) {
                byLocation.computeIfAbsent(alias.getLocationId(), id -> new ArrayList<>()).add(alias.getAlias());
            }
            Map<Long, List<String>> sorted = new LinkedHashMap<>();
            byLocation.forEach((id, names) -> sorted.put(id, AskAreas.sortAliases(names)));
            return new Names(areas, sorted);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Could not read the named areas and aliases; answering without them: {}", e.toString());
            return Names.NONE;
        }
    }
}
